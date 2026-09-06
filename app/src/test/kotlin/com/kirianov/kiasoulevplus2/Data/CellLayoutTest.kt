package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
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
}
