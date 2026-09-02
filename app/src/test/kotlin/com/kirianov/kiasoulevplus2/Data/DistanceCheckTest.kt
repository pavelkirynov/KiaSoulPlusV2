package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistanceCheckTest {

    @Test
    fun `a steady speed integrates to the obvious distance`() {
        var check = DistanceCheck()
        // Крок 6 с — приблизно так і приходить кадр 4F0. Шість хвилин на 90 км/год -> 9 км.
        (0..60).forEach { step ->
            check = check.plus(speedKmh = 90.0, atMs = step * 6_000L, odometerKm = null)
        }

        assertEquals(9.0, check.computedKm, 0.001)
        assertTrue(check.hasComputed)
    }

    /** Трапеції: розгін від нуля до 100 за 10 с дає половину, а не повну відстань. */
    @Test
    fun `acceleration counts as the mean of both ends`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 0.0, atMs = 0, odometerKm = null)
        check = check.plus(speedKmh = 100.0, atMs = 10_000, odometerKm = null)

        // середня 50 км/год за 10 с
        assertEquals(50.0 * 10.0 / 3600.0, check.computedKm, 0.0001)
    }

    /** З одного зразка трапеції не буває. */
    @Test
    fun `one sample gives no distance`() {
        val check = DistanceCheck().plus(speedKmh = 90.0, atMs = 0, odometerKm = null)

        assertEquals(0.0, check.computedKm, 0.001)
        assertFalse(check.hasComputed)
    }

    /**
     * Дірка у зв'язку не має вигадати кілометри: за десять хвилин тишею на 90 км/год
     * набігло б 15 км, яких ніхто не бачив.
     */
    @Test
    fun `a gap is clipped instead of inventing distance`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 90.0, atMs = 0, odometerKm = null)
        check = check.plus(speedKmh = 90.0, atMs = 10 * 60_000L, odometerKm = null)

        val clipped = 90.0 * (DistanceCheck.MAX_STEP_MS / 3_600_000.0)
        assertEquals(clipped, check.computedKm, 0.0001)
        assertTrue("Обрізане мусить бути значно меншим за 15 км", check.computedKm < 1.0)
    }

    /**
     * Крок між зразками більший за обрізку рахується не повністю. Це не дефект, а
     * рішення: за дірку невідомо, з якою швидкістю їхали, і чесніше недорахувати.
     */
    @Test
    fun `a step longer than the clip is counted only up to the clip`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 90.0, atMs = 0, odometerKm = null)
        check = check.plus(speedKmh = 90.0, atMs = 2 * DistanceCheck.MAX_STEP_MS, odometerKm = null)

        assertEquals(90.0 * DistanceCheck.MAX_STEP_MS / 3_600_000.0, check.computedKm, 0.0001)
    }

    /** Одометр веде відлік від першого показу: порівнювати з 188459 км сенсу немає. */
    @Test
    fun `the odometer is measured from its first reading`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 60.0, atMs = 0, odometerKm = 188_459.6)
        check = check.plus(speedKmh = 60.0, atMs = 60_000, odometerKm = 188_460.6)

        assertEquals(1.0, check.odometerKm, 0.001)
        assertTrue(check.hasOdometer)
    }

    @Test
    fun `the error against the odometer is reported as a percent`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 60.0, atMs = 0, odometerKm = 1_000.0)
        // 60 км/год це 0.1 км за кожні 6 с; шість хвилин -> 6 км і там, і там
        (1..60).forEach { step ->
            check = check.plus(speedKmh = 60.0, atMs = step * 6_000L, odometerKm = 1_000.0 + step * 0.1)
        }

        assertEquals(6.0, check.computedKm, 0.001)
        assertEquals(6.0, check.odometerKm, 0.001)
        assertEquals(0.0, check.errorPercent!!, 0.001)
    }

    @Test
    fun `an integral that runs ahead of the odometer shows a positive error`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 66.0, atMs = 0, odometerKm = 1_000.0)
        (1..60).forEach { step ->
            check = check.plus(speedKmh = 66.0, atMs = step * 6_000L, odometerKm = 1_000.0 + step * 0.1)
        }

        // 6.6 км проти 6.0 -> +10 %
        assertEquals(10.0, check.errorPercent!!, 0.01)
        assertTrue(check.differenceKm > 0.0)
    }

    @Test
    fun `without an odometer there is no error to report`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 60.0, atMs = 0, odometerKm = null)
        check = check.plus(speedKmh = 60.0, atMs = 60_000, odometerKm = null)

        assertNull(check.errorPercent)
        assertFalse(check.hasOdometer)
    }

    /** Час, що йде назад, не має відняти пройдене. */
    @Test
    fun `a backwards timestamp adds nothing`() {
        var check = DistanceCheck()
        check = check.plus(speedKmh = 60.0, atMs = 60_000, odometerKm = null)
        check = check.plus(speedKmh = 60.0, atMs = 0, odometerKm = null)

        assertEquals(0.0, check.computedKm, 0.001)
    }
}
