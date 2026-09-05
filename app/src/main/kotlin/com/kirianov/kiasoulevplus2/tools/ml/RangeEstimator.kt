// ====================================================================================
// ЗАПАС ХОДУ (RangeEstimator)
//
// Складає докупи дві вивчені моделі:
//
//     запас = (енергія над справжнім дном) / (передбачена витрата)
//
// Обидва множники — вивчені, а не задані. Саме тому це не той самий розрахунок на
// ручних коефіцієнтах, тільки з іншими числами: коефіцієнти тут беруться з того,
// як їздить конкретне авто, і змінюються разом із ним.
//
// Ширина інтервалу — з фактичних промахів, поки їх набралося; доти — з апріорної
// оцінки, помітно розширеної. Число без інтервалу тут не показується взагалі:
// прогноз запасу ходу без «наскільки я в цьому впевнений» і є та сама «вгадайка»,
// від якої всі втомилися.
//
// Чисті функції: жодного стану, жодного Android.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlConfidence
import com.kirianov.kiasoulevplus2.Data.MlPrediction
import com.kirianov.kiasoulevplus2.Data.MlSegment
import com.kirianov.kiasoulevplus2.Data.PredictionBasis
import com.kirianov.kiasoulevplus2.Data.RangeScenario
import kotlin.math.sqrt

object RangeEstimator {

    /** Швидкості, для яких показуємо «а якщо їхати рівно так». */
    val SCENARIO_SPEEDS_KMH = listOf(40.0, 60.0, 90.0, 110.0)

    fun predict(
        consumption: ConsumptionModel,
        capacity: CapacityModel,
        quality: PredictionQuality,
        preciseSocPercent: Double,
        recent: DriveConditions,
        basis: PredictionBasis = PredictionBasis(),
        /**
         * Енергія, що лишилася, за ВИМІРЯНОЮ кривою ємності, кВт·год.
         *
         * Коли вона є, вона й береться: крива знає, як ємність РОЗПОДІЛЕНА по
         * шкалі, а шкала цього авто різко нерівна — відсоток угорі коштує
         * близько кілометра, у кінці від п'яти до десяти. Сама повна ємність у
         * криву приходить окремо: аксіомою з відомого пакета, а далі виміром із
         * зарядки з низьких відсотків.
         */
        curveEnergyKwh: Double? = null,
        /**
         * Чи зміряна повна ємність зарядкою з низьких відсотків, чи вона поки
         * аксіома. Крива дає розподіл ємності по шкалі навіть на аксіомі, тож
         * «взяли з кривої» і «зміряли» — різні речі, і плутати їх не можна.
         */
        curveTotalMeasured: Boolean = false,
    ): MlPrediction? {
        if (preciseSocPercent < 0.0) return null

        val measuredEnergy = curveEnergyKwh?.takeIf { it.isFinite() && it > 0.0 }
        val energyKwh = measuredEnergy ?: capacity.energyRemainingKwh(preciseSocPercent)
        if (energyKwh <= 0.0) return null

        val whPerKm = consumption.predictWhPerKm(recent)
        if (!whPerKm.isFinite() || whPerKm < MIN_WH_PER_KM || whPerKm > MAX_WH_PER_KM) return null

        val rangeKm = energyKwh * 1000.0 / whPerKm
        if (!rangeKm.isFinite() || rangeKm <= 0.0) return null

        val measured = quality.rangeBounds(rangeKm)
        val bounds = measured ?: parametricBounds(consumption, capacity, recent, rangeKm)

        return MlPrediction(
            basis = basis,
            rangeKm = rangeKm,
            rangeFromKm = bounds.first.coerceAtLeast(0.0),
            rangeToKm = bounds.second,
            realPercent = capacity.realPercent(preciseSocPercent),
            usableEnergyRemainingKwh = energyKwh,
            capacityMeasured = measuredEnergy != null && curveTotalMeasured,
            whPerKm = whPerKm,
            measuredBand = measured != null,
            scenarios = SCENARIO_SPEEDS_KMH.map { speed ->
                // Клімат їде разом з водієм: якщо пічка працює зараз, вона працюватиме
                // і на дев'яноста. Сценарій змінює швидкість, а не погоду в салоні.
                val steady = DriveConditions.steady(
                    speed,
                    recent.ambientTempC,
                    recent.batteryTempC,
                    recent.climateShare,
                )
                val scenarioWhPerKm = consumption.predictWhPerKm(steady)
                RangeScenario(
                    speedKmh = speed,
                    rangeKm = if (scenarioWhPerKm > 0.0) energyKwh * 1000.0 / scenarioWhPerKm else 0.0,
                    whPerKm = scenarioWhPerKm,
                )
            },
        )
    }

    /**
     * Запасний інтервал, поки промахів на блоках ще замало.
     *
     * Береться власна невизначеність моделі плюс невизначеність ємності, і все це
     * навмисно розширюється: параметрична оцінка систематично оптимістична, бо не
     * знає про те, чого модель не бачить узагалі.
     */
    private fun parametricBounds(
        consumption: ConsumptionModel,
        capacity: CapacityModel,
        recent: DriveConditions,
        rangeKm: Double,
    ): Pair<Double, Double> {
        val power = consumption.predictPowerKw(recent)
        val sigmaKw = consumption.predictionSigmaKw(recent, TYPICAL_SEGMENT_METERS)
        val powerRelative = if (power > 0.0) (sigmaKw / power) else DEFAULT_RELATIVE
        val capacityRelative = capacity.relativeSigma

        val relative = (
            sqrt(powerRelative * powerRelative + capacityRelative * capacityRelative) * COLD_START_INFLATION
            ).coerceIn(MIN_RELATIVE, MAX_RELATIVE)

        return (rangeKm * (1.0 - relative)) to (rangeKm * (1.0 + relative))
    }

