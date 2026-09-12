package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyCurveTest {

    /** Рівна крива: 50 кВт·год від 0 до 100 %, тобто пів кВт·год на відсоток. */
    private val even = (0..100 step 10).map {
        EnergyPoint(socPercent = it.toDouble(), energyKwh = it * 0.5)
    }

    @Test
    fun `a point between samples is interpolated`() {
        assertEquals(50.0, even.socAtEnergy(25.0)!!, 0.001)
        assertEquals(35.0, even.socAtEnergy(17.5)!!, 0.001)
    }

    @Test
    fun `the ends land on the ends`() {
        assertEquals(0.0, even.socAtEnergy(0.0)!!, 0.001)
        assertEquals(100.0, even.socAtEnergy(50.0)!!, 0.001)
    }

    /** Залишок поза кривою тримаємо на її кінцях, а не екстраполюємо в нікуди. */
    @Test
    fun `energy outside the curve is held at its ends`() {
        assertEquals(0.0, even.socAtEnergy(-5.0)!!, 0.001)
        assertEquals(100.0, even.socAtEnergy(500.0)!!, 0.001)
    }

    /**
     * Нерівномірна крива — це і є цікавий випадок: біля дна відсоток важить менше,
     * тому та сама енергія стоїть вище по шкалі, ніж дала б пряма.
     */
    @Test
    fun `an uneven curve places energy where the curve says, not where a line would`() {
        val uneven = listOf(
            EnergyPoint(0.0, 0.0),
            EnergyPoint(50.0, 10.0),   // перша половина шкали — лише 10 кВт·год
            EnergyPoint(100.0, 50.0),  // друга — 40
        )

        // Пряма дала б 20 %, крива дає 50 %.
        assertEquals(50.0, uneven.socAtEnergy(10.0)!!, 0.001)
        assertEquals(75.0, uneven.socAtEnergy(30.0)!!, 0.001)
    }

    @Test
    fun `a curve too short to interpolate gives nothing`() {
        assertNull(emptyList<EnergyPoint>().socAtEnergy(1.0))
        assertNull(listOf(EnergyPoint(0.0, 0.0)).socAtEnergy(1.0))
    }

    /** Пласка ділянка кривої не має ділити на нуль. */
    @Test
    fun `a flat stretch does not divide by zero`() {
        val flat = listOf(
            EnergyPoint(0.0, 0.0),
            EnergyPoint(50.0, 10.0),
            EnergyPoint(60.0, 10.0),
            EnergyPoint(100.0, 40.0),
        )

        assertEquals(50.0, flat.socAtEnergy(10.0)!!, 0.001)
    }
}
