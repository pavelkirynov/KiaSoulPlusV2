package com.kirianov.kiasoulevplus2.tools.frames

import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BusChangeLogTest {

    private fun frame(id: String, vararg bytes: Int) = CanBroadcastFrame(id, bytes.toList())

    /** Перша поява кожного ID — це подія: раніше його не бачили. */
    @Test
    fun `first sight of each id is an event`() {
        val result = BusChangeLog.fold(
            seen = emptyMap(),
            frames = listOf(frame("111", 1, 2), frame("222", 3)),
            atMs = 100L,
        )

        assertEquals(2, result.events.size)
        assertEquals("111", result.events[0].id)
        assertEquals(listOf(1, 2), result.events[0].bytes)
        assertEquals(100L, result.events[0].atMs)
        assertEquals(mapOf("111" to listOf(1, 2), "222" to listOf(3)), result.seen)
    }

    /** Той самий кадр із тими самими байтами події не породжує. */
    @Test
    fun `an unchanged frame is not an event`() {
        val first = BusChangeLog.fold(emptyMap(), listOf(frame("111", 1, 2)), 100L)
        val second = BusChangeLog.fold(
            first.seen,
            listOf(frame("111", 1, 2), frame("111", 1, 2)),
            200L,
        )

        assertTrue(second.events.isEmpty())
    }

    /**
     * ГОЛОВНЕ: змінені байти — це подія саме в ту мить. Так і виглядає натискання
     * кнопки: кадр був однаковий, і раптом смикнувся.
     */
    @Test
    fun `a changed frame is an event at that moment`() {
        val first = BusChangeLog.fold(emptyMap(), listOf(frame("433", 0x00)), 100L)
        val second = BusChangeLog.fold(first.seen, listOf(frame("433", 0x08)), 350L)

        assertEquals(1, second.events.size)
        assertEquals("433", second.events[0].id)
        assertEquals(listOf(0x08), second.events[0].bytes)
        assertEquals(350L, second.events[0].atMs)
    }

    /**
     * ОБРІЗОК — НЕ ЗМІНА. Той самий кадр, недочитаний монітором до 2 байтів, не
     * має рахуватися зміною проти повних 8; а повний після обрізка — теж ні.
     */
    @Test
    fun `a truncated frame is not a change`() {
        val full = BusChangeLog.fold(emptyMap(), listOf(frame("55D", 0xFF, 0x7F, 0xFF, 0xFF)), 100L)
        assertEquals(1, full.events.size)

        // Куций префікс тих самих байтів — не подія.
        val cut = BusChangeLog.fold(full.seen, listOf(frame("55D", 0xFF, 0x7F)), 200L)
        assertTrue(cut.events.isEmpty())
        // У памʼяті лишилася ПОВНІША версія.
        assertEquals(listOf(0xFF, 0x7F, 0xFF, 0xFF), cut.seen["55D"])

        // Повний кадр після куцого — теж не подія: дані ті самі.
        val backFull = BusChangeLog.fold(cut.seen, listOf(frame("55D", 0xFF, 0x7F, 0xFF, 0xFF)), 300L)
        assertTrue(backFull.events.isEmpty())
    }

    /** А ось справжня зміна байта під спільним префіксом — це подія. */
    @Test
    fun `a real change under the same length is an event`() {
        val first = BusChangeLog.fold(emptyMap(), listOf(frame("517", 0x00, 0x00)), 100L)
        val second = BusChangeLog.fold(first.seen, listOf(frame("517", 0x80, 0x00)), 200L)
        assertEquals(1, second.events.size)
        assertEquals(listOf(0x80, 0x00), second.events[0].bytes)
    }

    /** Порожня порція нічого не змінює й повертає той самий стан. */
    @Test
    fun `an empty batch returns the same state`() {
        val seen = mapOf("111" to listOf(1))
        val result = BusChangeLog.fold(seen, emptyList(), 100L)

        assertTrue(result.events.isEmpty())
        assertSame(seen, result.seen)
    }

    /** У межах однієї порції кадр може зʼявитися й одразу змінитися — це дві події. */
    @Test
    fun `two different payloads in one batch are two events`() {
        val result = BusChangeLog.fold(
            emptyMap(),
            listOf(frame("50", 0x01), frame("50", 0x02)),
            100L,
        )

        assertEquals(2, result.events.size)
        assertEquals(listOf(0x02), result.seen["50"])
    }
}
