package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeAccuracyTest {

    /**
     * Той самий приклад, з якого все почалося: обіцяв 200, проїхали 50, обіцяє
     * 135. Отже 50 км дороги з'їли 65 км запасу — оптимізм на 30 %.
     */
    @Test
    fun `an optimistic forecast shows how much it overpromised`() {
        var accuracy = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)
        accuracy = accuracy.observe(rangeKm = 135.0, odometerKm = 1_050.0)

        assertEquals(65.0, accuracy.predictedDropKm, 0.001)
        assertEquals(50.0, accuracy.drivenKm, 0.001)
        assertEquals(15.0, accuracy.errorKm, 0.001)
        assertEquals(30.0, accuracy.errorPercent!!, 0.001)
    }

    /** Точний прогноз: запас падає рівно на стільько, скільки проїхано. */
    @Test
    fun `an exact forecast shows no error`() {
        var accuracy = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)
        accuracy = accuracy.observe(rangeKm = 150.0, odometerKm = 1_050.0)

        assertEquals(0.0, accuracy.errorKm, 0.001)
        assertEquals(0.0, accuracy.errorPercent!!, 0.001)
    }

    /** Перестраховка читається від'ємною помилкою: проїдете більше за обіцяне. */
    @Test
    fun `a cautious forecast shows a negative error`() {
        var accuracy = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)
        accuracy = accuracy.observe(rangeKm = 165.0, odometerKm = 1_050.0)

        assertEquals(-15.0, accuracy.errorKm, 0.001)
        assertEquals(-30.0, accuracy.errorPercent!!, 0.001)
    }

    /**
     * Потрібні обидва числа одразу: прогноз без одометра нема з чим порівнювати,
     * одометр без прогнозу — нема що перевіряти.
     */
    @Test
    fun `the count does not start without both numbers`() {
        assertFalse(RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 0.0).started)
        assertFalse(RangeAccuracy().observe(rangeKm = 0.0, odometerKm = 1_000.0).started)
        assertTrue(RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0).started)
    }

    /** На першому кілометрі похибка одометра важить більше за якість прогнозу. */
    @Test
    fun `a percent is withheld until enough distance is covered`() {
        var accuracy = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)
        accuracy = accuracy.observe(rangeKm = 199.0, odometerKm = 1_000.5)

        assertNull(accuracy.errorPercent)
        assertFalse(accuracy.hasEnoughDistance)

        accuracy = accuracy.observe(rangeKm = 196.0, odometerKm = 1_004.0)
        assertTrue(accuracy.hasEnoughDistance)
    }

    /** Початкову обіцянку не переписуємо: саме її ми й перевіряємо. */
    @Test
    fun `the opening promise is not overwritten by later ones`() {
        var accuracy = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)
        accuracy = accuracy.observe(rangeKm = 150.0, odometerKm = 1_040.0)
        accuracy = accuracy.observe(rangeKm = 120.0, odometerKm = 1_070.0)

        assertEquals(200.0, accuracy.startRangeKm, 0.001)
        assertEquals(80.0, accuracy.predictedDropKm, 0.001)
        assertEquals(70.0, accuracy.drivenKm, 0.001)
    }

    /** Одометр не може йти назад: хибне читання не має дати від'ємний шлях. */
    @Test
    fun `an odometer going backwards gives no negative distance`() {
        var accuracy = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)
        accuracy = accuracy.observe(rangeKm = 190.0, odometerKm = 900.0)

        assertEquals(0.0, accuracy.drivenKm, 0.001)
    }

    /** Пропущений прогноз не має зіпсувати відлік: беремо, що є. */
    @Test
    fun `a missing reading leaves the count untouched`() {
        val started = RangeAccuracy().observe(rangeKm = 200.0, odometerKm = 1_000.0)

        assertEquals(started, started.observe(rangeKm = 0.0, odometerKm = 1_020.0))
        assertEquals(started, started.observe(rangeKm = 180.0, odometerKm = 0.0))
    }
}
