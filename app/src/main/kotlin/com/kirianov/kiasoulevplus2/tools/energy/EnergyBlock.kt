// ====================================================================================
// БЛОК ВИМІРУ ЄМНОСТІ (EnergyBlock)
//
// ЩО ВІН РОБИТЬ:
// 1. Стежить за точним SOC і пожиттєвими лічильниками енергії.
// 2. Коли шкала помітно зрушила, а лічильник відданої енергії набрав близько
//    кіловат-години, віддає це в криву як один замір.
// 3. Тримає готову криву в GeneralData і зберігає її у файл.
// 4. Виконує запит «забути криву».
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ інтегрує потужність і НЕ залежить від знака струму: усе рахується
//   різницею лічильників, які веде сама батарея.
// - НЕ вимагає неперервних даних. Обрив зв'язку не псує замір, а лише відкладає
//   його: лічильники абсолютні, і після повернення відлік просто починається
//   від нового якоря.
//
// ДВА ВИПАДКИ, КОЛИ ЯКІР СКИДАЄТЬСЯ БЕЗ ЗАМІРУ:
//  - зарядка. Тоді шкала йде вгору, і різниця лічильників означає протилежне;
//  - надто довга пауза. За неї авто могло і поїхати, і зарядитися, а різниця
//    лічильників цього не розділяє. Краще пропустити замір, ніж вписати в криву
//    суміш поїздки із зарядкою.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.energy

