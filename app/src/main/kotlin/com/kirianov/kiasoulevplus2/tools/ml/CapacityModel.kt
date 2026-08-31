// ====================================================================================
// МОДЕЛЬ ЄМНОСТІ (CapacityModel)
//
// Відповідає на два питання, на які панельний відсоток не відповідає.
//
// 1. Скільки кіловат-годин насправді тримає **ця** батарея. Замість того щоб ділити
//    енергію на різницю SOC (відношення двох маленьких шумних чисел, та ще й з
//    діленням), тут прямо описується накопичена крива: якщо dQ/du = c₀ + c₁u + c₂u²,
//    то за будь-який проміжок
//
//        ΔE = c₀·Δu + (c₁/2)·Δ(u²) + (c₂/3)·Δ(u³)
//
//    Це лінійно за коефіцієнтами, ділення немає взагалі, а довгі проміжки самі собою
//    важать більше за короткі — саме те, що потрібно.
//
// 2. Де у шкали справжні краї. Панель показує 0 %, коли в батареї ще лишається буфер,
//    і 100 %, коли вона ще не повна. Пряма, що зв'язує панельний SOC із точним, ці
//    буфери й виказує — саме про це попереджає docs/SOUL_EV_CAN.md: без цього
//    перерахунок кВт·год у кілометри пливе. Пряма будується лише за середньою
//    частиною шкали: на краях панель упирається в 0 і 100, і ці «полички» зіпсували б
//    нахил.
//
// «Реальний відсоток» тут — частка **енергії**, що лишилася над справжнім дном, а не
// положення стрілки на шкалі.
//
// Важливо про темп даних: точний SOC приходить приблизно раз на хвилину, тож за
// п'ять хвилин руху він зрушить на відсоток-другий — з такого кроку ємності не
// вивчити. Тому модель годується не відрізками, а **сесіями**: цілим заїздом або
// цілим заряджанням, де SOC проходить помітний шмат шкали.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import kotlin.math.abs