    /**
     * Як їхали останнім часом. Саме ці умови, а не якийсь «типовий цикл», підставляються
     * в модель: питання водія — «скільки я проїду, якщо їхатиму як зараз».
     *
     * Зважування за пробігом, а не за кількістю відрізків: п'ятихвилинна стоянка не
     * має важити стільки ж, скільки десять кілометрів траси.
     */
    /**
     * Опис того, на чому побудований прогноз: скільки відрізків, скільки часу РУХУ
     * вони покривають і з якою середньою швидкістю. Стоянка у час руху не входить.
     */
    fun basisOf(segments: List<MlSegment>, climateShare: Double?, climateLive: Boolean): PredictionBasis {
        val moving = segments.filter { !it.charging && it.distanceKm > 0.0 }
        if (moving.isEmpty()) return PredictionBasis(climateShare = climateShare, climateLive = climateLive)

        val movingMs = moving.sumOf { it.durationMs }
        val distanceKm = moving.sumOf { it.distanceKm }
        val hours = movingMs / MS_PER_HOUR

        return PredictionBasis(
            segments = moving.size,
            movingMs = movingMs,
            meanSpeedKmh = if (hours > 0.0) distanceKm / hours else 0.0,
            climateShare = climateShare,
            climateLive = climateLive,
        )
    }

    fun recentConditions(segments: List<MlSegment>, fallbackSpeedKmh: Double = 50.0): DriveConditions {
        val moving = segments.filter { !it.charging && it.distanceKm > 0.0 }
        if (moving.isEmpty()) return DriveConditions.steady(fallbackSpeedKmh)

        var weight = 0.0
        var speed = 0.0
        var cube = 0.0
        var variance = 0.0
        var ambient = 0.0
        var ambientWeight = 0.0
        var battery = 0.0
        var batteryWeight = 0.0
        var climate = 0.0
        var climateWeight = 0.0

        moving.forEach { segment ->
            val w = segment.distanceKm
            weight += w
            speed += segment.meanSpeedMps * w
            cube += segment.meanSpeedCubedMps * w
            variance += segment.speedVarianceMps * w
            segment.ambientTempC?.let {
                ambient += it * w
                ambientWeight += w
            }
            segment.batteryTempC?.let {
                battery += it * w
                batteryWeight += w
            }
            segment.climateShare?.let {
                climate += it * w
                climateWeight += w
            }
        }

        if (weight <= 0.0) return DriveConditions.steady(fallbackSpeedKmh)

        return DriveConditions(
            meanSpeedMps = speed / weight,
            meanSpeedCubedMps = cube / weight,
            speedVarianceMps = variance / weight,
            ambientTempC = if (ambientWeight > 0.0) ambient / ambientWeight else null,
            batteryTempC = if (batteryWeight > 0.0) battery / batteryWeight else null,
            climateShare = if (climateWeight > 0.0) climate / climateWeight else null,
        )
    }

    /**
     * Наскільки вже можна вірити числу.
     *
     * Рахується не за кількістю відрізків, а за двома речами, які справді вирішують:
     * чи вивчені з даних три головні доданки (відбір, кочення, повітря) і чи вузький
     * вийшов інтервал. Сто відрізків самої лише траси не роблять модель готовою:
     * постійний відбір із них не виділиться, і чесна готовність це покаже.
     */
    fun confidenceOf(readiness: ModelReadiness, prediction: MlPrediction?): MlConfidence {
        if (prediction == null) return MlConfidence.None
        val width = relativeWidth(prediction)
        return when {
            readiness.core >= GOOD_READINESS && width <= GOOD_WIDTH -> MlConfidence.Good
            readiness.core >= FAIR_READINESS && width <= FAIR_WIDTH -> MlConfidence.Fair
            readiness.core > 0.0 -> MlConfidence.Learning
            else -> MlConfidence.None
        }
    }

    private fun relativeWidth(prediction: MlPrediction): Double {
        if (prediction.rangeKm <= 0.0) return Double.MAX_VALUE
        return (prediction.rangeToKm - prediction.rangeFromKm) / 2.0 / prediction.rangeKm
    }

    /** Витрата поза цими межами означає збій даних, а не незвичну манеру їзди. */
    private const val MIN_WH_PER_KM = 60.0
    private const val MAX_WH_PER_KM = 600.0

    /** Типовий відрізок, на якому оцінюється невизначеність рельєфу. */
    private const val TYPICAL_SEGMENT_METERS = 3_000.0

    private const val COLD_START_INFLATION = 1.5
    private const val DEFAULT_RELATIVE = 0.3
    private const val MIN_RELATIVE = 0.05
    private const val MAX_RELATIVE = 0.5

    private const val GOOD_READINESS = 0.8
    private const val GOOD_WIDTH = 0.10
    private const val FAIR_READINESS = 0.5
    private const val FAIR_WIDTH = 0.20
    private const val MS_PER_HOUR = 3_600_000.0

}
