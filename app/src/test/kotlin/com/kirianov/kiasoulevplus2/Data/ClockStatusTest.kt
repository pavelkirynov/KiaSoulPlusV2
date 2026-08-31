package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Вердикт існує для однієї речі: не шукати причину там, де її немає.
 * Користувач уже витратив час на вимикання GPS, яке не могло допомогти.
 */
class ClockStatusTest {

    @Test
    fun `no frame yet means no verdict`() {
        assertEquals(ClockDiagnosis.Unknown, ClockStatus().diagnosis)
    }

    @Test
    fun `a matching clock is fine`() {
        assertEquals(
            ClockDiagnosis.Fine,
            ClockStatus(driftSeconds = 4, rateSecondsPerHour = 1.0).diagnosis,
        )
    }

    /** Йде нормально, просто виставлений не туди: достатньо виставити вручну. */
    @Test
    fun `a clock that is merely set wrong is told apart from a broken one`() {
        assertEquals(
            ClockDiagnosis.SetWrong,
            ClockStatus(driftSeconds = 40 * 60, rateSecondsPerHour = 0.0).diagnosis,
        )
    }

    @Test
    fun `jumps outrank a plain offset`() {
        assertEquals(
            ClockDiagnosis.TimeJumps,
            ClockStatus(driftSeconds = 40 * 60, rateSecondsPerHour = 0.0, jumpCount = 3).diagnosis,
        )
    }

    /**
     * Хід важливіший за все інше: якщо годинник іде з іншою швидкістю, вимикання GPS
     * не допоможе, і сказати про це треба першим.
     */
    @Test
    fun `a rate fault outranks jumps and offset`() {
        assertEquals(
            ClockDiagnosis.RateFault,
            ClockStatus(
                driftSeconds = 40 * 60,
                rateSecondsPerHour = 145.0,
                jumpCount = 3,
            ).diagnosis,
        )
    }

    @Test
    fun `a slightly imperfect rate is not called a fault`() {
        assertEquals(
            ClockDiagnosis.Fine,
            ClockStatus(driftSeconds = 3, rateSecondsPerHour = 5.0).diagnosis,
        )
    }
}