class CapacityModel(
    /** Крива ємності: dQ/du = c₀ + c₁u + c₂u², де u = SOC/100. */
    private val energy: OnlineRegression = OnlineRegression(
        size = 3,
        prior = doubleArrayOf(NOMINAL_CAPACITY_KWH, 0.0, 0.0),
        priorSigma = doubleArrayOf(4.0, 6.0, 6.0),
        noiseSigma = 0.5,
        forgetMs = OnlineRegression.FORGET_TWO_YEARS_MS,
    ),
    /** Панельний SOC як пряма від точного: [зсув, нахил]. Звідси й беруться буфери. */
    private val buffer: OnlineRegression = OnlineRegression(
        size = 2,
        prior = doubleArrayOf(PRIOR_BUFFER_OFFSET, PRIOR_BUFFER_SLOPE),
        priorSigma = doubleArrayOf(3.0, 0.1),
        noiseSigma = 1.0,
        forgetMs = OnlineRegression.FORGET_TWO_YEARS_MS,
    ),
) {

    val observations: Double get() = energy.effectiveSamples

    /** Точний SOC, на якому панель показує нуль: справжнє дно шкали, %. */
    val floorSocPercent: Double
        get() {
            val theta = buffer.coefficients()
            val slope = theta[1]
            if (slope <= MIN_SLOPE) return DEFAULT_FLOOR_PERCENT
            return (-theta[0] / slope).coerceIn(0.0, MAX_FLOOR_PERCENT)
        }

    /** Точний SOC, на якому панель показує сто: справжня стеля шкали, %. */
    val ceilingSocPercent: Double
        get() {
            val theta = buffer.coefficients()
            val slope = theta[1]
            if (slope <= MIN_SLOPE) return DEFAULT_CEILING_PERCENT
            return ((100.0 - theta[0]) / slope)
                .coerceIn(floorSocPercent + MIN_WINDOW_PERCENT, 100.0)
        }

    /** Уся корисна ємність від дна до стелі, кВт·год. */
    val usableCapacityKwh: Double
        get() = energyBetween(floorSocPercent, ceilingSocPercent)

    /**
     * Вивчена ємність відносно паспортної, %.
     *
     * Читати обережно. SOC у BMS Hyundai/Kia, найпевніше, нормований на **поточну**
     * ємність пакета, а не на паспортну. Якщо так, то кВт·год на відсоток із віком
     * майже не змінюється, і це число роками стоятиме близько сотні — тобто воно
     * чесно годиться для перерахунку SOC у кіловат-години (а це саме те, що треба
     * запасу ходу) і не годиться як показник зносу. Справжній SOH лежить у окремому
     * запиті 21 05; знайти його можна екраном «Експерименти».
     */
    val capacityVersusNominalPercent: Double
        get() = usableCapacityKwh / NOMINAL_CAPACITY_KWH * 100.0

    /** Відносна невизначеність ємності — входить в інтервал запасу ходу. */
    val relativeSigma: Double
        get() {
            val capacity = usableCapacityKwh
            if (capacity <= 0.0) return DEFAULT_RELATIVE_SIGMA
            val full = featuresFor(0.0, 100.0)
            val sigma = energy.predictionSigma(full)
            return (sigma / capacity).coerceIn(MIN_RELATIVE_SIGMA, DEFAULT_RELATIVE_SIGMA)
        }

    /**
     * Довчитися на цілій сесії: заїзді або заряджанні, за яке SOC пройшов помітний
     * шмат шкали. Менші проміжки відкидаються — на них шум SOC більший за сигнал.
     */
    fun learn(
        socStartPercent: Double,
        socEndPercent: Double,
        energyKwh: Double,
        atMs: Long,
    ): Boolean {
        val drop = socStartPercent - socEndPercent
        if (abs(drop) < MIN_SOC_SPAN_PERCENT) return false
        if (!energyKwh.isFinite()) return false

        // Енергія і SOC мусять рухатися в один бік: інакше це збій читання.
        if (drop * energyKwh <= 0.0) return false

        return energy.observe(
            featuresFor(socEndPercent, socStartPercent),
            energyKwh,
            weight = abs(drop),
            atMs = atMs,
        )
    }

    /**
     * Пара «панель / BMS» в один момент: із багатьох таких пар і встає пряма.
     *
     * Береться лише середина шкали. Біля країв панель упирається в 0 і 100 і стоїть
     * там, поки точний SOC іще рухається; така «поличка» завалила б нахил і зробила б
     * висновок про буфери гіршим, ніж просто здогад.
     */
    fun learnBuffer(displaySocPercent: Double?, preciseSocPercent: Double?, atMs: Long) {
        val display = displaySocPercent ?: return
        val precise = preciseSocPercent ?: return
        if (display <= BUFFER_FIT_FROM_PERCENT || display >= BUFFER_FIT_TO_PERCENT) return
        if (precise < 0.0 || precise > 100.0) return
        buffer.observe(doubleArrayOf(1.0, precise), display, weight = 1.0, atMs = atMs)
    }

    /** Скільки кВт·год важить один відсоток у цій точці шкали. */
    fun kwhPerPercentAt(socPercent: Double): Double {
        val theta = energy.coefficients()
        val u = socPercent / 100.0
        return ((theta[0] + theta[1] * u + theta[2] * u * u) / 100.0)
            .coerceAtLeast(MIN_KWH_PER_PERCENT)
    }

    /**
     * Енергія між двома точками шкали, кВт·год.
     *
     * ∫(c₀ + c₁u + c₂u²) du = c₀·Δu + c₁·Δ(u²)/2 + c₂·Δ(u³)/3
     */
    fun energyBetween(fromSocPercent: Double, toSocPercent: Double): Double {
        if (toSocPercent <= fromSocPercent) return 0.0
        val theta = energy.coefficients()
        val features = featuresFor(fromSocPercent, toSocPercent)
        var total = 0.0
        for (index in features.indices) total += theta[index] * features[index]
        // Крива, що пішла вниз, означала б батарею, яка віддає від'ємну енергію.
        return if (total.isFinite() && total > 0.0) {
            total
        } else {
            NOMINAL_CAPACITY_KWH * (toSocPercent - fromSocPercent) / 100.0
        }
    }

    /** Скільки корисної енергії лишилося над справжнім дном, кВт·год. */
    fun energyRemainingKwh(preciseSocPercent: Double): Double =
        energyBetween(floorSocPercent, preciseSocPercent.coerceAtMost(ceilingSocPercent))

    /**
     * Справжній залишок у відсотках: частка енергії над дном, а не положення стрілки.
     * Саме це число розходиться з панеллю на морозі й наприкінці шкали.
     */
    fun realPercent(preciseSocPercent: Double): Double {
        val total = usableCapacityKwh
        if (total <= 0.0) return 0.0
        return (energyRemainingKwh(preciseSocPercent) / total * 100.0).coerceIn(0.0, 100.0)
    }

    fun snapshot(): CapacitySnapshot = CapacitySnapshot(energy.snapshot(), buffer.snapshot())

    fun restore(snapshot: CapacitySnapshot): Boolean {
        val energyRestored = energy.restore(snapshot.energy)
        val bufferRestored = buffer.restore(snapshot.buffer)
        return energyRestored && bufferRestored
    }

    /** [Δu, Δ(u²)/2, Δ(u³)/3] — рівно те, на що множаться c₀, c₁, c₂. */
    private fun featuresFor(fromSocPercent: Double, toSocPercent: Double): DoubleArray {
        val from = fromSocPercent / 100.0
        val to = toSocPercent / 100.0
        return doubleArrayOf(
            to - from,
            (to * to - from * from) / 2.0,
            (to * to * to - from * from * from) / 3.0,
        )
    }

    companion object {
        /** Паспортна ємність Soul EV 27 кВт·год: точка відліку і апріорне значення. */
        const val NOMINAL_CAPACITY_KWH = 27.0

        /**
         * Менший розмах SOC не годиться: точний SOC приходить раз на ~хвилину і сам є
         * оцінкою BMS, тож на кількох відсотках шум переважує сигнал.
         */
        const val MIN_SOC_SPAN_PERCENT = 8.0

        /** Ємність відсотка не буває нульовою: інакше запас ходу став би нескінченним. */
        const val MIN_KWH_PER_PERCENT = 0.05

        /** Панель зазвичай показує нуль, коли в BMS ще лишається кілька відсотків. */
        const val PRIOR_BUFFER_OFFSET = -4.2
        const val PRIOR_BUFFER_SLOPE = 1.05

        /** Пряму буферів будуємо лише за серединою шкали, без «поличок» на краях. */
        const val BUFFER_FIT_FROM_PERCENT = 10.0
        const val BUFFER_FIT_TO_PERCENT = 95.0

        const val DEFAULT_FLOOR_PERCENT = 4.0
        const val DEFAULT_CEILING_PERCENT = 99.0
        const val MAX_FLOOR_PERCENT = 15.0
        const val MIN_WINDOW_PERCENT = 50.0
        const val MIN_SLOPE = 0.5

        const val DEFAULT_RELATIVE_SIGMA = 0.25
        const val MIN_RELATIVE_SIGMA = 0.02
    }
}

