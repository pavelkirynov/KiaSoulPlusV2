// ====================================================================================
// ЧЕСНІСТЬ ПРОГНОЗУ (NoiseModel, PredictionQuality)
//
// Дві речі, без яких «± стільки-то кілометрів» була б прикрасою.
//
// NoiseModel відповідає на питання про рельєф. Висоти застосунок не бачить узагалі:
// ні GPS, ні висотоміра. Підйом на двісті метрів — це близько кіловат-години, тобто
// сім кілометрів ходу, більше за будь-який температурний доданок. Моделювати пагорби
// нема з чого, вдавати, що їх немає, — нечесно, а «повільна поправка» була б просто
// хибною: на дорозі туди й назад вона вивчила б підйом і подвоїла помилку на спуску.
//
// Лишається єдиний чесний шлях: **виміряти, скільки шуму пагорби вносять**. Набір
// висоти за відстань поводиться як випадкове блукання, тож дисперсія росте як D, а
// дисперсія середньої потужності — як v̄²/D. Одна регресія квадратів промахів на
// [1, v̄²/D] — і в нас є число, яке саме показує, наскільки горбиста місцевість у
// цього водія. Воно ж дає ваги: короткі швидкі відрізки важать менше за довгі.
//
// PredictionQuality відповідає на питання про ширину інтервалу. Параметрична
// невизначеність із матриці для цього не годиться: вона спадає майже до нуля, поки
// справжня помилка лишається на своїх десяти відсотках. Тому ширина береться з
// фактичних промахів на блоках по десять кілометрів — і додатково підтягується,
// поки частка влучань не збіжиться до обіцяних вісімдесяти відсотків.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import kotlin.math.sqrt

/**
 * Дисперсія середньої потужності відрізка: Var(P̄) = σ₀² + κ²·v̄²/D.
 *
 * σ₀² — власний шум вимірювання, κ² — внесок невидимого рельєфу.
 */
class NoiseModel(
    private val regression: OnlineRegression = OnlineRegression(
        size = 2,
        prior = doubleArrayOf(PRIOR_BASE_VARIANCE, PRIOR_GRADE_VARIANCE),
        priorSigma = doubleArrayOf(1.0, 40.0),
        // Відгук тут — квадрат промаху, його власний розкид того ж порядку.
        noiseSigma = 2.0,
        forgetMs = OnlineRegression.FORGET_YEAR_MS,
    ),
) {

    fun observe(residualKw: Double, meanSpeedMps: Double, distanceMeters: Double, atMs: Long): Boolean {
        if (!residualKw.isFinite()) return false
        return regression.observe(
            featuresOf(meanSpeedMps, distanceMeters),
            residualKw * residualKw,
            weight = 1.0,
            atMs = atMs,
        )
    }

    fun varianceFor(meanSpeedMps: Double, distanceMeters: Double): Double =
        regression.predict(featuresOf(meanSpeedMps, distanceMeters)).coerceAtLeast(MIN_VARIANCE)

    fun sigmaFor(meanSpeedMps: Double, distanceMeters: Double): Double =
        sqrt(varianceFor(meanSpeedMps, distanceMeters))

    /** Вага відрізка у навчанні: обернена до його ж очікуваної дисперсії. */
    fun weightFor(meanSpeedMps: Double, distanceMeters: Double): Double =
        1.0 / varianceFor(meanSpeedMps, distanceMeters)

    /** Наскільки горбисто їздить цей водій, метри набору висоти на корінь із кілометра. */
    val terrainRoughness: Double
        get() {
            val kappaSquare = regression.coefficients()[1].coerceAtLeast(0.0)
            return sqrt(kappaSquare) / METRES_PER_KWH_CLIMB / SECONDS_PER_HOUR * METRES_PER_KM
        }

    fun snapshot(): RegressionSnapshot = regression.snapshot()

    fun restore(snapshot: RegressionSnapshot): Boolean = regression.restore(snapshot)

    private fun featuresOf(meanSpeedMps: Double, distanceMeters: Double): DoubleArray {
        // Стоячи на місці, машина нікуди не піднімається: рельєф не додає шуму.
        val grade = if (distanceMeters > 0.0) meanSpeedMps * meanSpeedMps / distanceMeters else 0.0
        return doubleArrayOf(1.0, grade)
    }

    companion object {
        /** Власний шум вимірювання, кВт². */
        const val PRIOR_BASE_VARIANCE = 1.0

        /**
         * Внесок рельєфу. Прикидка для помірно горбистої місцевості: 200 м набору —
         * це ≈1 кВт·год, на трьох кілометрах за п'ять хвилин виходить близько кіловата
         * невизначеності, тобто κ² ≈ 32 у одиницях (м/с)²/м.
         */
        const val PRIOR_GRADE_VARIANCE = 32.0

        const val MIN_VARIANCE = 0.05

        private const val METRES_PER_KWH_CLIMB = 5.0e-3
        private const val SECONDS_PER_HOUR = 3600.0
        private const val METRES_PER_KM = 1000.0
    }
}

