package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripHistoryTest {

    private fun sample(ms: Long, km: Double, discharged: Double = 100.0) =
        TripSample(elapsedMs = ms, odometerKm = km, dischargedKwh = discharged, chargedKwh = 0.0)

    @Test
    fun `samples accumulate in order`() {
        val history = TripHistory()
            .plus(sample(0, 1000.0))
            .plus(sample(1000, 1000.5, discharged = 100.1))

        assertEquals(2, history.samples.size)
        assertEquals(1000.5, history.samples.last().odometerKm!!, 0.0001)
    }

    /**
     * Стоянка не має з'їдати історію: інакше за годину простою вікно «останні 20 км»
     * втратило б початок поїздки.
     */
    @Test
    fun `a sample that changed nothing but time replaces the previous one`() {
        val history = TripHistory()
            .plus(sample(0, 1000.0))
            .plus(sample(1000, 1000.0))
            .plus(sample(2000, 1000.0))

        assertEquals(1, history.samples.size)
        // Час усе одно рухається, щоб тривалість не завмирала.
        assertEquals(2000L, history.samples.last().elapsedMs)
    }

    @Test
    fun `history is capped so it cannot grow without bound`() {
        val history = (0..TripHistory.MAX_SAMPLES + 100).fold(TripHistory()) { acc, i ->
            acc.plus(sample(i * 1000L, 1000.0 + i))
        }

        assertEquals(TripHistory.MAX_SAMPLES, history.samples.size)
    }

    @Test
    fun `the trip window starts at the first sample`() {
        val history = TripHistory().plus(sample(0, 1000.0)).plus(sample(1000, 1005.0))

        assertEquals(1000.0, history.startOf(ConsumptionWindow.Trip)!!.odometerKm!!, 0.0001)
    }

    @Test
    fun `a distance window starts at the last sample before the mark`() {
        val history = TripHistory()
            .plus(sample(0, 1000.0))
            .plus(sample(1000, 1004.0))
            .plus(sample(2000, 1006.0))
            .plus(sample(3000, 1010.0))

        // Останні 5 км -> відмітка 1005; останній знімок до неї — 1004.
        assertEquals(1004.0, history.startOf(ConsumptionWindow.Last5Km)!!.odometerKm!!, 0.0001)
    }

    @Test
    fun `a distance window falls back to the trip start when the trip is shorter`() {
        val history = TripHistory().plus(sample(0, 1000.0)).plus(sample(1000, 1002.0))

        assertEquals(1000.0, history.startOf(ConsumptionWindow.Last20Km)!!.odometerKm!!, 0.0001)
        assertFalse(history.covers(ConsumptionWindow.Last20Km))
    }

    @Test
    fun `coverage is reported once enough distance has been driven`() {
        val history = TripHistory().plus(sample(0, 1000.0)).plus(sample(1000, 1006.0))

        assertTrue(history.covers(ConsumptionWindow.Last5Km))
        assertFalse(history.covers(ConsumptionWindow.Last20Km))
    }

    @Test
    fun `an empty history has no window start`() {
        assertTrue(TripHistory().isEmpty)
        assertEquals(null, TripHistory().startOf(ConsumptionWindow.Trip))
    }
}
