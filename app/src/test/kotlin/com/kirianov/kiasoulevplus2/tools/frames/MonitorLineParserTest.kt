package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorLineParserTest {

    @Test
    fun `parses a normal monitor line`() {
        val frame = MonitorLineParser.parse("4F0 00 5A 00 00 00 B3 C1 1C")!!

        assertEquals("4F0", frame.id)
        assertEquals(8, frame.bytes.size)
        assertEquals(0x5A, frame.bytes[1])
        assertEquals(0x1C, frame.bytes[7])
    }

    /**
     * Дешеві клони ELM327 v2.1 розбивають ID на два байти з паддінгом. Без цього
     * обходу близько половини кадрів не розпізнається, і виглядає це як
     * «адаптер не тягне».
     */
    @Test
    fun `unpads an id split across two bytes by cheap clones`() {
        val frame = MonitorLineParser.parse("00 00 06 53 00 00 00 00 00 A0 00 00")!!

        assertEquals("653", frame.id)
        assertEquals(0xA0, frame.bytes[5])
    }

    @Test
    fun `trims whitespace and control characters`() {
        val frame = MonitorLineParser.parse("\t 594  00 00 00 00 00 A0 03 00 \r\n")!!

        assertEquals("594", frame.id)
        assertEquals(8, frame.bytes.size)
    }

    @Test
    fun `lowercase bytes are accepted`() {
        val frame = MonitorLineParser.parse("4f0 00 5a 00 00 00 b3 c1 1c")!!

        assertEquals("4F0", frame.id)
        assertEquals(0xB3, frame.bytes[5])
    }

    @Test
    fun `service lines are refused`() {
        assertNull(MonitorLineParser.parse(""))
        assertNull(MonitorLineParser.parse("   "))
        assertNull(MonitorLineParser.parse("SEARCHING..."))
        assertNull(MonitorLineParser.parse("STOPPED"))
        assertNull(MonitorLineParser.parse("NO DATA"))
    }

    /**
     * Відкидати зіпсований байт не можна: усі наступні зсуви поїхали б на один,
     * і замість «немає даних» вийшов би тихо неправильний пробіг.
     */
    @Test
    fun `a line with a corrupted byte is refused whole`() {
        assertNull(MonitorLineParser.parse("4F0 00 ZZ 00 00 00 B3 C1 1C"))
        assertNull(MonitorLineParser.parse("4F0 00 5A 00 00 00 B3 C1 1"))
    }

    /** Якщо в адаптері вимкнені пробіли (AT S0), кадр приходить одним словом. */
    @Test
    fun `parses a glued line with the spaces switched off`() {
        val frame = MonitorLineParser.parse("4F0005A000000B3C11C")!!

        assertEquals("4F0", frame.id)
        assertEquals(8, frame.bytes.size)
        assertEquals(0x5A, frame.bytes[1])
        assertEquals(0xB3, frame.bytes[5])
        assertEquals(0x1C, frame.bytes[7])
    }

    @Test
    fun `a word that is not a frame stays refused`() {
        assertNull(MonitorLineParser.parse("BUFFERFULL"))
        assertNull(MonitorLineParser.parse("STOPPED"))
    }
}
