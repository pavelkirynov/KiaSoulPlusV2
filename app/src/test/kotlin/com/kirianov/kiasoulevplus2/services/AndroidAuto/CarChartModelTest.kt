package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.BatteryCurve
import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CurvePoint
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VoltagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarChartModelTest {

    private fun curveOf(vararg pairs: Pair<Double, Double>, measured: Boolean = true) = BatteryCurve(
        points = pairs.map { CurvePoint(socPercent = it.first, energyKwh = it.second, measured = measured) },
        totalKwh = 50.88,
        coveredPercent = 65.0,
        samples = 12,
    )

    /**
     * Головне про цю картинку: відсоток іде СПРАВА НАЛІВО, сто ліворуч. Так
     * прийнято малювати розрядні криві, і так само зроблено на екрані телефона —
     * дві різні картинки однієї величини були б гірші за відсутність другої.
     */
    @Test
    fun `the scale runs from full on the left to empty on the right`() {
        val chart = CarChartModel.curve(curveOf(0.0 to 0.0, 100.0 to 50.0))

        val points = chart.series.first().points
        assertEquals("нуль відсотків — правий край", 1.0, points.first().x, 1e-9)
        assertEquals("сто відсотків — лівий край", 0.0, points.last().x, 1e-9)
    }

    /** Висота кривої міряється повною ємністю, інакше форму не порівняти між замірами. */
    @Test
    fun `the curve is scaled by the full capacity`() {
        val chart = CarChartModel.curve(curveOf(0.0 to 0.0, 50.0 to 25.44))

        assertEquals(0.5, chart.series.first().points.last().y, 1e-6)
    }

    /**
     * Доведена ділянка малюється пунктиром окремою кривою. Показувати її так само,
     * як зміряну, означало б брехати про те, чого ми не знаємо.
     */
    @Test
    fun `the part that was only inferred is drawn apart and dashed`() {
        val curve = BatteryCurve(
            points = listOf(
                CurvePoint(0.0, 0.0, measured = false),
                CurvePoint(25.0, 12.0, measured = false),
                CurvePoint(50.0, 25.0, measured = true),
                CurvePoint(100.0, 50.0, measured = true),
            ),
            totalKwh = 50.0,
        )

        val chart = CarChartModel.curve(curve)

        val dashed = chart.series.single { it.dashed }
        assertEquals(2, dashed.points.size)
    }

    /** Крива напруги живе на власній вертикалі: інакше її форму не побачити. */
    @Test
    fun `the voltage curve gets the right hand axis`() {
        val curve = curveOf(0.0 to 0.0, 100.0 to 50.0).copy(
            voltagePoints = listOf(
                VoltagePoint(socPercent = 10.0, volts = 330.0),
                VoltagePoint(socPercent = 90.0, volts = 400.0),
            ),
        )

        val chart = CarChartModel.curve(curve)

        val volts = chart.series.single { it.axis == ChartSeries.Axis.Right }
        assertEquals(2, volts.points.size)
        assertTrue("підписи вольтів мають бути праворуч", chart.rightTicks.isNotEmpty())
    }

    /** Без напруги правої осі немає взагалі: порожня вісь лише з'їдає ширину. */
    @Test
    fun `without voltage there is no right hand axis`() {
        val chart = CarChartModel.curve(curveOf(0.0 to 0.0, 100.0 to 50.0))

        assertTrue(chart.rightTicks.isEmpty())
        assertEquals("", chart.rightUnit)
    }

    /**
     * Порожнє полотно мусить сказати, чого чекає. Картинка без жодного слова
     * читається як несправність, а не як «замірів ще немає».
     */
    @Test
    fun `an empty curve explains itself instead of drawing nothing`() {
        val chart = CarChartModel.curve(BatteryCurve())

        assertFalse(chart.hasCurves)
        assertTrue(chart.message.isNotEmpty())
    }

    /** Підпис каже, ємність зміряна чи поки прийнята на віру. */
    @Test
    fun `the subtitle says whether the capacity was measured`() {
        val axiom = CarChartModel.curve(curveOf(0.0 to 0.0, 100.0 to 50.0))
        val measured = CarChartModel.curve(
            curveOf(0.0 to 0.0, 100.0 to 50.0).copy(totalMeasured = true),
        )

        // «Прийнято» звідси прибрано навмисно: у застосунку про зарядки це слово
        // означає «отримано в батарею», і рядок «51.9 кВт·год прийнято» на машинному
        // екрані читався саме так — ніби стільки щойно зарядили.
        assertTrue(axiom.subtitle.contains("за паспортом"))
        assertFalse("це слово плутає з отриманою енергією", axiom.subtitle.contains("прийнято"))
        assertTrue(measured.subtitle.contains("виміряна зарядкою"))
    }

    /** Заряд великим: береться реальний відсоток, коли прогноз його вже знає. */
    @Test
    fun `the charge picture prefers the real percent`() {
        val state = State(bms = BmsData(displaySoc = 95.0))

        val chart = CarChartModel.gauge(state)

        assertEquals("95 %", chart.title)
        assertTrue(chart.subtitle.contains("панел"))
    }

    /** Кожна картинка зі списку мусить будуватися: список і є меню на екрані. */
    @Test
    fun `every offered picture can actually be built`() {
        CarChartModel.PICTURES.forEach { (id, _) ->
            val chart = CarChartModel.chartFor(id, State())
            assertTrue("картинка $id має мати заголовок", chart.title.isNotEmpty())
        }
    }
}
