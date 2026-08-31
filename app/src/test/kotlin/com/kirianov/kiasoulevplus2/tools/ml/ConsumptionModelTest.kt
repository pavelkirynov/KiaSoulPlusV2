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

    /**
     * Вивчений постійний відбір має збігтися з тим, що закладали.
     *
     * У даних навмисно є затори. Без них відбір із самої лише їзди не виділяється:
     * на швидкостях від 25 км/год і вище він невідрізнимий від опору коченню, і
     * чесна відповідь моделі — лишитися при фізиці. Саме тому повільні відрізки
     * тут не сміття, а найцінніше, що є.
     */
    @Test
    fun `learns the constant draw of the car`() {
        val car = VirtualCar(auxKw = 1.20)
        val model = ConsumptionModel()

        car.week(segments = 150).forEach(model::learn)
        repeat(50) { index ->
            model.learn(
                car.segment(speedKmh = 6.0, distanceKm = 0.4, atMs = 2_000_000L + index * 600_000L),
            )
        }

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
     *
     * «Не має права зсунути» тут не означає «відкидається». Дике значення
     * обрізається: рядок ознак лишається в матриці, але потягнути відгук далі
     * кількох розкидів воно не може. Тому зсув не нульовий, а мізерний — і, на
     * відміну від відкидання, це не створює відбору проти незручних даних.
     */
    @Test
    fun `a single broken segment barely moves the model`() {
        val car = VirtualCar()
        val model = ConsumptionModel()
        car.week(segments = 120).forEach(model::learn)

        val before = model.predictWhPerKm(DriveConditions.steady(60.0))
        val broken = car.segment(speedKmh = 60.0).copy(energyKwh = 40.0)
        model.learn(broken)
        val after = model.predictWhPerKm(DriveConditions.steady(60.0))

        assertEquals("вплив викиду мав лишитися мізерним", before, after, before * 0.02)
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
        assertTrue("обігріву модель не бачила: $readiness", readiness.getValue("обігрів понад те") < 0.2)
    }

    /**
     * Клімат тепер видно прямо: кадр 200 каже, скільки кілометрів він з'їдає.
     * Модель мусить брати саме це свідчення, а не гадати з погоди — інакше тепла
     * зима з увімкненою пічкою й холодна з вимкненою виглядали б однаково.
     */
    @Test
    fun `learns what the climate costs from the car's own word`() {
        val car = VirtualCar()
        val model = ConsumptionModel()

        // Однакова погода, різний клімат: відрізнити їх можна тільки за часткою.
        val hvacOff = car.week(segments = 100, ambientTempC = 10.0)
            .map { it.copy(climateShare = 0.0) }
        val hvacOn = car.week(segments = 100, ambientTempC = 10.0, startAtMs = 2_000_000L)
            .map { segment ->
                val hours = segment.durationMs / 3_600_000.0
                // Пічка додає рівно 3 кВт, і авто чесно каже про це часткою.
                val extra = 3.0 * hours
                val total = segment.energyKwh + extra
                segment.copy(energyKwh = total, tractionKwh = total, climateShare = extra / total)
            }
        (hvacOff + hvacOn).sortedBy { it.startedAtMs }.forEach(model::learn)

        val cold = model.predictPowerKw(DriveConditions.steady(60.0, ambientTempC = 10.0, climateShare = 0.0))
        val warm = model.predictPowerKw(
            DriveConditions.steady(60.0, ambientTempC = 10.0, climateShare = 0.3),
        )

        assertTrue("з увімкненим кліматом має виходити дорожче: $cold проти $warm", warm > cold + 1.0)
        assertTrue("і модель мала це вивчити з частки", model.readiness.byFeature.toMap().getValue("клімат") > 0.5)
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
