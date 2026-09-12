package com.kirianov.kiasoulevplus2.tools.autoconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {

    @Test
    fun `the pause doubles up to the ceiling`() {
        assertEquals(5_000L, AutoConnectPolicy.delayAfter(1))
        assertEquals(10_000L, AutoConnectPolicy.delayAfter(2))
        assertEquals(20_000L, AutoConnectPolicy.delayAfter(3))
        assertEquals(40_000L, AutoConnectPolicy.delayAfter(4))
        assertEquals(80_000L, AutoConnectPolicy.delayAfter(5))
        assertEquals(AutoConnectPolicy.MAX_DELAY_MS, AutoConnectPolicy.delayAfter(6))
    }

    /**
     * Головне про відступ: за багато годин без адаптера пауза не має ні
     * переповнитися, ні впасти назад до секунд — інакше застосунок молотив би
     * Bluetooth усю ніч.
     */
    @Test
    fun `a long night of failures stays at the ceiling`() {
        (6..10_000).forEach { attempt ->
            assertEquals(
                "спроба $attempt",
                AutoConnectPolicy.MAX_DELAY_MS,
                AutoConnectPolicy.delayAfter(attempt),
            )
        }
    }

    @Test
    fun `a nonsense attempt number falls back to the first pause`() {
        assertEquals(AutoConnectPolicy.FIRST_DELAY_MS, AutoConnectPolicy.delayAfter(0))
        assertEquals(AutoConnectPolicy.FIRST_DELAY_MS, AutoConnectPolicy.delayAfter(-3))
    }

    /** Сісти в машину і чекати підключення довше двох хвилин було б помітно. */
    @Test
    fun `the ceiling is short enough to catch someone getting into the car`() {
        assertTrue(AutoConnectPolicy.MAX_DELAY_MS <= 120_000L)
    }
}
