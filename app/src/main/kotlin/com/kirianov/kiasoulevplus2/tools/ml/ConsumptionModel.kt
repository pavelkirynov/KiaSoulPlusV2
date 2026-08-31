// ====================================================================================
// МОДЕЛЬ ВИТРАТИ (ConsumptionModel)
//
// Вчиться передбачати **середню потужність** відрізка, а не одразу Вт·год/км.
// Це не дрібниця, а єдиний спосіб описати і трасу, і затор однією формулою:
//
//   • потужність розкладається на фізично осмислені доданки — постійний відбір,
//     опір коченню (∝ v), опір повітря (∝ v³) — і кожен лишається кінцевим;
//   • Вт·год/км на нульовій швидкості прямує в нескінченність, тож стоянка з
//     кліматом зіпсувала б таку регресію, хоча саме вона найкраще показує відбір;
//   • у формі «на кілометр» доданок відбору виглядав би як θ₀/v — гіпербола.
//     Регресія Вт·год/км на [1, v, v²] цього доданка не має взагалі й міське
//     їздіння описати не здатна в принципі. Тому — потужність.
//
// Перехід назад тривіальний і робиться лише для показу: Вт·год/км = P·1000/v.
//
// Швидкість у метрах за секунду і поділена на 10: усі ознаки виходять близько
// одиниці, і матриця лишається добре обумовленою навіть із кубом. Масштаби —
// **сталі числа**, а не ковзне середнє: інакше збережені достатні статистики
// означали б різне до і після перезапуску, і журнал перестав би відтворюватися.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import kotlin.math.max

/** Умови, за яких питаємо модель про витрату. Швидкості в метрах за секунду. */
data class DriveConditions(
    val meanSpeedMps: Double,
    /**
     * Середнє від куба швидкості, а не куб середньої: опір повітря росте як v³,
     * тому рвана їзда коштує дорожче за рівну з тією самою середньою.
     */
    val meanSpeedCubedMps: Double,
    val speedVarianceMps: Double = 0.0,
    val ambientTempC: Double? = null,
    val batteryTempC: Double? = null,
) {
    val meanSpeedKmh: Double get() = meanSpeedMps * KMH_PER_MPS

    companion object {
        private const val KMH_PER_MPS = 3.6

        /** Рівний хід зі сталою швидкістю: для сценаріїв «а якщо їхати 90». */
        fun steady(speedKmh: Double, ambientTempC: Double? = null, batteryTempC: Double? = null): DriveConditions {
            val mps = speedKmh / KMH_PER_MPS
            return DriveConditions(
                meanSpeedMps = mps,
                meanSpeedCubedMps = mps * mps * mps,
                speedVarianceMps = 0.0,
                ambientTempC = ambientTempC,
                batteryTempC = batteryTempC,
            )
        }

        fun of(segment: MlSegment) = DriveConditions(
            meanSpeedMps = segment.meanSpeedMps,
            meanSpeedCubedMps = segment.meanSpeedCubedMps,
            speedVarianceMps = segment.speedVarianceMps,
            ambientTempC = segment.ambientTempC,
            batteryTempC = segment.batteryTempC,
        )
    }
}

/**
 * Набір ознак і фізика, з якої модель стартує.
 *
 * Апріорні середні пораховані для Soul EV 27 кВт·год: маса ≈ 1570 кг з водієм,
 * Cx·A ≈ 0.95 м², опір коченню ≈ 0.013, ККД тракту ≈ 0.85. Це не калібрування
 * і не «підігнані коефіцієнти» — це розумна відповідь на перший день, поки даних
 * нема. Перевірка: 60 км/год дає 7.8 кВт (130 Вт·год/км), 90 км/год — 17.2 кВт
 * (191 Вт·год/км). Для цього авто обидва числа правильні.
 *
 * Апріорна σ — це справжня непевність, а не умовна вага: широка σ означає
 * «здогад, переконуйте даними», вузька — «майже впевнені». Ознака, якої в даних
 * ще не траплялося (мороз, поки надворі літо), так і лишається при фізиці.
 */
internal object ConsumptionFeatures {

    const val SIZE = 7

    /** Опорна швидкість: усі ознаки рахуються від v/10 м/с. */
    private const val SPEED_REFERENCE_MPS = 10.0

    private const val TEMPERATURE_SCALE = 10.0

    /** Нижче цієї температури за бортом майже напевно працює обігрів. */
    private const val HEATING_BELOW_C = 15.0

    /** Вище цієї — кондиціонер. */
    private const val COOLING_ABOVE_C = 25.0

