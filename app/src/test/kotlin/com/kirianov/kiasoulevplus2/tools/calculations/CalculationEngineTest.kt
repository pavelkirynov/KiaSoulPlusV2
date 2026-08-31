package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.TripHistory
import com.kirianov.kiasoulevplus2.Data.TripSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculationEngineTest {

    private fun history(vararg samples: TripSample) =
        samples.fold(TripHistory()) { acc, sample -> acc.plus(sample) }

    private fun sample(minutes: Long, km: Double, discharged: Double, charged: Double = 0.0) =
        TripSample(
            elapsedMs = minutes * 60_000,
            odometerKm = km,
            dischargedKwh = discharged,
            chargedKwh = charged,
        )

    @Test
    fun `power is negative while discharging`() {
        val result = CalculationEngine.calculate(
            BmsData(batteryVoltage = 366.0, batteryCurrent = -100.0),
            CellData(),
            TripHistory(),
            ConsumptionWindow.Trip,
        )
        assertEquals(-36.6, result.powerKw, 0.0001)
    }

    @Test
    fun `cell spread ignores unread cells`() {
        val result = CalculationEngine.calculate(
            BmsData(),
            CellData(cellVoltages = listOf(0.0, 3.90, 3.85, 0.0, 4.05)),
            TripHistory(),
            ConsumptionWindow.Trip,
        )

        assertEquals(3.85, result.minCellVoltage, 0.0001)
        assertEquals(4.05, result.maxCellVoltage, 0.0001)
        assertEquals(0.20, result.cellDeltaVolts, 0.0001)
    }

    /** 30 хв, 40 км, витрачено 6 кВт·год, повернуто 1 -> 5 кВт·год чисто. */
    @Test
    fun `trip stats tie energy to time and distance`() {
        val stats = CalculationEngine.statsFor(
            history(
                sample(minutes = 0, km = 50_000.0, discharged = 5987.6, charged = 6123.4),
                sample(minutes = 30, km = 50_040.0, discharged = 5993.6, charged = 6124.4),
            ),
            ConsumptionWindow.Trip,
        )

        assertEquals(40.0, stats.distanceKm, 0.0001)
        assertEquals(30 * 60_000L, stats.durationMs)
        assertEquals(6.0, stats.consumedKwh, 0.0001)
        assertEquals(1.0, stats.recoveredKwh, 0.0001)
        assertEquals(5.0, stats.netKwh, 0.0001)
        assertEquals(125.0, stats.whPerKm!!, 0.0001)      // 5000 Вт·год / 40 км
        assertEquals(12.5, stats.kwhPer100Km!!, 0.0001)
        assertEquals(80.0, stats.averageSpeedKmh!!, 0.0001) // 40 км за пів години
        assertEquals(10.0, stats.averagePowerKw!!, 0.0001)  // 5 кВт·год за пів години
        assertTrue(stats.isComplete)
    }

    /** Діапазон «останні 5 км» має рахуватися лише по хвосту поїздки. */
    @Test
    fun `a distance window only covers the tail of the trip`() {
        val trip = history(
            sample(minutes = 0, km = 1000.0, discharged = 100.0),
            sample(minutes = 10, km = 1010.0, discharged = 102.0),
            sample(minutes = 20, km = 1015.0, discharged = 103.0),
            sample(minutes = 25, km = 1020.0, discharged = 104.5),
        )

        val window = CalculationEngine.statsFor(trip, ConsumptionWindow.Last5Km)

        assertEquals(5.0, window.distanceKm, 0.0001)
        assertEquals(1.5, window.consumedKwh, 0.0001)
        assertEquals(30.0, window.kwhPer100Km!!, 0.0001) // 1500 Вт·год / 5 км
        assertTrue(window.isComplete)
    }

    @Test
    fun `a window longer than the trip is reported as incomplete`() {
        val trip = history(
            sample(minutes = 0, km = 1000.0, discharged = 100.0),
            sample(minutes = 10, km = 1003.0, discharged = 100.6),
        )

        val window = CalculationEngine.statsFor(trip, ConsumptionWindow.Last20Km)

        assertFalse(window.isComplete)
        // Показуємо те, що є, а не порожньо: 3 км замість очікуваних 20.
        assertEquals(3.0, window.distanceKm, 0.0001)
        assertEquals(20.0, window.kwhPer100Km!!, 0.0001)
    }

    /** Без пробігу витрату на 100 км рахувати нема з чого, але кВт·год і час є. */
    @Test
    fun `consumption per distance is absent while the odometer is not read`() {
        val trip = history(
            sample(minutes = 0, km = 0.0, discharged = 100.0),
            sample(minutes = 15, km = 0.0, discharged = 102.5),
        )

        val stats = CalculationEngine.statsFor(trip, ConsumptionWindow.Trip)

        assertNull(stats.whPerKm)
        assertNull(stats.averageSpeedKmh)
        assertEquals(2.5, stats.consumedKwh, 0.0001)
        assertEquals(10.0, stats.averagePowerKw!!, 0.0001)
    }

    @Test
    fun `an empty history yields nothing to show`() {
        val stats = CalculationEngine.statsFor(TripHistory(), ConsumptionWindow.Trip)

        assertFalse(stats.hasData)
        assertEquals(0.0, stats.distanceKm, 0.0001)
    }
}
