// ====================================================================================
// ВИМІРЯНА КРИВА ЄМНОСТІ (EnergyLevels)
//
// Тримає, скільки кВт·год виявилося в кожному відсотку шкали, і складає з цього
// криву. Нічого не читає й нікуди не пише — чистий стан, тож перевіряється тестами.
//
// ЯК ЦЕ МІРЯЄТЬСЯ. Один замір — це «шкала пройшла від A % до B %, і за цей час
// пожиттєвий лічильник відданої енергії виріс на стільки, а прийнятої на стільки».
// Різниця й є енергія, яку містила ця ділянка шкали. Ніякого інтегрування, ніяких
// вимог до неперервності даних: лічильники веде сама батарея, тому ні обрив
// Bluetooth, ні пауза в опитуванні заміру не псують.
//
// ЧОМУ КОШИКИ ПО 1 %. Замір майже ніколи не вкладається рівно в один відсоток, тож
// його енергія розкладається по кошиках пропорційно пройденій у кожному частині.
// Кошик пам'ятає СУМИ — енергію і пройдені відсотки, — а не готове середнє. Тоді
// повторний прохід тим самим місцем шкали автоматично усереднюється з вагою: довший
// прохід важить більше, ніж короткий, і крива уточнюється з кожною поїздкою.
//
// ГОЛОВНЕ ПРАВИЛО ЦЬОГО ФАЙЛУ: МІСЦЕВИЙ НАХИЛ НЕ РОЗТЯГУЄТЬСЯ НА ВСЮ ШКАЛУ.
//
// Шкала цього авто різко нерівна, і це не дефект вимірів, а фізика: BMS накладає
// заводську таблицю «напруга → відсоток» на комірки з іншою хімією. За словами
// водія відсоток угорі шкали коштує близько кілометра, посередині близько двох, а
// в кінці від п'яти до десяти. Тобто нахил гуляє в рази.
//
// Через це «зміряли 0.29 кВт·год/% на ділянці 88–95 % ⇒ у батареї 29 кВт·год» —
// груба помилка: угорі шкали відсоток найдешевший, і саме там ми й міряли першим.
// Повна ємність береться ОКРЕМО: спершу як аксіома (пакет відомий), а далі як
// вимір із зарядки, що починалася з низьких відсотків. Криві ж заміри задають
// ФОРМУ — те, як ця ємність розподілена по шкалі.
//
// НЕВИМІРЯНІ КОШИКИ. Крива має бути суцільною від 0 до 100 %, інакше її нема як
// нарисувати. Порожнім місцям нахил ПРОТЯГУЄТЬСЯ між сусідніми виміряними: між
// двома островами — плавно від одного до іншого, за краями — рівно як на
// найближчому виміряному. І вже після цього все невиміряне разом множиться на
// один коефіцієнт, щоб сума кривої дорівнювала повній ємності.
//
// Рівний нахил на порожніх місцях, який був тут спершу, давав видимі зломи на
// кожній межі острова — злам, якого в батареї немає, бо він походить від способу
// малювання, а не від комірок. Кожна точка при цьому однаково знає, вимір вона чи
// доведення, і на графіку це видно різним кольором.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.energy

import com.kirianov.kiasoulevplus2.Data.CurvePoint
import kotlin.math.max
import kotlin.math.min

class EnergyLevels {

    private val sumKwh = DoubleArray(BINS)
    private val sumPercent = DoubleArray(BINS)

    var samples: Int = 0
        private set

    /**
     * Заміри ПОВНОЇ ємності — із зарядок, що починалися з низьких відсотків.
     *
     * Тримаються окремо від кошиків, бо це принципово інший вимір: кошики кажуть
     * про форму шкали, а це — про її загальну «вагу» в кіловат-годинах.
     */
    private var totalSumKwh = 0.0

    var fullChargeSamples: Int = 0
        private set