    /** Нижче цієї температури батареї помітно ростуть внутрішні втрати. */
    private const val COLD_BATTERY_BELOW_C = 10.0

    /** Поки термометра не чули, вважаємо погоду м'якою: жодного доданка не додається. */
    private const val ASSUMED_AMBIENT_C = 20.0
    private const val ASSUMED_BATTERY_C = 20.0

    private const val VARIANCE_SCALE = 100.0

    val NAMES = listOf(
        "постійний відбір",
        "опір коченню",
        "опір повітря",
        "обігрів",
        "холодна батарея",
        "кондиціонер",
        "рвана їзда",
    )

    val PRIOR = doubleArrayOf(
        0.70, // постійний відбір: бортова електроніка й DC-DC, кВт
        2.36, // опір коченню: 10·m·g·Crr/(1000·ККД), кВт на 10 м/с
        0.68, // опір повітря: 1000·0.5·ρ·CxA/(1000·ККД), кВт на (10 м/с)³
        1.20, // обігрів на 10 °C нижче порога, кВт
        0.05, // холодна батарея, кВт
        0.60, // кондиціонер на 10 °C вище порога, кВт
        0.00, // надбавка за рвану їзду: фізика її не передбачає, хай знайдуть дані
    )

    /**
     * Обігрів має широку σ навмисно: стану клімату застосунок не бачить, і температура
     * за бортом — лише його замінник. Пічка на цьому авто тягне кілька кіловат, тобто
     * більше за решту доданків разом, тож нехай дані розпоряджаються цим коефіцієнтом
     * вільно.
     */
    val PRIOR_SIGMA = doubleArrayOf(0.40, 0.60, 0.20, 1.00, 0.10, 0.60, 1.00)

    /** Типовий розкид залишків, кВт: у стількох спостереженнях цінується апріорі. */
    const val NOISE_SIGMA = 1.5

    fun of(conditions: DriveConditions): DoubleArray {
        val ambient = conditions.ambientTempC ?: ASSUMED_AMBIENT_C
        val battery = conditions.batteryTempC ?: ASSUMED_BATTERY_C
        val speed = conditions.meanSpeedMps / SPEED_REFERENCE_MPS
        val cube = conditions.meanSpeedCubedMps /
            (SPEED_REFERENCE_MPS * SPEED_REFERENCE_MPS * SPEED_REFERENCE_MPS)
        return doubleArrayOf(
            1.0,
            speed,
            cube,
            max(0.0, HEATING_BELOW_C - ambient) / TEMPERATURE_SCALE,
            max(0.0, COLD_BATTERY_BELOW_C - battery) / TEMPERATURE_SCALE,
            max(0.0, ambient - COOLING_ABOVE_C) / TEMPERATURE_SCALE,
            conditions.speedVarianceMps / VARIANCE_SCALE,
        )
    }

    fun of(segment: MlSegment) = of(DriveConditions.of(segment))
}

/**
 * Скільки модель уже знає з даних, а не з фізики: по одному числу від 0 до 1 на
 * кожен коефіцієнт. Це і є чесна відповідь на «чи вже навчилася» — куди краща за
 * кількість відрізків, бо показує ще й **чого саме** модель не бачила: узимку
 * готовність морозного доданка так і лишиться низькою, поки не настане мороз.
 */
data class ModelReadiness(val byFeature: List<Pair<String, Double>>) {
    /** Готовність трьох головних доданків: відбір, кочення, повітря. */
    val core: Double get() = byFeature.take(3).minOfOrNull { it.second } ?: 0.0
}

