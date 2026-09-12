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

    // --- Розкладка «модулі 6s» -------------------------------------------------

    /** Номери на екрані, з одиниці: так їх і читає власник. */
    private fun screen(): List<List<List<Int>>> =
        CellLayout.sixCellRows().map { row -> row.map { module -> module.map { it + 1 } } }

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
     * ЛІВА КОЛОНКА, ЯК ЇЇ НАЗВАВ ВЛАСНИК: «6-1, 7-12, 18-13… останній 43-48».
     *
     * Напрямок нумерації всередині модуля чергується, бо міжмодульний провід іде
     * то по лівому боку стопки, то по правому. Намальована підряд сітка бреше в
     * половині рядів — і саме ці ряди тут і перевіряються.
     */
    @Test
    fun `the left column alternates direction row by row`() {
        val rows = screen()

        assertEquals(listOf(6, 5, 4, 3, 2, 1), rows[0][0])
        assertEquals(listOf(7, 8, 9, 10, 11, 12), rows[1][0])
        assertEquals(listOf(18, 17, 16, 15, 14, 13), rows[2][0])
        assertEquals(listOf(19, 20, 21, 22, 23, 24), rows[3][0])
        assertEquals(listOf(43, 44, 45, 46, 47, 48), rows[7][0])
    }

    /**
     * ПРАВА КОЛОНКА ЙДЕ ЗНИЗУ ВГОРУ: «49-54» знизу, «96-91» зверху.
     *
     * Ланцюг перескакує з нижнього лівого модуля в нижній правий і піднімається
     * назад — тому верхній правий модуль останній у пакеті, а не дев'ятий.
     */
    @Test
    fun `the right column runs bottom up`() {
        val rows = screen()

        assertEquals(listOf(49, 50, 51, 52, 53, 54), rows[7][1])
        assertEquals(listOf(60, 59, 58, 57, 56, 55), rows[6][1])
        assertEquals(listOf(85, 86, 87, 88, 89, 90), rows[1][1])
        assertEquals(listOf(96, 95, 94, 93, 92, 91), rows[0][1])
    }

    /**
     * ЗМІЙКА НЕ РВЕТЬСЯ — головна перевірка всієї розкладки.
     *
     * Сусідні номери мусять бути сусідніми й на екрані: або одне під одним у тій
     * самій колонці, або поруч на переході між колонками. Якщо десь напрямок
     * модуля переставлено, ця перевірка це й покаже.
     */
    @Test
    fun `consecutive cells are neighbours on the screen`() {
        val rows = screen()

        // Перехід між рядами лівої колонки: кінці стоять один під одним.
        (0 until 7).forEach { row ->
            val below = rows[row + 1][0]
            assertTrue(
                "ряд $row лівої колонки не стикується з наступним",
                rows[row][0].first() + 1 == below.first() || rows[row][0].last() + 1 == below.last(),
            )
        }
        // Перехід між колонками: 48 знизу ліворуч, 49 знизу праворуч.
        assertEquals(rows[7][0].last() + 1, rows[7][1].first())
        // Перехід між рядами правої колонки, знизу вгору.
        (7 downTo 1).forEach { row ->
            val above = rows[row - 1][1]
            assertTrue(
                "ряд $row правої колонки не стикується з тим, що вище",
                rows[row][1].first() + 1 == above.first() || rows[row][1].last() + 1 == above.last(),
            )
        }
    }
}
