// ====================================================================================
// БЛОК ВИМІРУ ЄМНОСТІ (EnergyBlock)
//
// ЩО ВІН РОБИТЬ:
// 1. Стежить за точним SOC і пожиттєвими лічильниками енергії.
// 2. Коли шкала помітно зрушила, а лічильник відданої енергії набрав близько
//    кіловат-години, віддає це в криву як один замір ФОРМИ шкали.
// 3. Ловить зарядки, що починалися з низьких відсотків, і міряє ними ПОВНУ
//    ємність. Доти повна ємність — аксіома з відомого пакета.
// 4. Тримає готову криву в GeneralData і зберігає її у файл.
// 5. Виконує запит «забути криву».
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
import com.kirianov.kiasoulevplus2.Data.Pack
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

    /**
     * Початок зарядки: відсоток і лічильник прийнятої енергії в той момент.
     *
     * Зарядка з низьких відсотків до сотні — єдиний прямий вимір ПОВНОЇ ємності,
     * який узагалі можна зняти з машини. Лічильник прийнятої енергії веде сама
     * батарея, тож втрат бортового зарядного в цьому числі немає — на відміну від
     * показів розетки, з яких і взялася аксіома.
     */
    private var chargeStart: ChargeStart? = null

    fun start(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            store.load()?.let { saved ->
                levels.restore(saved)
                if (saved.pendingSocPercent >= 0.0) {
                    chargeStart = ChargeStart(
                        socPercent = saved.pendingSocPercent,
                        chargedKwh = saved.pendingChargedKwh,
                        dischargedKwh = saved.pendingDischargedKwh,
                        atMs = saved.pendingAtMs,
                    )
                }
            }
            publish()

            GeneralData.state.collect { state ->
                if (state.curve.request == CurveRequest.Reset) {
                    GeneralData.clearCurveRequest()
                    levels.reset()
                    anchor = null
                    chargeStart = null
                    withContext(ioDispatcher) { store.clear() }
                    publish()
                    return@collect
                }

                // Напругу беремо з КОЖНОГО читання, а не лише з тих, де зрушив
                // SOC: її крива набирається на порядок повільніше — їй потрібні
                // десятки замірів у кошику, щоб прибрати просадку прямою.
                val learnedVolts = learnVoltage(state)

                val reading = readingOf(state) ?: return@collect
                val learnedShape = accept(reading)
                val charge = watchCharge(reading)

                // Зберігаємо й тоді, коли лише поставили закладку: без цього
                // початок зарядки не дожив би до ранку.
                if (learnedShape || learnedVolts || charge != ChargeWatch.Nothing) {
                    withContext(ioDispatcher) { store.save(snapshot()) }
                }
                if (learnedShape || learnedVolts || charge == ChargeWatch.Learned) publish()
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

    /**
     * Веде зарядну сесію й закриває її замiром повної ємності, коли шкала дійшла
     * до сотні.
     *
     * ЗАРЯДКУ МИ ПРАКТИЧНО НІКОЛИ НЕ БАЧИМО ЦІЛКОМ, і на це розраховано все тут.
     * OBD-порт гасне, щойно авто йде в режим зарядки: у журналі адаптер відвалюється
     * за хвилину після початку й до ранку не повертається. Тому замір тримається на
     * ДВОХ КІНЦЯХ — закладці на початку і першому читанні, коли шкала вже вгорі, — а
     * не на спостереженні за процесом. Різниця пожиттєвого лічильника між ними і є
     * прийнята енергія, скільки б годин між ними не пройшло.
     *
     * Через це ж закладка лежить у файлі, а не в пам'яті: телефон їде з машиною, і
     * до ранку процес може не дожити.
     *
     * Якір ставиться на будь-якому зарядному читанні з низьким відсотком, а не лише
     * на переході «не заряджаюсь → заряджаюсь»: телефон часто під'єднується посеред
     * зарядки, і чекати наступного разу означало б не зміряти нічого.
     */
    private fun watchCharge(reading: Reading): ChargeWatch {
        val started = chargeStart
        if (started == null) {
            if (reading.charging && reading.socPercent <= EnergyLevels.MAX_START_PERCENT) {
                chargeStart = ChargeStart(
                    socPercent = reading.socPercent,
                    chargedKwh = reading.chargedKwh,
                    dischargedKwh = reading.dischargedKwh,
                    atMs = reading.atMs,
                )
                return ChargeWatch.Anchored
            }
            return ChargeWatch.Nothing
        }

        // Авто віддало більше, ніж могло з'їсти, просто стоячи на зарядці — отже
        // воно ще й їхало. Розділити нічим, тож закладку викидаємо.
        val hours = (reading.atMs - started.atMs).coerceAtLeast(0L) / MS_PER_HOUR
        val allowance = maxOf(MIN_DISCHARGE_ALLOWANCE_KWH, PARASITIC_DRAW_KW * hours)
        if (reading.dischargedKwh - started.dischargedKwh > allowance) {
            chargeStart = null
            return ChargeWatch.Anchored
        }

        // Ще не долило. Чекаємо далі — хоч до завтра: закладка нікуди не дінеться.
        if (reading.socPercent < EnergyLevels.MIN_FINISH_PERCENT) return ChargeWatch.Nothing

        val learned = levels.learnFullCharge(
            fromPercent = started.socPercent,
            toPercent = reading.socPercent,
            energyInKwh = reading.chargedKwh - started.chargedKwh,
        )
        // Хай там прийнято чи ні, сесія закрита: другого разу той самий вимір
        // додавати не можна.
        chargeStart = null
        return if (learned) ChargeWatch.Learned else ChargeWatch.Anchored
    }

    /** Що сталося із закладкою на зарядку за це читання. */
    private enum class ChargeWatch {
        /** Нічого не змінилося. */
        Nothing,

        /** Закладку поставлено або викинуто: змінилося те, що треба зберегти. */
        Anchored,

        /** Замір повної ємності додано. */
        Learned,
    }

    private fun publish() {
        // Повна ємність: вимір із глибокої зарядки, а поки його немає — аксіома з
        // відомого пакета. Місцевий нахил кривої тут НЕ використовується: угорі
        // шкали відсоток найдешевший, і розтягнути його на всю шкалу означало б
        // занизити ємність у рази.
        val measuredTotal = levels.measuredTotalKwh
        val total = measuredTotal ?: Pack.USABLE_CAPACITY_KWH
        val curve = levels.curve(total)

        GeneralData.updateCurve { current ->
            current.copy(
                points = curve,
                measuredFromPercent = levels.measuredFromPercent,
                measuredToPercent = levels.measuredToPercent,
                coveredPercent = levels.coveredPercent,
                voltagePoints = levels.voltageCurve(),
                totalKwh = total,
                totalMeasured = measuredTotal != null,
                fullChargeSamples = levels.fullChargeSamples,
                samples = levels.samples,
            )
        }
    }

    /** Знімок разом із закладкою: у файл вони мусять іти нероздільно. */
    private fun snapshot(): LevelsSnapshot {
        val pending = chargeStart
        return levels.snapshot().copy(
            pendingSocPercent = pending?.socPercent ?: -1.0,
            pendingChargedKwh = pending?.chargedKwh ?: 0.0,
            pendingDischargedKwh = pending?.dischargedKwh ?: 0.0,
            pendingAtMs = pending?.atMs ?: 0L,
        )
    }

    private data class ChargeStart(
        val socPercent: Double,
        val chargedKwh: Double,
        val dischargedKwh: Double,
        /** Час закладки: за ним рахується поблажливість до стоянкового відбору. */
        val atMs: Long,
    )

    /**
     * Напруга проти шкали. Зарядка виключена: там напругу тримає зарядний, а не
     * батарея, і це вже не характеристика комірок.
     */
    private fun learnVoltage(state: State): Boolean {
        val vehicle = state.vehicle
        val bms = state.bms
        if (!vehicle.hasPreciseSoc || !bms.hasData) return false
        if (vehicle.charging.isCharging) return false

        return levels.learnVoltage(
            socPercent = vehicle.preciseSocPercent,
            volts = bms.batteryVoltage,
            amps = bms.batteryCurrent,
        )
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

        private const val MS_PER_HOUR = 3_600_000.0

        /** Шкала подеколи здригається на десяті — це ще не зарядка. */
        const val SOC_RISE_TOLERANCE = 0.2

        /**
         * Скільки авто їсть із ТЯГОВОЇ батареї, просто стоячи на зарядці, кВт.
         *
         * Поріг тут пропорційний часу, а не абсолютний, і це принципово: авто на
         * зарядці живить свою ж електроніку від тягового пакета, тож лічильник
         * ВІДДАНОЇ енергії росте й на стоянці. За сорок хвилин це виходило
         * 0.3 кВт·год, за ніч набіжить кілька — абсолютний поріг відкидав би
         * саме ті зарядки, заради яких усе це й робиться.
         */
        const val PARASITIC_DRAW_KW = 0.8

        /** Мінімальна поблажливість: на коротких паузах працює крок лічильника. */
        const val MIN_DISCHARGE_ALLOWANCE_KWH = 0.3
    }
}
