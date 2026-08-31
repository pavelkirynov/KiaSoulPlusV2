package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorLineParserTest {

    @Test
    fun `parses a normal monitor line`() {
        val frame = MonitorLineParser.parse("4F0 00 5A 00 00 00 B3 C1 1C", "4F0")!!

        assertEquals("4F0", frame.id)
        assertEquals(8, frame.bytes.size)
        assertEquals(0x5A, frame.bytes[1])
        assertEquals(0x1C, frame.bytes[7])
    }

    /**
     * Реальний рядок із машини: адаптер віддав кадр без ID. Раніше такий рядок
     * або відкидався, або, гірше, вгадувався як чужий кадр.
     */
    @Test
    fun `a headerless line belongs to the filtered id`() {
        val frame = MonitorLineParser.parse("00 00 6E 00 00 B3 C1 1C", "4F0")!!

        assertEquals("4F0", frame.id)
        assertEquals(0xB3, frame.bytes[5])
        assertEquals(0x1C, frame.bytes[7])
    }

    /**
     * Регресія на вгадування: «00 00 01 FF FD 07 40 44» — це дані, а не кадр 1FF.
     * Стара евристика робила з нього неіснуючий ID і псувала показники.
     */
    @Test
    fun `a data line is not turned into a made-up id`() {
        val frame = MonitorLineParser.parse("00 00 01 FF FD 07 40 44", "4F0")!!

        assertEquals("4F0", frame.id)
        assertEquals(listOf(0, 0, 1, 0xFF, 0xFD, 7, 0x40, 0x44), frame.bytes)
    }

    /** Дешеві клони розбивають ID на два байти з паддінгом. */
    @Test
    fun `unpads an id split across two bytes by cheap clones`() {
        val frame = MonitorLineParser.parse("00 00 06 53 00 00 00 00 00 A0 00 00", "653")!!

        assertEquals("653", frame.id)
        assertEquals(0xA0, frame.bytes[5])
    }

    /**
     * Явний ID важливіший за фільтр: його повідомив сам адаптер, тут нема чого гадати.
     * Фільтр потрібен лише рядкам, у яких ID немає. Кадр не того ID відсіє декодер.
     */
    @Test
    fun `an explicit id wins over the filter`() {
        val frame = MonitorLineParser.parse("653 00 00 00 00 00 A0 00 00", "4F0")!!

        assertEquals("653", frame.id)
        assertEquals(0xA0, frame.bytes[5])
    }

    @Test
    fun `lowercase bytes are accepted`() {
        val frame = MonitorLineParser.parse("4f0 00 5a 00 00 00 b3 c1 1c", "4F0")!!

        assertEquals("4F0", frame.id)
        assertEquals(0xB3, frame.bytes[5])
    }

    /** Усе, що адаптер пише про себе, а не про шину. */
    @Test
    fun `service lines are refused`() {
        assertNull(MonitorLineParser.parse("", "4F0"))
        assertNull(MonitorLineParser.parse("   ", "4F0"))
        assertNull(MonitorLineParser.parse("SEARCHING...", "4F0"))
        assertNull(MonitorLineParser.parse("STOPPED", "4F0"))
        assertNull(MonitorLineParser.parse("NO DATA", "4F0"))
        assertNull(MonitorLineParser.parse("BUFFER FULL", "4F0"))
        assertNull(MonitorLineParser.parse("FF 03 E1 03 00 00 11 10 <DATA ERROR", "4F0"))
        assertNull(MonitorLineParser.parse("0: 00 00 00 00 80 4B EB", "4F0"))
    }

    /**
     * Відкидати зіпсований байт поодинці не можна: усі наступні зсуви поїхали б
     * на один, і замість «немає даних» вийшов би тихо неправильний пробіг.
     */
    @Test
    fun `a line with a corrupted byte is refused whole`() {
        assertNull(MonitorLineParser.parse("4F0 00 ZZ 00 00 00 B3 C1 1C", "4F0"))
        assertNull(MonitorLineParser.parse("4F0 00 5A 00 00 00 B3 C1 1", "4F0"))
    }

    /** Кадр не може бути довшим за 8 байтів: довший рядок — це два злиплих. */
    @Test
    fun `a line longer than one frame is refused`() {
        assertNull(MonitorLineParser.parse("00 5A 00 00 00 B3 C1 1C 00 5A", "4F0"))
    }

    /** Якщо в адаптері вимкнені пробіли (AT S0), кадр приходить одним словом. */
    @Test
    fun `parses a glued line with the spaces switched off`() {
        val frame = MonitorLineParser.parse("4F0005A000000B3C11C", "4F0")!!

        assertEquals("4F0", frame.id)
        assertEquals(8, frame.bytes.size)
        assertEquals(0x5A, frame.bytes[1])
        assertEquals(0xB3, frame.bytes[5])
        assertEquals(0x1C, frame.bytes[7])
    }

    /** Без відомого фільтра лишається тільки те, що має ID явно. */
    @Test
    fun `without an expected id only explicit ids are accepted`() {
        assertEquals("4F0", MonitorLineParser.parse("4F0 00 5A 00 00 00 B3 C1 1C")?.id)
        assertNull(MonitorLineParser.parse("00 5A 00 00 00 B3 C1 1C"))
    }
}
