package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDiffTest {

    private fun frame(vararg bytes: Int) = bytes.toList()

    /**
     * Заради чого все й зроблено: знайти один біт, який перемкнувся від
     * запалювання. Знайти його треба серед кадрів, які стоять, — і саме тиша
     * навколо робить зміну помітною.
     */
    @Test
    fun `a single flipped bit is found among frames that stand still`() {
        val off = mapOf(
            "4F0" to frame(0x00, 0x00, 0x00, 0x00),
            "200" to frame(0x11, 0x22, 0x33),
            "553" to frame(0x04, 0x00),
        )
        val ready = mapOf(
            "4F0" to frame(0x00, 0x00, 0x00, 0x00),
            "200" to frame(0x11, 0x22, 0x33),
            "553" to frame(0x06, 0x00),
        )

        val changes = FrameDiff.compare(off, ready)

        assertEquals("змінитися мав рівно один кадр", 1, changes.size)
        val frame = changes.single()
        assertEquals("553", frame.id)
        val byte = frame.changes.single()
        assertEquals(0, byte.index)
        assertEquals(listOf(1), byte.changedBits)
    }

    /** Опис для екрана має читатися без розшифровки. */
    @Test
    fun `the change describes itself in hex and bits`() {
        val changes = FrameDiff.compare(
            mapOf("553" to frame(0x00, 0x04)),
            mapOf("553" to frame(0x00, 0x06)),
        )

        assertEquals("B1  04 → 06  біти 1", changes.single().changes.single().describe())
    }

    /**
     * Кадр, якого немає в одному зі знімків, — це «не застали», а не «змінилося».
     * Вікно вільного прослуховування коротке, і плутати ці дві звістки означало б
     * потонути в хибних слідах.
     */
    @Test
    fun `a frame seen in only one snapshot is reported apart`() {
        val changes = FrameDiff.compare(
            mapOf("4F0" to frame(0x01)),
            mapOf("4F0" to frame(0x01), "653" to frame(0x02)),
        )

        val single = changes.single()
        assertEquals("653", single.id)
        assertTrue(single.onlyInOne)
        assertTrue("розбирати такий кадр нема з чим", single.changes.isEmpty())
    }

    /** Кадри без змін не повертаються взагалі: на шині їх десятки. */
    @Test
    fun `frames that did not move are left out`() {
        val same = mapOf("4F0" to frame(1, 2, 3), "200" to frame(9))

        assertTrue(FrameDiff.compare(same, same).isEmpty())
    }

    /** Кадр змінив довжину: розбираємо спільну частину й кажемо про довжину окремо. */
    @Test
    fun `a frame that changed length still reports its common bytes`() {
        val changes = FrameDiff.compare(
            mapOf("200" to frame(0x01, 0x02)),
            mapOf("200" to frame(0x03, 0x02, 0x07)),
        )

        val frame = changes.single()
        assertTrue(frame.lengthChanged)
        assertEquals(1, frame.changes.size)
        assertEquals(0, frame.changes.single().index)
    }

    @Test
    fun `every differing bit is listed from the low end up`() {
        assertEquals(listOf(0, 7), FrameDiff.changedBits(0x00, 0x81))
        assertEquals(emptyList<Int>(), FrameDiff.changedBits(0x5A, 0x5A))
    }
}
