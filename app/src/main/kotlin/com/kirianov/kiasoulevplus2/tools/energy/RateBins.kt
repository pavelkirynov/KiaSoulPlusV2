// ====================================================================================
// КОШИКИ ШКАЛИ (RateBins)
//
// Одна й та сама механіка потрібна трьом кривим одразу — енергії за лічильниками,
// енергії за струмом і кілометрам, — тому вона живе тут окремо. Різняться вони
// лише тим, ЩО кладуть у кошик; правила накопичення в усіх однакові.
//
// ЯК ЦЕ МІРЯЄТЬСЯ. Один замір — це «шкала пройшла від A % до B %, і за цей час
// набігло стільки». Замір майже ніколи не вкладається рівно в один відсоток, тож
// його розкладає по кошиках пропорційно пройденій у кожному частині.
//
// Кошик пам'ятає СУМИ — величину і пройдені відсотки, — а не готове середнє. Тоді
// повторний прохід тим самим місцем шкали усереднюється з вагою: довший прохід
// важить більше за короткий, і крива уточнюється з кожною поїздкою.
//
// НЕВИМІРЯНІ КОШИКИ ЗАПОВНЮЄ [profile]: між двома виміряними нахил переходить
// плавно, за краями продовжується нахилом найближчого виміряного. Це найпростіше
// припущення, яке не вигадує форми. Рівний нахил на порожніх місцях, який був тут
// спершу, давав видимі зломи на кожній межі острова — злом, якого в батареї немає,
// бо він походить від способу малювання.
//
// Чистий Kotlin зі своїм станом: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.energy

import kotlin.math.max
import kotlin.math.min

