package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeHistoryTest {

    private fun session(kwh: Double, socRise: Double, endedAtMs: Long) =
        ChargeSession(kwh = kwh, socRise = socRise, startedAtMs = 0L, endedAtMs = endedAtMs, cause = "тест")

    private val sessions = listOf(
        session(kwh = 10.0, socRise = 20.0, endedAtMs = 2_000L),
        session(kwh = 5.0, socRise = 10.0, endedAtMs = 1_000L),
        session(kwh = 3.0, socRise = 6.0, endedAtMs = 500L),
    )

    /** Підсумок бере лише зарядки, що завершились у проміжку. */
    @Test
    fun `totals sum only sessions inside the range`() {
        val totals = ChargeHistory.totals(sessions, capacityKwh = 0.0, fromMs = 900L, toMs = 2_500L)
        assertEquals(2, totals.count)
        assertEquals(15.0, totals.counterKwh, 0.001)
        assertEquals(30.0, totals.socRise, 0.001)
        // Без ємності енергія = лічильник (заниження краще за прочерк).
        assertEquals(15.0, totals.energyKwh, 0.001)
    }

    /** Із заданою ємністю головне число рахується за шкалою заряду. */
    @Test
    fun `energy uses the pack capacity when given`() {
        val totals = ChargeHistory.totals(sessions, capacityKwh = 44.0, fromMs = 0L, toMs = 3_000L)
        // 36 п.п. × 44 кВт·год / 100 = 15.84.
        assertEquals(15.84, totals.energyKwh, 0.01)
    }

    /** Порожній проміжок — порожній підсумок. */
    @Test
    fun `an empty range yields empty totals`() {
        val totals = ChargeHistory.totals(sessions, capacityKwh = 44.0, fromMs = 3_000L, toMs = 4_000L)
        assertTrue(totals.isEmpty)
        assertEquals(0.0, totals.energyKwh, 0.001)
    }

    /** Межі включні з обох боків. */
    @Test
    fun `range bounds are inclusive`() {
        assertEquals(1, ChargeHistory.inRange(sessions, 500L, 500L).size)
        assertEquals(3, ChargeHistory.inRange(sessions, 500L, 2_000L).size)
    }
}
