package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import kotlin.random.Random

/**
 * Вигадане авто з **відомими** коефіцієнтами. Потрібне, щоб перевіряти не «чи не
 * впав розрахунок», а головне: чи справді модель знаходить ту закономірність, яка
 * в даних закладена.
 *
 * Сюди ж додається те, чого застосунок не бачить, — рельєф. Він навмисно вноситься
 * як зсув енергії, залежний від відстані: модель не мусить його вгадати, вона мусить
 * не зламатися і чесно показати ширший інтервал.
 */
class VirtualCar(
    val auxKw: Double = 0.85,
    val rollingKw: Double = 2.20,
    val aeroKw: Double = 0.62,
    val heatingKw: Double = 2.00,
    /** Ємність перепакованого пакета, кВт·год: число від того, хто його збирав. */
    val capacityKwh: Double = 51.0,
    private val seed: Int = 42,
) {

    /** Запас ходу на повному пакеті за сталої швидкості, км. */
    fun trueRangeKm(speedKmh: Double, ambientTempC: Double = 20.0): Double =
        capacityKwh * 1000.0 / trueWhPerKm(speedKmh, ambientTempC)


    private val random = Random(seed)

    /** Справжня середня потужність за таких умов, кВт. */
    fun truePowerKw(speedMps: Double, cubedMps: Double, ambientTempC: Double): Double {
        val heating = maxOf(0.0, 15.0 - ambientTempC) / 10.0
        return auxKw +
            rollingKw * (speedMps / 10.0) +
            aeroKw * (cubedMps / 1000.0) +
            heatingKw * heating
    }

    /** Справжня витрата за таких умов, Вт·год/км. */
    fun trueWhPerKm(speedKmh: Double, ambientTempC: Double = 20.0): Double {
        val mps = speedKmh / 3.6
        return truePowerKw(mps, mps * mps * mps, ambientTempC) * 1000.0 / speedKmh
    }

    /**
     * Відрізок такого авто.
     *
     * [hillNoiseKw] — невидимий рельєф: чистий зсув потужності, якого в ознаках немає.
     * [measurementNoiseKw] — звичайний шум вимірювання.
     */
    fun segment(
        speedKmh: Double,
        ambientTempC: Double = 20.0,
        batteryTempC: Double = 20.0,
        distanceKm: Double = 3.0,
        atMs: Long = 0L,
        hillNoiseKw: Double = 0.0,
        measurementNoiseKw: Double = 0.0,
        speedSpreadKmh: Double = 0.0,
    ): MlSegment {
        val mps = speedKmh / 3.6
        val spread = speedSpreadKmh / 3.6
        // ⟨v³⟩ ≥ ⟨v⟩³: нерівна їзда дорожча за рівну з тією самою середньою.
        val cubed = mps * mps * mps + 3.0 * mps * spread * spread
        val hours = distanceKm / speedKmh

        val noise = if (measurementNoiseKw > 0.0) random.nextGaussian() * measurementNoiseKw else 0.0
        val hill = if (hillNoiseKw > 0.0) random.nextGaussian() * hillNoiseKw else 0.0
        val power = truePowerKw(mps, cubed, ambientTempC) + noise + hill

        return MlSegment(
            startedAtMs = atMs,
            distanceKm = distanceKm,
            durationMs = (hours * 3_600_000.0).toLong(),
            energyKwh = power * hours,
            tractionKwh = power * hours,
            regenKwh = 0.0,
            meanSpeedMps = mps,
            meanSpeedCubedMps = cubed,
            speedVarianceMps = spread * spread,
            speedSamples = 60,
            coverage = 1.0,
            ambientTempC = ambientTempC,
            batteryTempC = batteryTempC,
        )
    }

    /**
     * Правдоподібний тиждень їзди: місто, траса й приміська суміш упереміш.
     * Різноманітність швидкостей тут навмисна — саме вона, а не кількість відрізків,
     * дозволяє відділити постійний відбір від опору коченню й від опору повітря.
     */
    fun week(
        segments: Int,
        ambientTempC: Double = 20.0,
        startAtMs: Long = 0L,
        hillNoiseKw: Double = 0.0,
        measurementNoiseKw: Double = 0.0,
    ): List<MlSegment> = (0 until segments).map { index ->
        val speed = SPEEDS[index % SPEEDS.size]
        val spread = if (speed < 50.0) 12.0 else 4.0
        segment(
            speedKmh = speed,
            ambientTempC = ambientTempC,
            distanceKm = if (speed < 50.0) 3.0 else 6.0,
            atMs = startAtMs + index * SEGMENT_SPACING_MS,
            hillNoiseKw = hillNoiseKw,
            measurementNoiseKw = measurementNoiseKw,
            speedSpreadKmh = spread,
        )
    }

    private fun Random.nextGaussian(): Double {
        // Бокс — Мюллер: своя реалізація, бо kotlin.random гаусового не має.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private companion object {
        val SPEEDS = listOf(30.0, 90.0, 45.0, 110.0, 60.0, 25.0, 80.0, 50.0, 100.0, 35.0)
        const val SEGMENT_SPACING_MS = 10 * 60 * 1000L
    }
}