    /**
     * Додає замір: шкала пройшла від [fromPercent] до [toPercent], і за цей час
     * з батареї пішло [netKwh] кВт·год.
     *
     * @return чи прийнято замір. Відмова означає, що числа непослідовні або
     * нефізичні: краще не мати виміру, ніж мати вигаданий.
     */
    fun learn(fromPercent: Double, toPercent: Double, netKwh: Double): Boolean {
        val span = fromPercent - toPercent
        if (span < MIN_SPAN_PERCENT || !span.isFinite()) return false
        if (netKwh <= 0.0 || !netKwh.isFinite()) return false

        // Скільки кВт·год на відсоток шкали. Поза цими межами це не батарея на
        // 27 або 51 кВт·год, а помилка читання.
        val rate = netKwh / span
        if (rate < MIN_RATE_KWH_PER_PERCENT || rate > MAX_RATE_KWH_PER_PERCENT) return false

        val low = max(0.0, min(fromPercent, toPercent))
        val high = min(100.0, max(fromPercent, toPercent))
        if (high <= low) return false

        var bin = binOf(low)
        while (bin < BINS) {
            val binLow = bin * BIN_WIDTH_PERCENT
            val binHigh = binLow + BIN_WIDTH_PERCENT
            if (binLow >= high) break

            val part = min(high, binHigh) - max(low, binLow)
            if (part > 0.0) {
                sumPercent[bin] += part
                sumKwh[bin] += rate * part
            }
            bin++
        }

        samples++
        return true
    }

    /**
     * Додає замір повної ємності із зарядки.
     *
     * ЧОМУ САМЕ ЗАРЯДКА З НИЗЬКИХ ВІДСОТКІВ. Лічильник прийнятої енергії веде сама
     * батарея, тобто вже по той бік бортового зарядного: втрат зарядного в цьому
     * числі немає, на відміну від показів розетки. А низький старт потрібен, щоб
     * зарядка охопила майже всю шкалу — тоді поділити енергію на пройдені відсотки
     * означає отримати ємність усієї шкали, а не однієї її ділянки.
     *
     * @return чи прийнято замір.
     */
    fun learnFullCharge(fromPercent: Double, toPercent: Double, energyInKwh: Double): Boolean {
        if (fromPercent > MAX_START_PERCENT || toPercent < MIN_FINISH_PERCENT) return false
        val span = toPercent - fromPercent
        if (span < MIN_CHARGE_SPAN_PERCENT) return false
        if (energyInKwh <= 0.0 || !energyInKwh.isFinite()) return false

        val total = energyInKwh / span * 100.0
        if (total < MIN_TOTAL_KWH || total > MAX_TOTAL_KWH) return false

        totalSumKwh += total
        fullChargeSamples++
        return true
    }

    /** Виміряна повна ємність, кВт·год; null — глибоких зарядок ще не було. */
    val measuredTotalKwh: Double?
        get() = if (fullChargeSamples > 0) totalSumKwh / fullChargeSamples else null

    /** Нахил кривої в цьому місці шкали, кВт·год на відсоток; null — не міряли. */
    fun rateAt(socPercent: Double): Double? = rateOfBin(binOf(socPercent))

    /** Середній нахил по всьому виміряному. Ним доводиться те, чого не міряли. */
    fun averageRate(): Double {
        val energy = sumKwh.sum()
        val percent = sumPercent.sum()
        return if (percent > 0.0) energy / percent else 0.0
    }

    val measuredFromPercent: Double?
        get() = sumPercent.indexOfFirst { it > 0.0 }.takeIf { it >= 0 }?.let { it * BIN_WIDTH_PERCENT }

    val measuredToPercent: Double?
        get() = sumPercent.indexOfLast { it > 0.0 }.takeIf { it >= 0 }?.let { (it + 1) * BIN_WIDTH_PERCENT }

    /** Яку частину шкали виміряно, у відсотках. */
    val coveredPercent: Double
        get() = sumPercent.count { it > 0.0 } * BIN_WIDTH_PERCENT

    /** Скільки кВт·год у виміряних кошиках разом. */
    fun measuredEnergyKwh(): Double = (0 until BINS).sumOf { bin ->
        (rateOfBin(bin) ?: 0.0) * BIN_WIDTH_PERCENT
    }