import com.kirianov.kiasoulevplus2.Data.BatteryCurve
import com.kirianov.kiasoulevplus2.Data.CurveRequest
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EnergyBlock(
    private val store: EnergyStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val levels = EnergyLevels()

    /** Точка, від якої міряється поточний інтервал. */
    private var anchor: Anchor? = null

    /**
     * Останній ПОБАЧЕНИЙ відсоток і час, коли його побачили.
     *
     * Читання беруться лише тоді, коли зрушив точний SOC, і це не дрібниця.
     * Лічильники оновлюються щосекунди, а SOC приходить кадром 598 раз на
     * півхвилини. Якби якір ставився на кожне читання лічильника, його відсоток
     * був би застарілий на цілі десятки секунд ходу — а міряємо ми саме
     * «стільки енергії на стільки відсотків». Прив'язка обох кінців до моментів
     * зміни SOC цей перекіс прибирає.
     */
    private var lastSocPercent: Double? = null
    private var lastReadingMs: Long? = null

    fun start(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            store.load()?.let { levels.restore(it) }
            publish()

            GeneralData.state.collect { state ->
                if (state.curve.request == CurveRequest.Reset) {
                    GeneralData.clearCurveRequest()
                    levels.reset()
                    anchor = null
                    withContext(ioDispatcher) { store.clear() }
                    publish()
                    return@collect
                }

                val reading = readingOf(state) ?: return@collect
                if (accept(reading)) {
                    withContext(ioDispatcher) { store.save(levels.snapshot()) }
                    publish()
                }
            }
        }
    }

    /**
     * @return чи змінилася крива. Тільки тоді має сенс і зберігати, і публікувати:
     * читань приходить кілька на секунду, а замірів — один на кілька кілометрів.
     */
    private fun accept(reading: Reading): Boolean {
        // Пауза міряється між ЧИТАННЯМИ, а не від якоря: сам інтервал цілком
        // законно триває кілька хвилин — стільки треба, щоб лічильник набрав
        // кіловат-годину. А от діра між читаннями означає, що ми не дивилися.
        // Nullable, а не нуль-ознака: нуль — це теж момент часу, і на ньому
        // перша ж перевірка паузи вийшла б хибною.
        val gapMs = lastReadingMs?.let { reading.atMs - it } ?: 0L
        lastReadingMs = reading.atMs

        val previous = anchor
        if (previous == null) {
            anchor = reading.toAnchor()
            return false
        }

        // Зарядка або діра в спостереженнях: інтервал непридатний.
        // Шкала вгору — теж зарядка, просто ознаки 581 ми не бачили.
        if (reading.charging ||
            gapMs > MAX_GAP_MS ||
            reading.socPercent > previous.socPercent + SOC_RISE_TOLERANCE
        ) {
            anchor = reading.toAnchor()
            return false
        }

        val out = reading.dischargedKwh - previous.dischargedKwh
        // Ще рано: якір НЕ рухаємо, інакше інтервал ніколи не набрав би ні
        // кіловат-години, ні відсотків шкали.
        if (out < MIN_STEP_KWH) return false

        val net = out - (reading.chargedKwh - previous.chargedKwh)
        val learned = levels.learn(previous.socPercent, reading.socPercent, net)

        // Якір переїжджає, якщо замір узято або якщо інтервал розтягнувся так,
        // що вже не буде взятий: тримати його далі означає нічого не міряти.
        if (learned || out > MAX_STEP_KWH) anchor = reading.toAnchor()
        return learned
    }

    private fun publish() {
        val curve = levels.curve()
        GeneralData.updateCurve { current ->
            current.copy(
                points = curve,
                measuredFromPercent = levels.measuredFromPercent,
                measuredToPercent = levels.measuredToPercent,
                coveredPercent = levels.coveredPercent,
                fullKwh = levels.fullKwh(),
                samples = levels.samples,
            )
        }
    }

    private fun readingOf(state: State): Reading? {
        val vehicle = state.vehicle
        val bms = state.bms
        if (!vehicle.hasPreciseSoc) return null
        if (bms.cumulativeEnergyDischargedKwh <= 0.0 || bms.cumulativeEnergyChargedKwh <= 0.0) return null

        // Тільки моменти, коли зрушив сам SOC: див. пояснення в lastSocPercent.
        if (vehicle.preciseSocPercent == lastSocPercent) return null
        lastSocPercent = vehicle.preciseSocPercent

        return Reading(
            socPercent = vehicle.preciseSocPercent,
            dischargedKwh = bms.cumulativeEnergyDischargedKwh,
            chargedKwh = bms.cumulativeEnergyChargedKwh,
            charging = vehicle.charging.isCharging,
            atMs = nowMs(),
        )
    }

    private data class Reading(
        val socPercent: Double,
        val dischargedKwh: Double,
        val chargedKwh: Double,
        val charging: Boolean,
        val atMs: Long,
    ) {
        fun toAnchor() = Anchor(socPercent, dischargedKwh, chargedKwh, atMs)
    }

    private data class Anchor(
        val socPercent: Double,
        val dischargedKwh: Double,
        val chargedKwh: Double,
        val atMs: Long,
    )

    // internal, а не private: межі замірів стережуть тести.
    internal companion object {
        /**
         * Скільки має набрати лічильник відданої енергії, щоб замір мав сенс.
         *
         * Крок лічильника — 0.1 кВт·год. Кіловат-година це десять кроків, тобто
         * близько 10 % похибки на замір; менше брати марно, більше — рідше
         * заміри. На звичайній витраті це приблизно п'ять кілометрів дороги.
         */
        const val MIN_STEP_KWH = 1.0

        /**
         * Довший інтервал не візьмемо вже ніколи: або SOC стоїть, або читання
         * непослідовні. Тримати такий якір означає нічого не міряти.
         */
        const val MAX_STEP_KWH = 5.0

        /**
         * Діра між читаннями, після якої інтервал непридатний: за неї авто
         * могло і проїхати, і зарядитися, а лічильники цього не розділяють.
         * Десять хвилин — та сама межа, з якої облік зарядок починає підозрювати
         * зарядку без телефона.
         */
        const val MAX_GAP_MS = 10 * 60 * 1000L

        /** Шкала подеколи здригається на десяті — це ще не зарядка. */
        const val SOC_RISE_TOLERANCE = 0.2
    }
}
