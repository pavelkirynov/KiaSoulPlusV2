package com.kirianov.kiasoulevplus2.tools.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityModelTest {

    /**
     * Батарея з відомою ємністю. Модель має знайти саме її, а не паспортні 27 кВт·год:
     * у цьому й уся суть — показувати ємність **цього** пакета, а не нового.
     */
    @Test
    fun `learns the real capacity of a worn battery`() {
        val model = CapacityModel()
        val trueCapacityKwh = 22.0

        // Десяток заїздів по різних частинах шкали.
        sessions().forEachIndexed { index, (from, to) ->
            val energy = trueCapacityKwh * (from - to) / 100.0
            model.learn(from, to, energy, atMs = index * DAY_MS)
        }

        // 60 % шкали мають важити 60 % ємності.
        assertEquals(trueCapacityKwh * 0.6, model.energyBetween(20.0, 80.0), 0.8)
        assertEquals(trueCapacityKwh / 100.0, model.kwhPerPercentAt(50.0), 0.02)
    }

    /** Заряджання — те саме спостереження, тільки з іншим знаком і чистіше. */
    @Test
    fun `a charging session teaches capacity just as a drive does`() {
        val model = CapacityModel()
        val trueCapacityKwh = 24.0

        repeat(8) { index ->
            // SOC росте, енергія від'ємна: обидві величини міняють знак разом.
            val received = -trueCapacityKwh * 0.5
            model.learn(20.0, 70.0, received, atMs = index * DAY_MS)
        }

        assertEquals(trueCapacityKwh / 100.0, model.kwhPerPercentAt(50.0), 0.03)
    }

    /** Дрібний крок SOC не годиться: на ньому шум більший за сигнал. */
    @Test
    fun `a tiny slice of the scale is refused`() {
        val model = CapacityModel()

        assertFalse(model.learn(60.0, 58.0, 0.5, atMs = 0L))
        assertTrue(model.learn(60.0, 45.0, 3.6, atMs = 0L))
    }

    /** Енергія і SOC мусять рухатися в один бік: інакше це збій читання. */
    @Test
    fun `energy and charge moving opposite ways is a broken reading`() {
        val model = CapacityModel()

        assertFalse("SOC упав, а енергія прибула", model.learn(60.0, 40.0, -5.0, atMs = 0L))
    }

    /**
     * Панель бреше на краях шкали, і модель має вирахувати, наскільки саме.
     * Пари подаються за законом display = 1.05·precise − 4.2, тобто нуль на панелі
     * настає, коли в батареї ще є чотири відсотки.
     */
    @Test
    fun `works out where the scale really ends`() {
        val model = CapacityModel()

        var at = 0L
        (12..95 step 2).forEach { precise ->
            val display = 1.05 * precise - 4.2
            repeat(3) { model.learnBuffer(display, precise.toDouble(), at++) }
        }

        assertEquals("справжнє дно шкали", 4.0, model.floorSocPercent, 0.6)
        assertTrue("стеля має бути близько сотні", model.ceilingSocPercent > 95.0)
    }

    /** Краї шкали, де панель уперлася в 0 і 100, не мусять завалювати нахил. */
    @Test
    fun `the flat ends of the dial are ignored`() {
        val model = CapacityModel()

        var at = 0L
        // «Полички»: панель стоїть на 100, поки точний SOC іще росте.
        repeat(40) { model.learnBuffer(100.0, 96.0 + it * 0.1, at++) }
        repeat(40) { model.learnBuffer(0.0, 4.0 - it * 0.05, at++) }

        // Жодна з цих пар не мала потрапити в підгонку, тож лишилася фізика.
        assertEquals(CapacityModel.DEFAULT_FLOOR_PERCENT, model.floorSocPercent, 0.5)
    }

    /** Реальний відсоток міряє енергію, а не положення стрілки. */
    @Test
    fun `the real percentage counts energy above the real floor`() {
        val model = CapacityModel()

        assertEquals(100.0, model.realPercent(model.ceilingSocPercent), 0.5)
        assertEquals(0.0, model.realPercent(model.floorSocPercent), 0.5)
        // На дні шкали панельні відсотки закінчуються раніше за реальні нулі.
        assertTrue(model.realPercent(10.0) < 10.0)
        assertTrue(model.realPercent(50.0) in 40.0..55.0)
    }

    /** Сесія складає відрізки, поки SOC не пройде помітний шмат шкали. */
    @Test
    fun `a session gathers segments until the charge moved enough`() {
        val session = CapacitySession()
        val car = VirtualCar()

        var soc = 80.0
        var observation: CapacityObservation? = null
        repeat(12) { index ->
            val next = soc - 1.5
            val segment = car.segment(speedKmh = 60.0, atMs = index * 600_000L)
                .copy(socStartPercent = soc, socEndPercent = next)
            observation = observation ?: session.add(segment)
            soc = next
        }

        val result = requireNonNull(observation)
        assertEquals(80.0, result.socStartPercent, 1e-9)
        assertTrue("розмах мав перевищити поріг", result.socStartPercent - result.socEndPercent >= 8.0)
        assertTrue("енергія мала скластися з відрізків", result.energyKwh > 0.0)
    }

    private fun requireNonNull(observation: CapacityObservation?): CapacityObservation {
        assertTrue("сесія так і не закрилася", observation != null)
        return observation!!
    }

    private fun sessions() = listOf(
        90.0 to 60.0,
        75.0 to 40.0,
        95.0 to 55.0,
        60.0 to 25.0,
        85.0 to 30.0,
        70.0 to 35.0,
        100.0 to 65.0,
        50.0 to 15.0,
        80.0 to 45.0,
        65.0 to 20.0,
    )

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