class RateBins(
    /** Нижча за цю швидкість — не вимір, а помилка читання. */
    private val minRate: Double,
    /** І вища за цю — так само. */
    private val maxRate: Double,
) {

    private val sumValue = DoubleArray(BINS)
    private val sumPercent = DoubleArray(BINS)

    var samples: Int = 0
        private set

    /**
     * Додає замір: шкала пройшла від [fromPercent] до [toPercent], і за цей час
     * набігло [value].
     *
     * @return чи прийнято замір. Відмова означає, що числа непослідовні або
     * нефізичні: краще не мати виміру, ніж мати вигаданий.
     */
    fun learn(fromPercent: Double, toPercent: Double, value: Double): Boolean {
        val span = fromPercent - toPercent
        if (span < MIN_SPAN_PERCENT || !span.isFinite()) return false
        if (value <= 0.0 || !value.isFinite()) return false

        val rate = value / span
        if (rate < minRate || rate > maxRate) return false

        val low = max(0.0, min(fromPercent, toPercent))
        val high = min(100.0, max(fromPercent, toPercent))
        if (high <= low) return false

        var bin = binOf(low)
        while (bin < BINS) {
            val binLow = bin * BIN_WIDTH_PERCENT
            if (binLow >= high) break

            val part = min(high, binLow + BIN_WIDTH_PERCENT) - max(low, binLow)
            if (part > 0.0) {
                sumPercent[bin] += part
                sumValue[bin] += rate * part
            }
            bin++
        }

        samples++
        return true
    }

    /** Нахил у цьому кошику; null — не міряли. */
    fun rateOf(bin: Int): Double? =
        if (sumPercent[bin] > MIN_BIN_PERCENT) sumValue[bin] / sumPercent[bin] else null

    fun rateAt(socPercent: Double): Double? = rateOf(binOf(socPercent))

    private fun binOf(socPercent: Double): Int =
        (socPercent / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BINS - 1)

    val hasMeasurements: Boolean get() = samples > 0 && sumPercent.any { it > MIN_BIN_PERCENT }

    val measuredFromPercent: Double?
        get() = sumPercent.indexOfFirst { it > MIN_BIN_PERCENT }.takeIf { it >= 0 }
            ?.let { it * BIN_WIDTH_PERCENT }

    val measuredToPercent: Double?
        get() = sumPercent.indexOfLast { it > MIN_BIN_PERCENT }.takeIf { it >= 0 }
            ?.let { (it + 1) * BIN_WIDTH_PERCENT }

    /** Яку частину шкали виміряно, у відсотках. */
    val coveredPercent: Double
        get() = sumPercent.count { it > MIN_BIN_PERCENT } * BIN_WIDTH_PERCENT

    /** Чи цей кошик зміряно насправді, а не доведено. */
    fun measured(bin: Int): Boolean = sumPercent[bin] > MIN_BIN_PERCENT

    /**
     * Нахил у КОЖНОМУ кошику шкали: виміряний там, де є заміри, доведений там, де
     * їх немає.
     *
     * [fallback] береться, коли не зміряно нічого взагалі, — тоді крива має бути
     * рівною лінією, а не порожнечею.
     */
    fun profile(fallback: Double): DoubleArray {
        val known = smooth(DoubleArray(BINS) { rateOf(it) ?: Double.NaN })
        val filled = DoubleArray(BINS)
        val measured = (0 until BINS).filter { !known[it].isNaN() }
        if (measured.isEmpty()) {
            filled.fill(fallback)
            return filled
        }

        for (bin in 0 until BINS) {
            if (!known[bin].isNaN()) {
                filled[bin] = known[bin]
                continue
            }
            val before = measured.lastOrNull { it < bin }
            val after = measured.firstOrNull { it > bin }
            filled[bin] = when {
                before != null && after != null -> {
                    val share = (bin - before).toDouble() / (after - before)
                    known[before] + share * (known[after] - known[before])
                }
                before != null -> known[before]
                else -> known[after!!]
            }
        }
        return filled
    }

    /**
     * Легке згладжування по сусідах: кошик важить удвічі більше за кожного з них.
     *
     * Крок лічильника лишає шум навіть у широкому кошику, а справжня крива шкали
     * гладка — вона задана хімією, а не випадковістю. Ваги навмисно скромні:
     * сильніше згладжування вже почало б з'їдати перегин, заради якого крива й
     * будується.
     *
     * Невиміряні кошики в згладжуванні не беруть участі — ні як джерело, ні як
     * ціль: доводити порожнє місце має протягування, а не розмазування сусіда.
     */
    private fun smooth(rates: DoubleArray): DoubleArray {
        val out = DoubleArray(BINS)
        for (bin in 0 until BINS) {
            if (rates[bin].isNaN()) {
                out[bin] = Double.NaN
                continue
            }
            var sum = rates[bin] * 2.0
            var weight = 2.0
            for (side in listOf(bin - 1, bin + 1)) {
                if (side in 0 until BINS && !rates[side].isNaN()) {
                    sum += rates[side]
                    weight += 1.0
                }
            }
            out[bin] = sum / weight
        }
        return out
    }

    val values: DoubleArray get() = sumValue.copyOf()
    val percents: DoubleArray get() = sumPercent.copyOf()

    /**
     * Піднімає кошики з файлу.
     *
     * Масиви іншої довжини приводяться до поточної: у них лежать СУМИ, тож із
     * широких кошиків вони розкладаються рівно, а з вузьких складаються. Жодного
     * заміру при цьому не втрачено — форма стає лише грубішою або дрібнішою.
     */
    fun restore(value: DoubleArray, percent: DoubleArray, samples: Int) {
        val v = resize(value) ?: return
        val p = resize(percent) ?: return
        v.copyInto(sumValue)
        p.copyInto(sumPercent)
        this.samples = samples
    }

    fun reset() {
        sumValue.fill(0.0)
        sumPercent.fill(0.0)
        samples = 0
    }

    companion object {
        /**
         * Кошики по одному відсотку.
         *
         * Ширші кошики гасили шум, але й ховали те, заради чого крива й будується:
         * шкала цього авто різко нерівна, і перегин у ній вужчий за п'ять
         * відсотків. Шум натомість гасить згладжування по сусідах.
         */
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

        /** Приводить масив із файлу до поточної кількості кошиків. */
        fun resize(values: DoubleArray): DoubleArray? = when {
            values.size == BINS -> values.copyOf()
            values.isEmpty() || BINS % values.size != 0 -> null
            else -> {
                // Ширший кошик розкладається на кілька вузьких порівну: сума та сама.
                val split = BINS / values.size
                DoubleArray(BINS) { values[it / split] / split }
            }
        }
    }
}
