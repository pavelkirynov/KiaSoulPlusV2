// ====================================================================================
// ВИМІРЯНА КРИВА ЄМНОСТІ (EnergyLevels)
//
// Тримає, скільки кВт·год виявилося в кожному відсотку шкали, і складає з цього
// криву. Нічого не читає й нікуди не пише — чистий стан, тож перевіряється тестами.
//
// ЯК ЦЕ МІРЯЄТЬСЯ. Один замір — це «шкала пройшла від A % до B %, і за цей час
// пожиттєвий лічильник відданої енергії виріс на стільки, а прийнятої на стільки».
// Різниця й є енергія, яку містила ця ділянка шкали. Ніякого інтегрування, ніяких
// вимог до неперервності даних: лічильники веде сама батарея.
//
// ЧОМУ КОШИКИ ПО 1 %. Замір майже ніколи не вкладається рівно в один відсоток, тож
// його енергія розкладається по кошиках пропорційно пройденій у кожному частині.
// Кошик пам'ятає СУМИ — енергію і пройдені відсотки, — а не готове середнє. Тоді
// повторний прохід тим самим місцем шкали автоматично усереднюється з вагою: довший
// прохід важить більше, ніж короткий, і крива уточнюється з кожною поїздкою.
//
// НЕВИМІРЯНІ КОШИКИ. Крива має бути суцільною від 0 до 100 %, інакше її нема як
// нарисувати. Там, де замірів не було, береться середній нахил по всьому виміряному —
// але кожна точка знає, вимір це чи доведення, і на графіку це видно різним кольором.
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

    /**
     * Крива від 0 до 100 % через один відсоток.
     *
     * Точка на X відсотках — це стільки кВт·год, скільки в батареї лишається на
     * цьому відсотку, тобто сума енергії всіх кошиків під ним.
     */
    fun curve(): List<CurvePoint> {
        if (samples == 0) return emptyList()

        val fallback = averageRate()
        val points = mutableListOf(CurvePoint(0.0, 0.0, measured = rateOfBin(0) != null))
        var energy = 0.0

        for (bin in 0 until BINS) {
            val measured = rateOfBin(bin)
            energy += (measured ?: fallback) * BIN_WIDTH_PERCENT
            points += CurvePoint(
                socPercent = (bin + 1) * BIN_WIDTH_PERCENT,
                energyKwh = energy,
                measured = measured != null,
            )
        }
        return points
    }

    /** Повна ємність за кривою: виміряне плюс доведене. */
    fun fullKwh(): Double = curve().lastOrNull()?.energyKwh ?: 0.0

    fun snapshot() = LevelsSnapshot(
        sumKwh = sumKwh.copyOf(),
        sumPercent = sumPercent.copyOf(),
        samples = samples,
    )

    fun restore(snapshot: LevelsSnapshot) {
        if (snapshot.sumKwh.size != BINS || snapshot.sumPercent.size != BINS) return
        snapshot.sumKwh.copyInto(sumKwh)
        snapshot.sumPercent.copyInto(sumPercent)
        samples = snapshot.samples
    }

    fun reset() {
        sumKwh.fill(0.0)
        sumPercent.fill(0.0)
        samples = 0
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

        /** Батарея на 150 кВт·год — більше в Soul EV не влізе фізично. */
        const val MAX_RATE_KWH_PER_PERCENT = 1.5
    }
}

/** Усе, що крива пам'ятає, у вигляді, придатному для файлу. */
data class LevelsSnapshot(
    val sumKwh: DoubleArray,
    val sumPercent: DoubleArray,
    val samples: Int,
) {
    // equals/hashCode для масивів data class не робить сам, а тести їх порівнюють.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LevelsSnapshot) return false
        return samples == other.samples &&
            sumKwh.contentEquals(other.sumKwh) &&
            sumPercent.contentEquals(other.sumPercent)
    }

    override fun hashCode(): Int =
        (sumKwh.contentHashCode() * 31 + sumPercent.contentHashCode()) * 31 + samples
}
