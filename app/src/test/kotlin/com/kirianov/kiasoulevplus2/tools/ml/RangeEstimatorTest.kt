package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlConfidence
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {

    /**
     * Наскрізна перевірка всієї витівки. Вигадане авто з відомою витратою і відомою
     * ємністю проїжджає свій тиждень; після цього прогноз запасу ходу має збігтися
     * з тим, що це авто справді проїде.
     */
    @Test
    fun `predicts the range a known car will actually cover`() {
        val car = VirtualCar()
        val capacityKwh = 24.0
        val (consumption, capacity, quality) = trainedOn(car, capacityKwh)

        val prediction = RangeEstimator.predict(
            consumption = consumption,
            capacity = capacity,
            quality = quality,
            preciseSocPercent = 80.0,
            recent = DriveConditions.steady(60.0),
        )

        assertNotNull(prediction)
        prediction!!

        // Скільки енергії справді лишилося над дном і на скільки її вистачить.
        val energy = capacity.energyRemainingKwh(80.0)
        val expectedKm = energy * 1000.0 / car.trueWhPerKm(60.0)

        val error = abs(prediction.rangeKm - expectedKm) / expectedKm
        assertTrue(
            "очікували ${expectedKm.toInt()} км, отримали ${prediction.rangeKm.toInt()} км",
            error < 0.08,
        )
        assertTrue("інтервал має накривати правду", expectedKm in prediction.rangeFromKm..prediction.rangeToKm)
    }

    /** Швидше їхати — менше проїхати. Сценарії мають бути впорядковані. */
    @Test
    fun `driving faster gets you less far`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trainedOn(car, 24.0)

        val prediction = RangeEstimator.predict(
            consumption, capacity, quality,
            preciseSocPercent = 90.0,
            recent = DriveConditions.steady(60.0),
        )!!

        val ranges = prediction.scenarios.map { it.rangeKm }
        assertEquals(RangeEstimator.SCENARIO_SPEEDS_KMH.size, ranges.size)
        assertTrue("на 110 має бути менше, ніж на 40: $ranges", ranges.last() < ranges.first())
        assertTrue(ranges.zipWithNext().all { (faster, slower) -> faster > slower })
    }

    /** Мороз коротшає запас ходу, і прогноз має це показувати. */
    @Test
    fun `a cold day shortens the predicted range`() {
        val car = VirtualCar(heatingKw = 2.5)
        val consumption = ConsumptionModel()
        val capacity = CapacityModel()
        val quality = PredictionQuality()

        (car.week(80, ambientTempC = 20.0) + car.week(80, ambientTempC = -8.0, startAtMs = 2_000_000L))
            .sortedBy { it.startedAtMs }
            .forEach { consumption.learn(it) }

        fun rangeAt(temp: Double) = RangeEstimator.predict(
            consumption, capacity, quality,
            preciseSocPercent = 80.0,
            recent = DriveConditions.steady(60.0, ambientTempC = temp),
        )!!.rangeKm

        assertTrue("на морозі запас має бути помітно меншим", rangeAt(-8.0) < rangeAt(20.0) * 0.85)
    }

    /** Порожня батарея — не привід малювати кілометри. */
    @Test
    fun `an empty battery predicts nothing`() {
        val consumption = ConsumptionModel()
        val capacity = CapacityModel()

        val prediction = RangeEstimator.predict(
            consumption, capacity, PredictionQuality(),
            preciseSocPercent = capacity.floorSocPercent,
            recent = DriveConditions.steady(60.0),
        )

        assertNull(prediction)
    }

    /** Поки модель нічого не бачила, вона й не вдає впевненості. */
    @Test
    fun `an untrained model does not claim confidence`() {
        val consumption = ConsumptionModel()
        val prediction = RangeEstimator.predict(
            consumption, CapacityModel(), PredictionQuality(),
            preciseSocPercent = 80.0,
            recent = DriveConditions.steady(60.0),
        )!!

        val confidence = RangeEstimator.confidenceOf(consumption.readiness, prediction)
        assertTrue(
            "щойно встановлений застосунок не має заявляти «добре»",
            confidence == MlConfidence.None || confidence == MlConfidence.Learning,
        )
        assertTrue("і інтервал має бути широким", prediction.rangeToKm - prediction.rangeFromKm > prediction.rangeKm * 0.2)
        assertTrue("ширина ще не виміряна, а припущена", !prediction.measuredBand)
    }

    /** Після тижня їзди інтервал звужується, а впевненість росте. */
    @Test
    fun `the interval narrows once there is data`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trainedOn(car, 24.0)

        val prediction = RangeEstimator.predict(
            consumption, capacity, quality,
            preciseSocPercent = 80.0,
            recent = DriveConditions.steady(60.0),
        )!!

        val relative = (prediction.rangeToKm - prediction.rangeFromKm) / 2.0 / prediction.rangeKm
        assertTrue("інтервал мав звузитися, а вийшло $relative", relative < 0.2)
        assertTrue(RangeEstimator.confidenceOf(consumption.readiness, prediction) != MlConfidence.None)
    }

    /**
     * «Як їхали останнім часом» має важити пробігом: п'ятихвилинна стоянка не
     * повинна переважити десять кілометрів траси.
     */
    @Test
    fun `recent conditions are weighted by distance, not by count`() {
        val car = VirtualCar()
        val longHighway = car.segment(speedKmh = 100.0, distanceKm = 30.0)
        val shortStops = List(5) { car.segment(speedKmh = 20.0, distanceKm = 0.5) }

        val conditions = RangeEstimator.recentConditions(shortStops + longHighway)

        assertTrue("трасa мала переважити: ${conditions.meanSpeedKmh}", conditions.meanSpeedKmh > 80.0)
    }

    /** Без жодного відрізка беремо розумне припущення, а не ділимо на нуль. */
    @Test
    fun `recent conditions fall back to something sensible`() {
        val conditions = RangeEstimator.recentConditions(emptyList())

        assertEquals(50.0, conditions.meanSpeedKmh, 0.01)
    }

    private fun trainedOn(car: VirtualCar, capacityKwh: Double): Triple<ConsumptionModel, CapacityModel, PredictionQuality> {
        val consumption = ConsumptionModel()
        val capacity = CapacityModel()
        val quality = PredictionQuality()

        // З шумом і рельєфом: на бездоганно чистих даних інтервал вийшов би
        // неправдоподібно вузьким, бо промахуватися моделі просто нема на чому.
        car.week(segments = 140, hillNoiseKw = 0.5, measurementNoiseKw = 0.25).forEach { segment ->
            val predicted = consumption.learn(segment)
            if (predicted != null) quality.observe(segment, predicted)
        }

        listOf(90.0 to 55.0, 80.0 to 40.0, 95.0 to 50.0, 70.0 to 30.0, 85.0 to 45.0, 60.0 to 20.0)
            .forEachIndexed { index, (from, to) ->
                capacity.learn(from, to, capacityKwh * (from - to) / 100.0, atMs = index * 86_400_000L)
            }

        return Triple(consumption, capacity, quality)
    }
}
