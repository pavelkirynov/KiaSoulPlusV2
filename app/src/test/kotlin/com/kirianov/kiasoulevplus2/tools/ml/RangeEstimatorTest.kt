package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlConfidence
import com.kirianov.kiasoulevplus2.Data.MlSegment
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {

    /** Відрізок руху з відомою відстанню й тривалістю: решта тут не важлива. */
    private fun drive(distanceKm: Double, durationMs: Long, charging: Boolean = false) = MlSegment(
        startedAtMs = 0L,
        distanceKm = distanceKm,
        durationMs = durationMs,
        energyKwh = distanceKm * 0.15,
        meanSpeedMps = if (durationMs > 0) distanceKm * 1000.0 / (durationMs / 1000.0) else 0.0,
        meanSpeedCubedMps = 0.0,
        charging = charging,
    )

    /**
     * Наскрізна перевірка всієї витівки. Вигадане авто з відомою витратою і відомою
     * ємністю проїжджає свій тиждень; після цього прогноз запасу ходу має збігтися
     * з тим, що це авто справді проїде.
     */
    @Test
    fun `predicts the range a known car will actually cover`() {
        val car = VirtualCar()
        val capacityKwh = 51.0
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

    /**
     * Крива задає залишок енергії, бо знає розподіл ємності по шкалі. Шкала цього
     * авто різко нерівна, і «88 % шкали» не означає «88 % енергії».
     */
    @Test
    fun `the curve decides the remaining energy`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trainedOn(car, capacityKwh = 51.0)

        fun predictWith(curveEnergyKwh: Double?, totalMeasured: Boolean = true) = RangeEstimator.predict(
            consumption = consumption,
            capacity = capacity,
            quality = quality,
            preciseSocPercent = 80.0,
            recent = DriveConditions.steady(60.0),
            curveEnergyKwh = curveEnergyKwh,
            curveTotalMeasured = totalMeasured,
        )

        val assumed = predictWith(null)!!
        val measured = predictWith(23.0)!!

        assertEquals(23.0, measured.usableEnergyRemainingKwh, 0.001)
        assertTrue("вимір не позначено", measured.capacityMeasured)
        assertFalse("припущення позначено виміром", assumed.capacityMeasured)
        // Крива на аксіомі дає число, але виміром воно не називається.
        assertFalse(predictWith(23.0, totalMeasured = false)!!.capacityMeasured)
        assertTrue(
            "менша ємність мусить дати менший запас: ${assumed.rangeKm} проти ${measured.rangeKm}",
            measured.rangeKm < assumed.rangeKm,
        )
        // Сценарії теж мусять їхати від виміряної енергії, а не від припущеної.
        assertTrue(measured.scenarios.all { it.rangeKm < assumed.rangeKm })
    }

    /** Порожній вимір нічого не ламає: береться припущення, як і до нього. */
    @Test
    fun `a curve with nothing measured falls back to the model`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trainedOn(car, capacityKwh = 51.0)

        val prediction = RangeEstimator.predict(
            consumption = consumption,
            capacity = capacity,
            quality = quality,
            preciseSocPercent = 80.0,
            recent = DriveConditions.steady(60.0),
            curveEnergyKwh = 0.0,
        )!!

        assertFalse(prediction.capacityMeasured)
        assertEquals(capacity.energyRemainingKwh(80.0), prediction.usableEnergyRemainingKwh, 0.001)
    }

    /** Швидше їхати — менше проїхати. Сценарії мають бути впорядковані. */
    @Test
    fun `driving faster gets you less far`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trainedOn(car, 51.0)

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

    /**
     * Вивчене про клімат мусить доходити до прогнозу. Це не самоочевидно: ознаку
     * легко додати в навчання й забути в передбаченні — тоді взимку з увімкненою
     * пічкою прогноз буде оптимістичним рівно на її ціну, і жоден тест навчання
     * цього не помітить.
     */
    @Test
    fun `a running heater shortens the range it predicts`() {
        val car = VirtualCar()
        val consumption = ConsumptionModel()

        // Навчання: та сама погода, різний клімат, чесна частка з кадру 200.
        val off = car.week(100, ambientTempC = 8.0).map { it.copy(climateShare = 0.0) }
        val on = car.week(100, ambientTempC = 8.0, startAtMs = 2_000_000L).map { segment ->
            val hours = segment.durationMs / 3_600_000.0
            val total = segment.energyKwh + 3.0 * hours
            segment.copy(energyKwh = total, tractionKwh = total, climateShare = 3.0 * hours / total)
        }
        (off + on).sortedBy { it.startedAtMs }.forEach(consumption::learn)

        val capacity = CapacityModel()
        fun range(share: Double?) = RangeEstimator.predict(
            consumption, capacity, PredictionQuality(),
            preciseSocPercent = 90.0,
            recent = DriveConditions.steady(60.0, ambientTempC = 8.0, climateShare = share),
        )!!

        val heaterOff = range(0.0)
        val heaterOn = range(0.3)

        assertTrue(
            "з пічкою запас мав скоротитися: ${heaterOn.rangeKm} проти ${heaterOff.rangeKm}",
            heaterOn.rangeKm < heaterOff.rangeKm * 0.9,
        )
        // І сценарії теж мусять нести клімат із собою, а не вдавати літо.
        assertTrue(
            "сценарій 90 км/год має знати про пічку",
            heaterOn.scenarios.first { it.speedKmh == 90.0 }.rangeKm <
                heaterOff.scenarios.first { it.speedKmh == 90.0 }.rangeKm,
        )
    }

    /** «Як їхали недавно» має нести і клімат: він частина цих умов. */
    @Test
    fun `recent conditions carry the climate along`() {
        val car = VirtualCar()
        val segments = car.week(20).map { it.copy(climateShare = 0.25) }

        val conditions = RangeEstimator.recentConditions(segments)

        assertEquals(0.25, conditions.climateShare ?: 0.0, 1e-9)
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
        val (consumption, capacity, quality) = trainedOn(car, 51.0)

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

        listOf(90.0 to 55.0, 80.0 to 40.0, 95.0 to 50.0, 70.0 to 30.0, 85.0 to 45.0, 60.0 to 20.0, 100.0 to 35.0, 75.0 to 15.0)
            .forEachIndexed { index, (from, to) ->
                capacity.learn(from, to, capacityKwh * (from - to) / 100.0, atMs = index * 86_400_000L)
            }

        return Triple(consumption, capacity, quality)
    }

    /**
     * Велике число зверху мусить уміти сказати, на чому воно побудоване: інакше
     * воно читається як «стільки проїду», а означає «стільки проїду, якщо їхати
     * так само, як останні години».
     */
    @Test
    fun `the basis reports moving time and mean speed`() {
        val segments = listOf(
            drive(distanceKm = 5.0, durationMs = 300_000),
            drive(distanceKm = 10.0, durationMs = 300_000),
        )

        val basis = RangeEstimator.basisOf(segments, climateShare = 0.12, climateLive = true)

        assertEquals(2, basis.segments)
        assertEquals(600_000L, basis.movingMs)
        // 15 км за 10 хвилин -> 90 км/год
        assertEquals(90.0, basis.meanSpeedKmh, 0.001)
        assertEquals(0.12, basis.climateShare!!, 0.001)
        assertTrue(basis.climateLive)
        assertTrue(basis.hasHistory)
    }

    /** Час РУХУ, а не час на годиннику: зарядка й стоянка у вікно не входять. */
    @Test
    fun `charging segments are not part of the basis`() {
        val segments = listOf(
            drive(distanceKm = 5.0, durationMs = 300_000),
            drive(distanceKm = 0.0, durationMs = 3_600_000, charging = true),
        )

        val basis = RangeEstimator.basisOf(segments, climateShare = null, climateLive = false)

        assertEquals(1, basis.segments)
        assertEquals(300_000L, basis.movingMs)
    }

    /** Поки поїздок немає, казати про «ваш стиль» нема чого. */
    @Test
    fun `without segments the basis has no history`() {
        val basis = RangeEstimator.basisOf(emptyList(), climateShare = null, climateLive = false)

        assertFalse(basis.hasHistory)
        assertEquals(0, basis.segments)
    }
}