class ConsumptionModel(
    private val regression: OnlineRegression = OnlineRegression(
        size = ConsumptionFeatures.SIZE,
        prior = ConsumptionFeatures.PRIOR,
        priorSigma = ConsumptionFeatures.PRIOR_SIGMA,
        noiseSigma = ConsumptionFeatures.NOISE_SIGMA,
        forgetMs = OnlineRegression.FORGET_YEAR_MS,
    ),
    /** Модель власного шуму: звідки беруться ваги і чесна ширина інтервалу. */
    val noise: NoiseModel = NoiseModel(),
) {

    val segmentsLearned: Double get() = regression.effectiveSamples

    /** Вивчений постійний відбір, кВт: скільки авто їсть, просто стоячи увімкненим. */
    val auxPowerKw: Double get() = regression.coefficients()[0]

    val readiness: ModelReadiness
        get() = ModelReadiness(
            ConsumptionFeatures.NAMES.mapIndexed { index, name -> name to regression.readiness(index) },
        )

    /**
     * Помилка передбачення в Вт·год/км на типовій швидкості — число для екрана.
     * Модель питають до того, як показати їй відповідь, тож це чесна оцінка.
     */
    fun meanAbsoluteErrorWhPerKm(referenceSpeedKmh: Double = REFERENCE_SPEED_KMH): Double =
        regression.meanAbsoluteResidual * 1000.0 / referenceSpeedKmh

    /**
     * Довчитися на відрізку.
     *
     * Заряджання сюди не годиться — там нема руху. Відрізок із надто великою
     * часткою рекуперації теж відкидається: це майже напевно затяжний спуск,
     * а висоти застосунок не бачить, тож із такого відрізка модель вивчила б,
     * що машина їздить майже задарма.
     *
     * Повертає передбачення, зроблене **до** навчання, або null, якщо відрізок
     * не взято. Саме ця пара «передбачили / побачили» і є чесною оцінкою помилки.
     */
    fun learn(segment: MlSegment): Double? {
        if (segment.charging) return null
        if (segment.durationMs < MIN_SEGMENT_MS) return null
        if (segment.regenFraction > MAX_REGEN_FRACTION) return null

        val power = segment.averagePowerKw ?: return null
        if (!power.isFinite() || power > MAX_PLAUSIBLE_POWER_KW || power < MIN_PLAUSIBLE_POWER_KW) return null

        val features = ConsumptionFeatures.of(segment)
        val predicted = regression.predict(features)

        val weight = noise.weightFor(segment.meanSpeedMps, segment.distanceMeters)
        val accepted = regression.observe(features, power, weight, segment.startedAtMs)
        if (!accepted) return null

        // Шум учиться на тому самому залишку, тільки після того, як його побачили.
        noise.observe(power - predicted, segment.meanSpeedMps, segment.distanceMeters, segment.startedAtMs)
        return predicted
    }

    fun predictPowerKw(conditions: DriveConditions): Double =
        regression.predict(ConsumptionFeatures.of(conditions))

    /**
     * Витрата, Вт·год/км.
     *
     * Ділення на швидкість обмежене знизу: на околиці нуля витрата на кілометр
     * справді прямує в нескінченність, і показувати це числом сенсу немає.
     */
    fun predictWhPerKm(conditions: DriveConditions): Double {
        val speed = conditions.meanSpeedKmh.coerceAtLeast(MIN_SPEED_KMH_FOR_WH_PER_KM)
        return predictPowerKw(conditions) * 1000.0 / speed
    }

    /**
     * Власна невизначеність моделі в цій точці, кВт. Це лише параметрична частина:
     * з часом вона спадає майже до нуля, тому сама по собі чесним інтервалом бути
     * не може. Ширину для екрана рахує `PredictionQuality` за фактичними промахами.
     */
    fun predictionSigmaKw(conditions: DriveConditions, distanceMeters: Double): Double {
        val features = ConsumptionFeatures.of(conditions)
        val parameter = regression.predictionSigma(features)
        val unmodelled = noise.sigmaFor(conditions.meanSpeedMps, distanceMeters)
        return kotlin.math.sqrt(parameter * parameter + unmodelled * unmodelled)
    }

    fun snapshot(): ConsumptionSnapshot = ConsumptionSnapshot(regression.snapshot(), noise.snapshot())

    fun restore(snapshot: ConsumptionSnapshot): Boolean {
        val restored = regression.restore(snapshot.regression)
        noise.restore(snapshot.noise)
        return restored
    }

    companion object {
        /** Коротший відрізок не дає ані пробігу, ані усталеного струму. */
        const val MIN_SEGMENT_MS = 60_000L

        /** Швидкість, на якій помилка моделі перекладається у Вт·год/км для екрана. */
        const val REFERENCE_SPEED_KMH = 60.0

        /** Нижче цієї швидкості Вт·год/км як величина втрачає сенс. */
        const val MIN_SPEED_KMH_FOR_WH_PER_KM = 5.0

        /** Стільки рекуперації відносно тяги буває лише на затяжному спуску. */
        const val MAX_REGEN_FRACTION = 0.5

        /** 27-кіловатний Soul не віддає стільки в середньому за п'ять хвилин. */
        const val MAX_PLAUSIBLE_POWER_KW = 90.0

        /** Стійка рекуперація в середньому за відрізок — це спуск, а не помилка. */
        const val MIN_PLAUSIBLE_POWER_KW = -30.0
    }
}

/** Накопичене моделлю витрати у вигляді, придатному для файлу. */
class ConsumptionSnapshot(
    val regression: RegressionSnapshot,
    val noise: RegressionSnapshot,
)