/**
 * Складає відрізки в сесію, поки SOC не пройде достатній шмат шкали.
 *
 * Саме через це модель ємності взагалі здатна вчитися: за окремий відрізок SOC
 * зрушить на відсоток, а тут набирається десяток і більше.
 */
class CapacitySession {

    private var socStart: Double? = null
    private var socLast: Double? = null
    private var energyKwh = 0.0
    private var startedAtMs = 0L
    private var charging = false

    val spanPercent: Double
        get() {
            val from = socStart ?: return 0.0
            val to = socLast ?: return 0.0
            return abs(from - to)
        }

    /**
     * Додає відрізок. Повертає готове спостереження, коли розмах SOC став достатнім,
     * і одразу починає накопичувати наступне.
     */
    fun add(segment: MlSegment): CapacityObservation? {
        val start = segment.socStartPercent
        val end = segment.socEndPercent ?: return null

        // Зміна режиму «їдемо / заряджаємось» закриває сесію: складати їх разом не можна.
        if (socStart != null && segment.charging != charging) reset()

        if (socStart == null) {
            socStart = start ?: end
            startedAtMs = segment.startedAtMs
            energyKwh = 0.0
            charging = segment.charging
        }

        energyKwh += segment.energyKwh
        socLast = end

        if (spanPercent < CapacityModel.MIN_SOC_SPAN_PERCENT) return null

        val observation = CapacityObservation(
            socStartPercent = socStart ?: return null,
            socEndPercent = end,
            energyKwh = energyKwh,
            atMs = startedAtMs,
        )
        reset()
        return observation
    }

    fun reset() {
        socStart = null
        socLast = null
        energyKwh = 0.0
        startedAtMs = 0L
        charging = false
    }
}

/** Готова пара «шмат шкали / витрачена на нього енергія». */
data class CapacityObservation(
    val socStartPercent: Double,
    val socEndPercent: Double,
    val energyKwh: Double,
    val atMs: Long,
)

/** Накопичене моделлю ємності у вигляді, придатному для файлу. */
class CapacitySnapshot(
    val energy: RegressionSnapshot,
    val buffer: RegressionSnapshot,
)