/**
 * Ширина інтервалу за фактичними промахами.
 *
 * Порівнюються не окремі відрізки, а блоки по десять кілометрів: людині цікавий
 * запас ходу на десятки кілометрів, а не на п'ять хвилин, і на довшому плечі
 * випадковий шум усереднюється — інтервал не має лякати тим, що вже скоротилося.
 */
class PredictionQuality {

    private val ratios = ArrayDeque<Double>()

    private var blockDistanceKm = 0.0
    private var blockObservedKwh = 0.0
    private var blockPredictedKwh = 0.0

    private var coverageMultiplier = 1.0
    private var blocksSeen = 0

    val blocks: Int get() = blocksSeen

    /** Чи набралося блоків, щоб довіряти саме виміряній ширині, а не апріорній. */
    val isCalibrated: Boolean get() = ratios.size >= MIN_BLOCKS_FOR_BAND

    /**
     * Додає закритий відрізок разом із тим, що для нього передбачали.
     * Повертає відношення «побачили / передбачили», коли закрився черговий блок.
     */
    fun observe(segment: MlSegment, predictedPowerKw: Double): Double? {
        if (segment.charging || segment.distanceKm <= 0.0) return null
        val hours = segment.durationMs / MS_PER_HOUR
        if (hours <= 0.0) return null

        blockDistanceKm += segment.distanceKm
        blockObservedKwh += segment.energyKwh
        blockPredictedKwh += predictedPowerKw * hours

        if (blockDistanceKm < BLOCK_KM) return null

        val ratio = if (blockPredictedKwh > MIN_BLOCK_KWH) blockObservedKwh / blockPredictedKwh else null
        blockDistanceKm = 0.0
        blockObservedKwh = 0.0
        blockPredictedKwh = 0.0

        if (ratio == null || !ratio.isFinite() || ratio <= 0.0) return null

        // Чи влучив би цей блок у той інтервал, який ми показували ДО нього.
        updateCoverage(ratio)

        ratios.addLast(ratio)
        while (ratios.size > MAX_BLOCKS) ratios.removeFirst()
        blocksSeen++
        return ratio
    }

    /**
     * Межі відношення «справжня витрата / передбачена» на рівні 80 %.
     * Асиметричні навмисно: промахнутися в бік «з'їло більше» можна значно сильніше.
     */
    fun ratioBounds(): Pair<Double, Double>? {
        if (!isCalibrated) return null
        val sorted = ratios.sorted()
        val low = quantile(sorted, LOW_QUANTILE)
        val high = quantile(sorted, HIGH_QUANTILE)
        val center = 1.0
        return (center - (center - low) * coverageMultiplier) to
            (center + (high - center) * coverageMultiplier)
    }

    /**
     * Інтервал запасу ходу. Запас обернено пропорційний витраті, тому більшій
     * витраті відповідає менший пробіг — межі міняються місцями.
     */
    fun rangeBounds(rangeKm: Double): Pair<Double, Double>? {
        val (low, high) = ratioBounds() ?: return null
        if (low <= 0.0 || high <= 0.0) return null
        return (rangeKm / high) to (rangeKm / low)
    }

    fun snapshot(): QualitySnapshot = QualitySnapshot(
        ratios = ratios.toList(),
        coverageMultiplier = coverageMultiplier,
        blocksSeen = blocksSeen,
    )

    fun restore(snapshot: QualitySnapshot) {
        ratios.clear()
        snapshot.ratios.filter { it.isFinite() && it > 0.0 }.takeLast(MAX_BLOCKS).forEach(ratios::addLast)
        coverageMultiplier = snapshot.coverageMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        blocksSeen = snapshot.blocksSeen
    }

    /**
     * Підганяє ширину так, щоб обіцяні 80 % справді були 80 %.
     *
     * Це і робить інтервал чесним: він не проголошений, а виміряний. Якщо в
     * оголошені межі влучає половина блоків, множник сам їх розсуне.
     */
    private fun updateCoverage(ratio: Double) {
        val bounds = ratioBounds() ?: return
        val outside = if (ratio < bounds.first || ratio > bounds.second) 1.0 else 0.0
        coverageMultiplier = (coverageMultiplier * (1.0 + COVERAGE_STEP * (outside - TARGET_MISS)))
            .coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    private fun quantile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 1.0
        val position = fraction * (sorted.size - 1)
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val weight = position - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    companion object {
        /** Довжина блоку: запас ходу цікавий на десятках кілометрів, не на п'яти хвилинах. */
        const val BLOCK_KM = 10.0

        const val MAX_BLOCKS = 200

        /** Менше блоків — і квантилі описували б випадковість, а не розкид. */
        const val MIN_BLOCKS_FOR_BAND = 12

        const val LOW_QUANTILE = 0.1
        const val HIGH_QUANTILE = 0.9

        /** Обіцяємо 80 %, тобто промахів має бути 20 %. */
        const val TARGET_MISS = 0.2

        const val COVERAGE_STEP = 0.05
        const val MIN_MULTIPLIER = 0.5
        const val MAX_MULTIPLIER = 4.0

        private const val MIN_BLOCK_KWH = 0.05
        private const val MS_PER_HOUR = 3_600_000.0
    }
}

data class QualitySnapshot(
    val ratios: List<Double>,
    val coverageMultiplier: Double,
    val blocksSeen: Int,
)
