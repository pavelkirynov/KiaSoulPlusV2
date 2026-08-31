package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteCandidatesTest {

    /**
     * Сценарій, для якого це і зроблено: у відповіді десь лежить пробіг, і його
     * знаходять звіркою з приладовою панеллю.
     */
    @Test
    fun `finds a candidate matching the dashboard reading`() {
        // 123456 = 0x01E240, покладено на зсув 8.
        val bytes = listOf(0x62, 0xB0, 0x02, 0, 0, 0, 0, 0, 0x01, 0xE2, 0x40, 0, 0)

        val candidates = ByteCandidates.find(bytes, 1L..2_000_000L)

        assertTrue(candidates.any { it.index == 8 && it.width == 3 && it.value == 123_456L })
    }

    @Test
    fun `candidates outside the range are dropped`() {
        val bytes = listOf(0xFF, 0xFF, 0xFF, 0xFF)

        assertTrue(ByteCandidates.find(bytes, 1L..1000L).isEmpty())
    }

    @Test
    fun `a short reply yields no candidates`() {
        assertTrue(ByteCandidates.find(listOf(0x62), 1L..2_000_000L).isEmpty())
        assertTrue(ByteCandidates.find(emptyList(), 1L..2_000_000L).isEmpty())
    }

    @Test
    fun `candidates are ordered by offset then width`() {
        val bytes = listOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50)

        val candidates = ByteCandidates.find(bytes, 1L..2_000_000L)

        assertEquals(candidates.sortedWith(compareBy({ it.index }, { it.width })), candidates)
    }
}
