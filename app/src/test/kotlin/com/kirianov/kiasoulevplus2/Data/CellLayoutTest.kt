package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок комірок у блоці — не порядок опитування. Друга збірка йде знизу вгору,
 * тому на екрані вона розвертається: ліворуч усюди верх стопки.
 */
class CellLayoutTest {

    /** Саме ці два рядки й називав власник: «1-7, 14-8, 15-19, 24-20». */
    @Test
    fun `the first blocks are laid out the way the pack is built`() {
        val rows = CellLayout.rows().map { row -> row.map { it + 1 } }

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), rows[0])
        assertEquals(listOf(14, 13, 12, 11, 10, 9, 8), rows[1])
        assertEquals(listOf(15, 16, 17, 18, 19), rows[2])
        assertEquals(listOf(24, 23, 22, 21, 20), rows[3])
    }

    /** Жодної комірки не загублено й не намальовано двічі. */
    @Test
    fun `every cell appears exactly once`() {
        val all = CellLayout.rows().flatten()

        assertEquals(CellData.TOTAL_CELLS, all.size)
        assertEquals((0 until CellData.TOTAL_CELLS).toList(), all.sorted())
    }

    /** Ряди по сім і по п'ять чергуються парами — так само, як блоки 14 і 10. */
    @Test
    fun `rows come in pairs of equal length`() {
        val lengths = CellLayout.rows().map { it.size }

        assertEquals(
            listOf(7, 7, 5, 5, 5, 5, 7, 7, 7, 7, 5, 5, 5, 5, 7, 7),
            lengths,
        )
    }

    // --- Розкладка «блоки 6s» --------------------------------------------------

    /** Шістнадцять модулів по шість, і кожна комірка рівно в одному з них. */
    @Test
    fun `the six cell layout covers every cell exactly once`() {
        val rows = CellLayout.sixCellRows()

        assertEquals(8, rows.size)
        assertTrue("у кожному рядку два модулі", rows.all { it.size == 2 })
        assertTrue("у кожному модулі шість комірок", rows.all { row -> row.all { it.size == 6 } })

        val all = rows.flatten().flatten()
        assertEquals(96, all.size)
        assertEquals((0 until 96).toSet(), all.toSet())
    }

    /**
     * Ліва колонка згори вниз — це модулі 1-8 у порядку опитування.
     *
     * Це половина розкладки, яку легко переплутати з простою сіткою: вона й
     * справді така сама. Уся різниця в правій колонці.
     */
    @Test
    fun `the left column runs top down in polling order`() {
        val rows = CellLayout.sixCellRows()

        assertEquals(listOf(0, 1, 2, 3, 4, 5), rows.first()[0])
        assertEquals(listOf(42, 43, 44, 45, 46, 47), rows.last()[0])
    }

    /**
     * ПРАВА КОЛОНКА ЧИТАЄТЬСЯ ЗНИЗУ ВГОРУ, і всередині модуля теж.
     *
     * Так модулі стоять у машині: нумерація обходить пакет змійкою, і намальована
     * підряд права половина була б перевернутою — тобто показувала б просідання
     * не з того боку, з якого воно є.
     */
    @Test
    fun `the right column runs bottom up and is mirrored inside`() {
        val rows = CellLayout.sixCellRows()

        // Найвищий правий модуль — останній у пакеті, комірки 96..91.
        assertEquals(listOf(95, 94, 93, 92, 91, 90), rows.first()[1])
        // Найнижчий правий — дев'ятий, комірки 54..49.
        assertEquals(listOf(53, 52, 51, 50, 49, 48), rows.last()[1])
    }

    /** Змійка не рветься: сусідні кінці колонок — сусідні комірки. */
    @Test
    fun `the snake closes at the bottom`() {
        val rows = CellLayout.sixCellRows()
        val leftEnd = rows.last()[0].last()
        val rightStart = rows.last()[1].last()

        assertEquals("низ лівої колонки й низ правої мусять бути сусідами", leftEnd + 1, rightStart)
    }
}
