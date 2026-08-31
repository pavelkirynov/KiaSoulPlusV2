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
    /**
     * Крива ємності по **корзинах** шкали: скільки кіловат-годин лежить у кожних
     * десяти відсотках SOC. Разом вони й складають повну ємність.
     *
     * Чому не поліном. Комірки в пакеті літій-залізо-фосфатні, а BMS читає їх
     * таблицею напруг від рідних нікелевих. У LFP полиця напруги майже пласка, тож
     * у середині шкали BMS «не помічає» витраченого: на малий ΔSOC там припадає
     * багато енергії, а на колінах навпаки. Виходить крива з вираженим горбом і
     * різкими краями.
     *
     * Поліном такий профіль описує погано, і найгірше — **поза** тим діапазоном, де
     * були дані: на перевірці правдоподібною кривою LFP куб дав −79 % на 95 % шкали
     * просто тому, що вище 88 % сесій не траплялося, і криву понесло. Корзини так не
     * вміють: кожна визначається лише тими сесіями, які її перетинають, а корзина
     * без даних чесно лишається при апріорному значенні.
     *
     * Лінійність за параметрами зберігається повністю: енергія за проміжок — це сума
     * корзин, узятих у частках покриття. Тому тут працює та сама математика.
     */
    private val energy: OnlineRegression = OnlineRegression(
        size = BINS,
        prior = DoubleArray(BINS) { NOMINAL_CAPACITY_KWH / BINS },
        priorSigma = DoubleArray(BINS) { PRIOR_BIN_SIGMA_KWH },
        noiseSigma = 0.5,
        forgetMs = OnlineRegression.FORGET_TWO_YEARS_MS,
        // Сусідні корзини — шматки однієї кривої, а не незалежні числа. Поки сесій
        // мало, вони діляться інформацією: інакше корзина, яку жодна сесія не
        // перетнула окремо, так і лишалася б при апріорі, а сусідня поруч — ні.
        penalty = OnlineRegression.smoothnessPenalty(BINS, SMOOTHNESS),
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
     * Вивчена ємність відносно очікуваної від перепаковки, %. Сто означає «пакет
     * саме такий, як думали»; менше — комірки віддають менше, ніж заявлено.
     */
    val capacityVersusNominalPercent: Double
        get() = usableCapacityKwh / NOMINAL_CAPACITY_KWH * 100.0

    /**
     * У скільки разів пакет більший за рідний, з паспорта якого BMS досі рахує
     * відсотки. Це і є множник, на який systematically помиляється приладова панель:
     * коли вона показує «десять відсотків», енергії лишилося приблизно вдвічі більше
     * за те, на що вона розрахована.
     */
    val timesLargerThanOriginal: Double
        get() = usableCapacityKwh / Vehicle.ORIGINAL_CAPACITY_KWH

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

        // Вага одна на сесію: довший проміжок і так важить більше, бо накриває
        // більше корзин і накриває їх повніше.
        return energy.observe(
            featuresFor(socEndPercent, socStartPercent),
            energyKwh,
            weight = 1.0,
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
        val bin = binOf(socPercent)
        return (energy.coefficients()[bin] / BIN_WIDTH_PERCENT).coerceAtLeast(MIN_KWH_PER_PERCENT)
    }

    private fun binOf(socPercent: Double): Int =
        (socPercent / BIN_WIDTH_PERCENT).toInt().coerceIn(0, BINS - 1)

    /**
     * Енергія між двома точками шкали, кВт·год: сума корзин, узятих у частках,
     * якими проміжок їх накриває.
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

    /**
     * Яку частку кожної корзини накриває проміжок: від 0 до 1. Саме на ці частки
     * і множаться енергії корзин.
     *
     * Знак важливий. Під час заряджання SOC росте, тобто кінець вищий за початок, —
     * і тоді частки беруться з мінусом. Разом із від'ємною енергією заряду це дає
     * той самий доданок, що й розряд: заряд і рух кажуть моделі одне й те саме,
     * просто з різних боків. Без знака заряджання не вчило б її взагалі.
     */
    private fun featuresFor(fromSocPercent: Double, toSocPercent: Double): DoubleArray {
        val from = fromSocPercent.coerceIn(0.0, 100.0)
        val to = toSocPercent.coerceIn(0.0, 100.0)
        val lower = minOf(from, to)
        val upper = maxOf(from, to)
        val sign = if (to >= from) 1.0 else -1.0
        return DoubleArray(BINS) { bin ->
            val binFrom = bin * BIN_WIDTH_PERCENT
            val binTo = binFrom + BIN_WIDTH_PERCENT
            val overlap = minOf(upper, binTo) - maxOf(lower, binFrom)
            sign * (overlap / BIN_WIDTH_PERCENT).coerceIn(0.0, 1.0)
        }
    }

    companion object {
        /** Апріорна корисна ємність перепакованого пакета. Див. `Vehicle`. */
        const val NOMINAL_CAPACITY_KWH = Vehicle.USABLE_CAPACITY_KWH

        /** На скільки шматків ділиться шкала. Десять відсотків на корзину. */
        const val BINS = 10

        const val BIN_WIDTH_PERCENT = 100.0 / BINS

        /**
         * Наскільки непевна корзина спочатку. Приблизно третина від апріорного
         * значення: досить широко, щоб дані швидко перемогли, і досить вузько, щоб
         * корзина без жодної сесії не зіпсувала загальну суму.
         */
        const val PRIOR_BIN_SIGMA_KWH = 1.5

        /**
         * Наскільки міцно триматися гладкості. Приблизно вага однієї сесії: досить,
         * щоб на першому десятку сесій крива не розсипалася на окремі стовпчики, і
         * замало, щоб через рік завадити їй показати справжній горб.
         */
        const val SMOOTHNESS = 1.0

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
        /**
         * Межі навмисно широкі. На перепакованій батареї BMS рахує шкалу за паспортом
         * рідного пакета, тож її нуль і сотня можуть стояти геть не там, де в нових
         * комірок. Вузький затиск тут воював би з тим, що модель щойно виміряла.
         */
        const val MAX_FLOOR_PERCENT = 25.0
        const val MIN_WINDOW_PERCENT = 40.0
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
