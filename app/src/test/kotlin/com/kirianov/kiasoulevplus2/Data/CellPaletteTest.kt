package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Комірку фарбує не значення, а відхилення від решти пакета — і пороги в кожного
 * режиму свої, бо в спокої комірки розходяться на одиниці мілівольт, а під струмом
 * на десятки. Один порог на всі режими або пофарбував би все, або нічого.
 */
class CellPaletteTest {

    private val palette = CellPalette(warnAt = 15.0, badAt = 30.0)

    @Test
    fun `three bands, two thresholds`() {
        assertEquals(CellLevel.Normal, palette.levelOf(0.0))
        assertEquals(CellLevel.Normal, palette.levelOf(14.9))
        assertEquals(CellLevel.Warn, palette.levelOf(15.0))
        assertEquals(CellLevel.Warn, palette.levelOf(29.9))
        assertEquals(CellLevel.Bad, palette.levelOf(30.0))
        assertEquals(CellLevel.Bad, palette.levelOf(500.0))
    }

    /**
     * Порядок порогів не гарантований: у полі введення можна набрати «погано» менше
     * за «підозра». Плутати їх не можна — вийшло б, що весь пакет червоний.
     */
    @Test
    fun `swapped thresholds still work`() {
        val swapped = CellPalette(warnAt = 30.0, badAt = 15.0)

        assertEquals(CellLevel.Normal, swapped.levelOf(10.0))
        assertEquals(CellLevel.Warn, swapped.levelOf(20.0))
        assertEquals(CellLevel.Bad, swapped.levelOf(40.0))
    }

    /** Нечисло нічого не фарбує: «не міряли» — не те саме, що «погано». */
    @Test
    fun `a missing value paints nothing`() {
        assertEquals(CellLevel.Normal, palette.levelOf(Double.NaN))
    }

    /** У спокої й під навантаженням пороги мусять бути різні, і типові теж. */
    @Test
    fun `resting and loaded thresholds differ by default`() {
        val palettes = CellPalettes()

        assertEquals(palettes.rest, palettes.of(CellValueMode.Rest))
        assertEquals(palettes.underLoad, palettes.of(CellValueMode.UnderLoad))
        org.junit.Assert.assertTrue(
            "під струмом просадка більша, порог мусить бути вищим",
            palettes.underLoad.high > palettes.rest.high,
        )
    }

    /** Зміна порога одного режиму не чіпає решту. */
    @Test
    fun `changing one mode leaves the others alone`() {
        val palettes = CellPalettes()
        val changed = palettes.with(CellValueMode.Rest, CellPalette(warnAt = 5.0, badAt = 9.0))

        assertEquals(CellPalette(warnAt = 5.0, badAt = 9.0), changed.of(CellValueMode.Rest))
        assertEquals(palettes.underLoad, changed.underLoad)
        assertEquals(palettes.resistance, changed.resistance)
    }

    /** Опір міряється в мілоомах, решта — в мілівольтах. */
    @Test
    fun `the unit follows the mode`() {
        val palettes = CellPalettes()

        assertEquals("мОм", palettes.unitOf(CellValueMode.Resistance))
        assertEquals("мВ", palettes.unitOf(CellValueMode.Rest))
    }
}
