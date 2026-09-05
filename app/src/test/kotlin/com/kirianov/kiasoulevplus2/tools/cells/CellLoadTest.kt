package com.kirianov.kiasoulevplus2.tools.cells

import com.kirianov.kiasoulevplus2.Data.CellHealth
import com.kirianov.kiasoulevplus2.Data.CellSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellLoadTest {

    private val cells = 96

    /**
     * Пакет із однією слабкою коміркою. Усі комірки просідають від струму однаково,
     * а слабка — глибше рівно на своєму надлишковому опорі.
     */
    private fun sweepAt(
        currentA: Double,
        weakIndex: Int = -1,
        weakExtraMilliOhm: Double = 0.0,
        drift: Double = 0.0,
        atMs: Long = 0L,
    ): CellSweep {
        val common = 3.90 - currentA * 0.0002
        val voltages = (0 until cells).map { index ->
            if (index == weakIndex) common - currentA * weakExtraMilliOhm / 1000.0 else common
        }
        return CellSweep(
            voltages = voltages,
            currentBeforeA = currentA - drift / 2,
            currentAfterA = currentA + drift / 2,
            packVolts = voltages.sum(),
            atMs = atMs,
        )
    }

    private fun run(vararg currents: Double, weakIndex: Int = -1, extra: Double = 0.0) =
        CellLoad.summarize(
            currents.mapIndexed { i, a ->
                sweepAt(a, weakIndex, extra, atMs = i * 1000L)
            },
        )

    /** Заради чого все: слабка комірка мусить знайтися й бути названою. */
    @Test
    fun `the weak cell is found by how much deeper it sags`() {
        val result = run(0.0, 30.0, 60.0, 90.0, 120.0, weakIndex = 42, extra = 2.0)

        assertTrue(result.resistanceKnown)
        val weakest = result.weakest
        assertNotNull(weakest)
        assertEquals(42, weakest!!.index)
        assertEquals("надлишковий опір мав вийти саме тим, що заклали", 2.0, weakest.excessMilliOhm!!, 0.05)
    }

    /** Здорові комірки мусять лишитися біля нуля, інакше «слабких» будуть усі. */
    @Test
    fun `healthy cells come out near zero`() {
        val result = run(0.0, 30.0, 60.0, 90.0, 120.0, weakIndex = 42, extra = 2.0)

        val healthy = result.cells.filter { it.index != 42 }
        assertTrue(healthy.all { kotlin.math.abs(it.excessMilliOhm ?: 99.0) < 0.1 })
    }

    /**
     * ГОЛОВНЕ ПРО ЧЕСНІСТЬ. Натиснув «старт», постояв, натиснув «стоп» — струм не
     * мінявся. Намалювати різнокольорові комірки в цьому випадку було б гірше, ніж
     * не намалювати нічого.
     */
    @Test
    fun `standing still gives no verdict about resistance`() {
        val result = run(2.0, 2.5, 2.0, 1.8, 2.2, weakIndex = 42, extra = 2.0)

        assertFalse(result.resistanceKnown)
        assertTrue("причина має бути названа", result.note.isNotEmpty())
        assertTrue("а напруги лишаються", result.hasCells)
        assertNull(result.cells[42].excessMilliOhm)
    }

    /** Кількох проходів теж мало: пряму по двох точках із шумом будувати нема сенсу. */
    @Test
    fun `too few sweeps give no verdict either`() {
        val result = run(0.0, 120.0, weakIndex = 42, extra = 2.0)

        assertFalse(result.resistanceKnown)
        assertTrue(result.note.contains("Проходів"))
    }

    /**
     * Прохід, під час якого струм поїхав, у пряму не входить: комірки з початку й
     * кінця такого проходу бачили різне навантаження.
     */
    @Test
    fun `a sweep with the load changing under it is left out of the fit`() {
        val good = listOf(0.0, 40.0, 80.0, 120.0).mapIndexed { i, a ->
            sweepAt(a, weakIndex = 42, weakExtraMilliOhm = 2.0, atMs = i * 1000L)
        }
        val shaky = sweepAt(200.0, weakIndex = 42, weakExtraMilliOhm = 2.0, drift = 60.0, atMs = 9_000L)

        val result = CellLoad.summarize(good + shaky)

        assertEquals("хиткий прохід не мав потрапити в сталі", 4, result.steadySweeps)
        assertEquals(5, result.sweeps)
        assertEquals(2.0, result.weakest!!.excessMilliOhm!!, 0.05)
    }

    /**
     * Мінімум напруги — теж корисне число, просто інше. Воно лишається навіть тоді,
     * коли про опір сказати нічого.
     */
    @Test
    fun `the lowest voltage of the test is always reported`() {
        val result = run(0.0, 120.0, weakIndex = 42, extra = 2.0)

        val weak = result.cells[42]
        assertEquals(3.876 - 0.24, weak.minVolts, 0.001)
        assertTrue("і найглибше відхилення від середнього", weak.worstDeviationVolts < 0.0)
    }

    /** Середня потужність за тест — те, чим його потім можна порівняти з іншим. */
    @Test
    fun `the average power of the test is reported`() {
        val result = run(0.0, 100.0, 100.0, 100.0, 100.0)

        // Чотири проходи по 100 А при ~374 В і один при нулі.
        assertEquals(29.9, result.averagePowerKw, 0.5)
    }

    /**
     * Висновок «слабка / критична» ухвалює модель, а не екран. Де проходить межа —
     * питання про батарею; екранові лишається обрати колір.
     */
    @Test
    fun `the model decides which cells are weak, not the screen`() {
        val result = CellLoad.summarize(
            listOf(0.0, 30.0, 60.0, 90.0, 120.0).mapIndexed { i, a ->
                val voltages = (0 until cells).map { index ->
                    val extra = when (index) {
                        10 -> 0.8   // слабша за середню
                        20 -> 2.5   // помітно слабша
                        else -> 0.0
                    }
                    3.90 - a * 0.0002 - a * extra / 1000.0
                }
                CellSweep(voltages, a, a, voltages.sum(), i * 1000L)
            },
        )

        assertEquals(CellHealth.Weak, result.cells[10].health)
        assertEquals(CellHealth.Critical, result.cells[20].health)
        assertEquals(CellHealth.Normal, result.cells[0].health)
    }

    /** Без розмаху струму здоровою не оголошуємо нікого: сказати нічого не можна. */
    @Test
    fun `without a spread of current no cell is called healthy`() {
        val result = run(2.0, 2.5, 2.0, 1.8, 2.2)

        assertTrue(result.cells.all { it.health == CellHealth.Unknown })
    }

    /** Порожній тест не має вигадувати результатів. */
    @Test
    fun `an empty test says so`() {
        val result = CellLoad.summarize(emptyList())

        assertFalse(result.hasCells)
        assertFalse(result.resistanceKnown)
        assertTrue(result.note.isNotEmpty())
    }

    /**
     * Знак струму в цьому авто виявився неоднозначним: додатний траплявся і на
     * розгоні, і на зарядці. Тест не має від нього залежати взагалі.
     */
    @Test
    fun `the sign of the current does not change the answer`() {
        val positive = run(0.0, 30.0, 60.0, 90.0, 120.0, weakIndex = 42, extra = 2.0)
        val negative = CellLoad.summarize(
            listOf(0.0, -30.0, -60.0, -90.0, -120.0).mapIndexed { i, a ->
                sweepAt(kotlin.math.abs(a), weakIndex = 42, weakExtraMilliOhm = 2.0, atMs = i * 1000L)
                    .copy(currentBeforeA = a, currentAfterA = a)
            },
        )

        assertEquals(
            positive.weakest!!.excessMilliOhm!!,
            negative.weakest!!.excessMilliOhm!!,
            0.05,
        )
    }
}