    /**
     * Крива від 0 до 100 % через один відсоток, прив'язана до повної ємності
     * [totalKwh].
     *
     * Точка на X відсотках — це стільки кВт·год, скільки в батареї лишається на
     * цьому відсотку.
     *
     * Виміряні кошики лишаються зі своїм зміряним нахилом, а невиміряній частині
     * шкали віддається решта повної ємності, розкладена рівно. Саме тому місцевий
     * нахил не розтягується на всю шкалу: сума кривої задана наперед, і кожен новий
     * замір лише перерозподіляє її, а не роздуває й не зменшує.
     */
    fun curve(totalKwh: Double): List<CurvePoint> {
        if (samples == 0 || totalKwh <= 0.0) return emptyList()

        val measured = DoubleArray(BINS) { rateOfBin(it) ?: Double.NaN }
        val measuredEnergy = measuredEnergyKwh()
        val prior = stretchOverGaps(measured)

        // Виміряне вже не влазить у повну ємність. Це або надто мала аксіома, або
        // заміри з надто грубою роздільністю; тиснемо їх пропорційно, лишаючи
        // невиміряній частині хоч якийсь нахил, — інакше крива пішла б униз.
        val hasGaps = measured.any { it.isNaN() }
        val squeeze = if (hasGaps && measuredEnergy > totalKwh * MAX_MEASURED_SHARE) {
            totalKwh * MAX_MEASURED_SHARE / measuredEnergy
        } else {
            1.0
        }

        // Один коефіцієнт на все невиміряне: форма там уже задана протягуванням,
        // лишається підігнати вагу, щоб сума кривої дорівнювала повній ємності.
        val priorTail = (0 until BINS).sumOf { if (measured[it].isNaN()) prior[it] * BIN_WIDTH_PERCENT else 0.0 }
        val tailScale = if (priorTail > 0.0) {
            ((totalKwh - measuredEnergy * squeeze) / priorTail).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val points = mutableListOf(CurvePoint(0.0, 0.0, measured = !measured[0].isNaN()))
        var energy = 0.0

        for (bin in 0 until BINS) {
            val known = measured[bin]
            val rate = if (known.isNaN()) prior[bin] * tailScale else known * squeeze
            energy += rate * BIN_WIDTH_PERCENT
            points += CurvePoint(
                socPercent = (bin + 1) * BIN_WIDTH_PERCENT,
                energyKwh = energy,
                measured = !known.isNaN(),
            )
        }
        return points
    }

    /**
     * Протягує нахил на порожні місця: між двома виміряними — плавно від одного до
     * іншого, за краями — рівно як на найближчому виміряному.
     *
     * Це найпростіше припущення, яке не вигадує форми: нахил шкали змінюється
     * плавно, і між двома відомими точками пряма — чесніше, ніж стрибок на
     * середнє по всій шкалі.
     */
    private fun stretchOverGaps(measured: DoubleArray): DoubleArray {
        val filled = DoubleArray(BINS)
        val known = (0 until BINS).filter { !measured[it].isNaN() }
        if (known.isEmpty()) return filled

        for (bin in 0 until BINS) {
            if (!measured[bin].isNaN()) {
                filled[bin] = measured[bin]
                continue
            }
            val before = known.lastOrNull { it < bin }
            val after = known.firstOrNull { it > bin }
            filled[bin] = when {
                before != null && after != null -> {
                    val share = (bin - before).toDouble() / (after - before)
                    measured[before] + share * (measured[after] - measured[before])
                }
                before != null -> measured[before]
                else -> measured[after!!]
            }
        }
        return filled
    }

    fun snapshot() = LevelsSnapshot(
        sumKwh = sumKwh.copyOf(),
        sumPercent = sumPercent.copyOf(),
        samples = samples,
        totalSumKwh = totalSumKwh,
        fullChargeSamples = fullChargeSamples,
    )

    fun restore(snapshot: LevelsSnapshot) {
        if (snapshot.sumKwh.size != BINS || snapshot.sumPercent.size != BINS) return
        snapshot.sumKwh.copyInto(sumKwh)
        snapshot.sumPercent.copyInto(sumPercent)
        samples = snapshot.samples
        totalSumKwh = snapshot.totalSumKwh
        fullChargeSamples = snapshot.fullChargeSamples
    }

    fun reset() {
        sumKwh.fill(0.0)
        sumPercent.fill(0.0)
        samples = 0
        totalSumKwh = 0.0
        fullChargeSamples = 0
    }

    private fun rateOfBin(bin: Int): Double? =
        if (sumPercent[bin] > MIN_BIN_PERCENT) sumKwh[bin] / sumPercent[bin] else null

    private fun binOf(socPercent: Double): Int =
        (socPercent / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BINS - 1)

    companion object {
        const val BINS = 100
        const val BIN_WIDTH_PERCENT = 1.0

        /**
         * Коротший замір нічого не дає: крок лічильника 0.1 кВт·год, і на пів
         * відсотка шкали це вже десятки відсотків похибки.
         */
        const val MIN_SPAN_PERCENT = 0.5

        /**
         * Скільки відсотка кошика має набратися, щоб вважати його виміряним.
         * Дотик краєм замір не робить.
         */
        const val MIN_BIN_PERCENT = 0.1

        /** Батарея на 10 кВт·год — менше не буває навіть у гібрида. */
        const val MIN_RATE_KWH_PER_PERCENT = 0.1

        /**
         * Батарея на 150 кВт·год — більше в Soul EV не влізе фізично.
         *
         * Межа широка навмисно: у кінці шкали відсоток коштує в рази більше, ніж
         * угорі, і вузька межа відкидала б саме ті заміри, яких найбільше не
         * вистачає.
         */
        const val MAX_RATE_KWH_PER_PERCENT = 1.5

        /** Зарядка мусить починатися не вище цього відсотка, щоб міряти повну ємність. */
        const val MAX_START_PERCENT = 6.0

        /** І доходити щонайменше до цього. */
        const val MIN_FINISH_PERCENT = 98.0

        /** Скільки відсотків шкали має охопити зарядка. */
        const val MIN_CHARGE_SPAN_PERCENT = 90.0

        /** Правдоподібні межі повної ємності цього пакета, кВт·год. */
        const val MIN_TOTAL_KWH = 20.0
        const val MAX_TOTAL_KWH = 120.0

        /**
         * Яку частку повної ємності дозволено зайняти виміряним кошикам, поки
         * шкала покрита не повністю. Решта лишається невиміряній частині: нуль
         * нахилу там означав би «на цих відсотках енергії немає взагалі».
         */
        const val MAX_MEASURED_SHARE = 0.95
    }
}

/** Усе, що крива пам'ятає, у вигляді, придатному для файлу. */
data class LevelsSnapshot(
    val sumKwh: DoubleArray,
    val sumPercent: DoubleArray,
    val samples: Int,
    val totalSumKwh: Double = 0.0,
    val fullChargeSamples: Int = 0,

    /**
     * Закладка на початок зарядки, яку ще не дораховано.
     *
     * Лежить у тому самому файлі, що й заміри, бо мусить пережити не лише обрив
     * зв'язку, а й перезапуск застосунку: телефон їде з машиною, зарядка йде без
     * нього годинами, і закладку в пам'яті процесу до ранку не донести.
     *
     * Від'ємний відсоток означає «закладки немає».
     */
    val pendingSocPercent: Double = -1.0,
    val pendingChargedKwh: Double = 0.0,
    val pendingDischargedKwh: Double = 0.0,
) {
    // equals/hashCode для масивів data class не робить сам, а тести їх порівнюють.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LevelsSnapshot) return false
        return samples == other.samples &&
            fullChargeSamples == other.fullChargeSamples &&
            totalSumKwh == other.totalSumKwh &&
            pendingSocPercent == other.pendingSocPercent &&
            pendingChargedKwh == other.pendingChargedKwh &&
            pendingDischargedKwh == other.pendingDischargedKwh &&
            sumKwh.contentEquals(other.sumKwh) &&
            sumPercent.contentEquals(other.sumPercent)
    }

    override fun hashCode(): Int =
        (sumKwh.contentHashCode() * 31 + sumPercent.contentHashCode()) * 31 + samples
}
