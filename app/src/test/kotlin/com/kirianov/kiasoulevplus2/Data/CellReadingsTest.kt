package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Досі колір у сітці брався лише з вердикту тесту під навантаженням, і режими
 * «ХХ», «Відхилення» та «Ввід» стояли безбарвними — саме ті, в яких найчастіше й
 * дивляться. Тут перевіряється те, що їх фарбує: число режиму й його відхилення
 * від медіани пакета.
 */
class CellReadingsTest {

    private fun verdict(index: Int, rest: Double, min: Double = 0.0, ohm: Double? = null) =
        CellVerdict(
            index = index,
            restVolts = rest,
            excessMilliOhm = ohm,
            minVolts = min,
            worstDeviationVolts = 0.0,
        )

    private fun testWith(cells: List<CellVerdict>) =
        CellTestState(result = CellTestResult(cells = cells))

    /** Медіана непарної й парної кількості: половину пакета можна не міряти. */
    @Test
    fun `the median ignores what was not measured`() {
        assertEquals(3.0, CellReadings.medianOf(listOf(1.0, null, 3.0, null, 5.0))!!, 0.001)
        assertEquals(3.5, CellReadings.medianOf(listOf(1.0, 3.0, 4.0, 6.0))!!, 0.001)
        assertNull(CellReadings.medianOf(listOf(null, null)))
    }

    /**
     * ФАРБУЄ ВІДХИЛЕННЯ, А НЕ САМЕ ЧИСЛО. На повному заряді всі комірки біля
     * 4.1 В, на порожньому — біля 3.3, і однакова заливка на обох означала б
     * різні речі.
     */
    @Test
    fun `the level comes from the deviation, not the value`() {
        val palette = CellPalette(warnAt = 15.0, badAt = 30.0)

        assertEquals(CellLevel.Normal, CellReadings.levelOf(4100.0, 4100.0, palette))
        assertEquals(CellLevel.Normal, CellReadings.levelOf(3300.0, 3300.0, palette))
        assertEquals(CellLevel.Warn, CellReadings.levelOf(3280.0, 3300.0, palette))
        assertEquals(CellLevel.Bad, CellReadings.levelOf(3260.0, 3300.0, palette))
    }

    /** Комірка, що вибилася ВГОРУ, — теж розбаланс, і теж має фарбуватися. */
    @Test
    fun `a cell above the pack is flagged too`() {
        val palette = CellPalette(warnAt = 15.0, badAt = 30.0)

        assertEquals(CellLevel.Bad, CellReadings.levelOf(3340.0, 3300.0, palette))
    }

    /** Порожня клітинка не фарбується: «не міряли» — не норма й не провал. */
    @Test
    fun `a missing value is not painted`() {
        val palette = CellPalette(warnAt = 1.0, badAt = 2.0)

        assertEquals(CellLevel.Normal, CellReadings.levelOf(null, 3300.0, palette))
        assertEquals(CellLevel.Normal, CellReadings.levelOf(3300.0, null, palette))
    }

    /** Напруга спокою віддається в мілівольтах — у тих самих, що й пороги. */
    @Test
    fun `voltages come back in millivolts`() {
        val test = testWith(listOf(verdict(0, rest = 3.65, min = 3.41)))
        val cells = CellData()
        val manual = ManualCells()

        assertEquals(
            3650.0,
            CellReadings.valueOf(0, CellValueMode.Rest, cells, manual, test)!!,
            0.001,
        )
        assertEquals(
            3410.0,
            CellReadings.valueOf(0, CellValueMode.UnderLoad, cells, manual, test)!!,
            0.001,
        )
    }

    /** Опір лишається в мілоомах: перекладати його у вольти нема куди. */
    @Test
    fun `resistance stays in milliohms`() {
        val test = testWith(listOf(verdict(0, rest = 3.65, ohm = 0.42)))

        assertEquals(
            0.42,
            CellReadings.valueOf(0, CellValueMode.Resistance, CellData(), ManualCells(), test)!!,
            0.001,
        )
    }

    /**
     * У режимі введення число бере не тест: спершу прочитане з шини, а якщо його
     * немає — набране руками. Інакше сітка, заповнена вручну, лишалася б без кольору.
     */
    @Test
    fun `entry mode reads the bus first and the keyboard second`() {
        val cells = CellData(cellVoltages = listOf(3.70, 0.0))
        val manual = ManualCells(voltages = mapOf(1 to 3.55))
        val empty = CellTestState()

        assertEquals(3700.0, CellReadings.valueOf(0, CellValueMode.Entry, cells, manual, empty)!!, 0.001)
        assertEquals(3550.0, CellReadings.valueOf(1, CellValueMode.Entry, cells, manual, empty)!!, 0.001)
        assertNull(CellReadings.valueOf(2, CellValueMode.Entry, cells, manual, empty))
    }

    /** Рядок під сіткою рахує, скільки комірок вибилося за кожен порог. */
    @Test
    fun `the counts add up to the whole pack`() {
        // Медіана тут 3300: п'ять відомих чисел, і три з них однакові.
        val values = listOf(3300.0, 3300.0, 3300.0, 3280.0, 3250.0, null)
        val counts = CellReadings.countOf(values, CellPalette(warnAt = 15.0, badAt = 30.0))

        assertEquals(1, counts[CellLevel.Bad])
        assertEquals(1, counts[CellLevel.Warn])
        assertEquals(values.size, counts.values.sum())
    }

    /** Числа даються на весь пакет, а не лише на зміряні комірки. */
    @Test
    fun `the whole pack is always covered`() {
        val readings = CellReadings.allOf(
            CellValueMode.Rest,
            CellData(),
            ManualCells(),
            testWith(listOf(verdict(0, rest = 3.65))),
        )

        assertEquals(Pack.CELLS_IN_SERIES, readings.size)
        assertEquals(3650.0, readings.first()!!, 0.001)
        assertNull(readings.last())
    }

    /**
     * РЕГРЕСІЯ: збережений простий замір лишався безбарвним і порожнім.
     *
     * У такому записі є напруги, але немає тесту під навантаженням, а режим у нього
     * ставиться «ХХ». Без цієї підміни виходило, що дивишся на замір — і не бачиш
     * ні чисел, ні кольору.
     */
    @Test
    fun `resting voltages come from the snapshot when there is no load test`() {
        val cells = CellData(cellVoltages = listOf(3.70, 3.68))
        val restOnly = CellTestState(valueMode = CellValueMode.Rest)

        assertEquals(
            3700.0,
            CellReadings.valueOf(0, CellValueMode.Rest, cells, ManualCells(), restOnly)!!,
            0.001,
        )
    }

    /** А коли тест є, «ХХ» бере його число, а не те, що в полі. */
    @Test
    fun `a load test wins over the field in resting mode`() {
        val cells = CellData(cellVoltages = listOf(3.70))
        val test = testWith(listOf(verdict(0, rest = 3.61)))

        assertEquals(
            3610.0,
            CellReadings.valueOf(0, CellValueMode.Rest, cells, ManualCells(), test)!!,
            0.001,
        )
    }
}
