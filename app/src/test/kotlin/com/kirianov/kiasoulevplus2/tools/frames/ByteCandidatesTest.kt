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

    /**
     * Головний сценарій пошуку: водій вводить число зі щитка, і застосунок каже,
     * де воно лежить. Тут 188443 покладено на зсув 5 трьома байтами.
     */
    @Test
    fun `finds a known value and reports where it sits`() {
        val bytes = listOf(0x62, 0xB0, 0x02, 0x00, 0x00, 0x02, 0xE0, 0x1B, 0x00)

        val matches = ByteCandidates.findValue(bytes, 188_443)

        // Ті самі байти читаються і як 4 з нулем спереду, тому збіг не один.
        // Найвужче прочитання йде першим — саме воно й потрібне.
        assertEquals(5, matches[0].index)
        assertEquals(3, matches[0].width)
        assertEquals(1, matches[0].divisor)
        assertTrue(matches[0].bigEndian)
    }

    /** У кадрі величина буває в десятих: 188443 км лежить як 1884430. */
    @Test
    fun `finds a value stored in tenths`() {
        // 1884430 = 0x1CC10E
        val bytes = listOf(0x00, 0x1C, 0xC1, 0x0E, 0x00)

        val matches = ByteCandidates.findValue(bytes, 188_443)

        assertEquals(10, matches[0].divisor)
        assertEquals(1, matches[0].index)
        assertEquals(3, matches[0].width)
    }

    /** Порядок байтів у полі буває зворотним, і це теж треба знайти. */
    @Test
    fun `finds a little endian value`() {
        val bytes = listOf(0x00, 0x1B, 0xE0, 0x02, 0x00)

        val matches = ByteCandidates.findValue(bytes, 188_443)

        assertTrue(matches.any { !it.bigEndian && it.index == 1 && it.width == 3 })
    }

    @Test
    fun `reports nothing when the value is absent`() {
        val bytes = listOf(0x62, 0xB0, 0x01, 0x00, 0x00, 0x00, 0x00)

        assertTrue(ByteCandidates.findValue(bytes, 188_443).isEmpty())
    }

    @Test
    fun `a non positive target is refused`() {
        assertTrue(ByteCandidates.findValue(listOf(0x00, 0x00), 0).isEmpty())
        assertTrue(ByteCandidates.findValue(listOf(0x00, 0x00), -5).isEmpty())
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
