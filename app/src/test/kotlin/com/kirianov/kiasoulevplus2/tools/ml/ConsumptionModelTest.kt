package com.kirianov.kiasoulevplus2.tools.ml

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionModelTest {

    /**
     * Головний тест усієї витівки: чи знаходить модель ту закономірність, яка в
     * даних насправді закладена. Вигаданому авто задано відомі коефіцієнти, і після
     * тижня їзди модель має передбачати його витрату з точністю в кілька відсотків.
     */
    @Test
    fun `learns the real consumption of a known car`() {
        val car = VirtualCar()
        val model = ConsumptionModel()

        car.week(segments = 120).forEach(model::learn)

        listOf(40.0, 60.0, 90.0).forEach { speed ->
            val expected = car.trueWhPerKm(speed)
            val actual = model.predictWhPerKm(DriveConditions.steady(speed))
            val error = abs(actual - expected) / expected
            assertTrue(
                "на $speed км/год: очікували ${expected.toInt()}, отримали ${actual.toInt()} Вт·год/км",
                error < 0.06,
            )
        }
    }

    /** Вивчений постійний відбір має збігтися з тим, що закладали. */
    @Test
    fun `learns the constant draw of the car`() {
        val car = VirtualCar(auxKw = 1.20)
        val model = ConsumptionModel()

        car.week(segments = 150).forEach(model::learn)

        assertEquals(1.20, model.auxPowerKw, 0.25)
    }

    /**
     * Перший день без жодних даних: відповідає фізика. Число має бути не «якесь»,
     * а правдоподібним для цього авто — інакше перший запуск показав би дурницю.
     */
    @Test
    fun `answers sensibly before it has seen anything`() {
        val model = ConsumptionModel()

        val city = model.predictWhPerKm(DriveConditions.steady(60.0))
        val highway = model.predictWhPerKm(DriveConditions.steady(90.0))

        assertTrue("60 км/год: $city Вт·год/км", city in 110.0..150.0)
        assertTrue("90 км/год: $highway Вт·год/км", highway in 165.0..215.0)
        assertTrue("на трасі має бути дорожче", highway > city)
    }

    /** Мороз коштує енергії, і модель має це вивчити, а не списати на швидкість. */
    @Test
    fun `learns what the heater costs`() {
        val car = VirtualCar(heatingKw = 2.0)
        val model = ConsumptionModel()

        // Влітку і взимку упереміш: інакше обігрів не відділити від решти.
        val summer = car.week(segments = 80, ambientTempC = 22.0)
        val winter = car.week(segments = 80, ambientTempC = -5.0, startAtMs = 1_000_000L)
        (summer + winter).sortedBy { it.startedAtMs }.forEach(model::learn)

        val warm = model.predictWhPerKm(DriveConditions.steady(60.0, ambientTempC = 22.0))
        val cold = model.predictWhPerKm(DriveConditions.steady(60.0, ambientTempC = -5.0))

        assertTrue("на морозі має бути помітно дорожче: $warm проти $cold", cold > warm * 1.15)
        assertEquals(car.trueWhPerKm(60.0, -5.0), cold, cold * 0.08)
    }

    /**
     * Одне зіпсоване читання CAN не має права зсунути модель: інакше досить однієї
     * поганої секунди на шині, щоб запас ходу поїхав.
     */
    @Test
    fun `a single broken segment does not move the model`() {
        val car = VirtualCar()
        val model = ConsumptionModel()
        car.week(segments = 120).forEach(model::learn)

        val before = model.predictWhPerKm(DriveConditions.steady(60.0))
        val broken = car.segment(speedKmh = 60.0).copy(energyKwh = 40.0)
        model.learn(broken)
        val after = model.predictWhPerKm(DriveConditions.steady(60.0))

        assertEquals("викид мав бути відкинутий", before, after, 1e-9)
    }

    /** Заряджання — не їзда: у ньому нема руху, і вчитися витраті на ньому нема чого. */
    @Test
    fun `charging segments are not consumption data`() {
        val model = ConsumptionModel()
        val charging = VirtualCar().segment(speedKmh = 60.0).copy(charging = true, energyKwh = -5.0)

        assertNull(model.learn(charging))
    }

    /**
     * Затяжний спуск виглядає як безкоштовна їзда. Висоти застосунок не бачить, тож
     * такий відрізок треба відкинути, інакше модель вивчить, що машина не витрачає.
     */
    @Test
    fun `a long descent is not treated as free driving`() {
        val model = ConsumptionModel()
        val descent = VirtualCar().segment(speedKmh = 60.0).copy(tractionKwh = 1.0, regenKwh = 0.8)

        assertNull(model.learn(descent))
    }

    /** Передбачення робиться ДО навчання: інакше «помилка» була б самообманом. */
    @Test
    fun `the reported prediction is made before the answer is seen`() {
        val car = VirtualCar()
        val model = ConsumptionModel()
        car.week(segments = 40).forEach(model::learn)

        val next = car.segment(speedKmh = 70.0, atMs = 9_000_000L)
        val expectedBefore = model.predictPowerKw(DriveConditions.of(next))
        val reported = model.learn(next)

        assertNotNull(reported)
        assertEquals(expectedBefore, reported!!, 1e-9)
    }

    /**
     * Модель мусить знати, чого вона ще не знає. Сама лише траса не дає відділити
     * постійний відбір, і готовність має це показати чесно, а не бадьорою сотнею.
     */
    @Test
    fun `readiness stays low for what the data never showed`() {
        val car = VirtualCar()
        val model = ConsumptionModel()

        // Тільки тепла пора: морозу модель не бачила жодного разу.
        car.week(segments = 150, ambientTempC = 20.0).forEach(model::learn)

        val readiness = model.readiness.byFeature.toMap()
        assertTrue("опір повітря мав вивчитися: $readiness", readiness.getValue("опір повітря") > 0.5)
        assertTrue("обігріву модель не бачила: $readiness", readiness.getValue("обігрів") < 0.2)
    }

    /** Рвана їзда дорожча за рівну з тією самою середньою швидкістю. */
    @Test
    fun `uneven driving costs more than steady driving at the same average`() {
        val car = VirtualCar()
        val model = ConsumptionModel()
        car.week(segments = 150).forEach(model::learn)

        val steady = DriveConditions.steady(50.0)
        val uneven = car.segment(speedKmh = 50.0, speedSpreadKmh = 20.0).let(DriveConditions::of)

        assertTrue(model.predictPowerKw(uneven) > model.predictPowerKw(steady))
    }
}
