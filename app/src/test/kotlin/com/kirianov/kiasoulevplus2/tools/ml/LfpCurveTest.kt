package com.kirianov.kiasoulevplus2.tools.ml

import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Крива літій-залізо-фосфатного пакета, який читає BMS із таблицею напруг від
 * рідних нікелевих комірок.
 *
 * У LFP полиця напруги майже пласка: від чверті до трьох чвертей заряду напруга
 * майже не рухається. BMS, налаштована на нікель, у цій зоні «не помічає»
 * витраченого — на малий ΔSOC там припадає багато кіловат-годин. На колінах шкали
 * навпаки: напруга летить, BMS малює великий ΔSOC, а енергії за ним мало.
 *
 * Виходить крива з вираженим горбом посередині. Саме її модель і мусить відновити,
 * бо від неї залежить і залишок енергії, і реальний відсоток.
 *
 * Еталон тут навмисно **не поліном і не сума корзин** — гаусів горб із числовим
 * інтегруванням. Інакше тест перевіряв би, чи вміє модель вгадати власний базис,
 * а не чи ловить вона справжню форму.
 */
class LfpCurveTest {

    /** Скільки кВт·год лежить у кожній одиниці u = SOC/100. Горб посередині. */
    private fun density(u: Double): Double = SCALE * (1.0 + HUMP * exp(-square((u - CENTRE) / WIDTH)))

    /** Справжня енергія між двома відсотками шкали, кВт·год. */
    private fun energyBetween(fromPercent: Double, toPercent: Double): Double {
        var sum = 0.0
        var u = fromPercent / 100.0
        val end = toPercent / 100.0
        while (u < end) {
            sum += density(u + STEP / 2.0) * STEP
            u += STEP
        }
        return sum
    }

    /**
     * Головне питання: чи перераховує модель цю криву правильно. Сесії — такі, якими
     * вони бувають насправді: вісім-п'ятнадцять відсотків шкали за раз, розкидані по
     * всьому діапазону, за рік із гаком.
     */
    @Test
    fun `reconstructs the hump of a lithium iron phosphate pack`() {
        val model = trainedOverWholeScale()

        assertEquals("повна ємність", TOTAL_KWH, model.usableCapacityKwh, TOTAL_KWH * 0.06)

        // Перевіряємо в серединах корзин: на краю корзина віддає своє середнє, і
        // порівнювати його з точковим значенням кривої було б нечесно.
        listOf(25.0, 35.0, 45.0, 55.0, 65.0, 75.0, 85.0).forEach { percent ->
            val truth = density(percent / 100.0) / 100.0
            val actual = model.kwhPerPercentAt(percent)
            val error = abs(actual - truth) / truth
            assertTrue(
                "на $percent %%: очікували %.3f, вийшло %.3f (похибка %.0f %%)"
                    .format(truth, actual, error * 100),
                error < 0.12,
            )
        }
    }

    /**
     * Горб має бути знайдений, а не згладжений у пряму. Якщо модель поверне рівну
     * шкалу, усі числа лишаться «правдоподібними», а залишок унизу буде завищений
     * саме там, де помилка найдорожча.
     */
    @Test
    fun `finds that the middle of the scale is worth far more than the ends`() {
        val model = trainedOverWholeScale()

        val middle = model.kwhPerPercentAt(45.0)
        val bottom = model.kwhPerPercentAt(5.0)
        val top = model.kwhPerPercentAt(95.0)

        assertTrue("посередині мало вийти помітно дорожче за низ: $middle проти $bottom", middle > bottom * 1.8)
        assertTrue("і за верх: $middle проти $top", middle > top * 1.8)

        val trueRatio = density(0.45) / density(0.95)
        val gotRatio = middle / top
        assertEquals("форма горба, а не лише його наявність", trueRatio, gotRatio, trueRatio * 0.35)
    }

    /**
     * Реальний відсоток мусить іти по енергії, а не по шкалі. На горбатій кривій це
     * вже помітна різниця: там, де BMS показує половину, енергії лишається інакше.
     */
    @Test
    fun `the real percentage follows energy along the curved scale`() {
        val model = trainedOverWholeScale()
        val total = energyBetween(0.0, 100.0)

        listOf(20.0, 40.0, 60.0, 80.0).forEach { percent ->
            val truth = energyBetween(0.0, percent) / total * 100.0
            val actual = model.realPercent(percent)
            assertEquals("реальний заряд на $percent % шкали", truth, actual, 4.0)
        }
    }

    /**
     * Вимога власника: дозволене BMS вікно має світитися саме як нуль і сто.
     * Скільки там насправді лишилося в комірках понад це вікно — питання окреме;
     * шкала на екрані мусить збігатися з тією, за якою авто дозволяє їздити.
     */
    @Test
    fun `the window the bms allows reads as nought and a hundred`() {
        val model = trainedOverWholeScale()

        assertEquals("нижній край вікна", 0.0, model.realPercent(model.floorSocPercent), 0.5)
        assertEquals("верхній край вікна", 100.0, model.realPercent(model.ceilingSocPercent), 0.5)
        assertTrue(
            "між краями відсоток мусить рости монотонно",
            (10..90 step 10).map { model.realPercent(it.toDouble()) }.zipWithNext()
                .all { (lower, upper) -> upper >= lower },
        )
    }

    /**
     * Чесність там, куди водій не заїжджав. Якщо верх шкали ніколи не бачили, модель
     * не має ані вигадувати там форму, ані злітати в мінус — вона мусить лишитися при
     * апріорному значенні.
     *
     * Саме через це крива й розбита на корзини: поліном, підігнаний по 12–88 %, на
     * дев'яноста п'ятьох давав −79 % і тягнув за собою весь залишок.
     */
    @Test
    fun `stays sane where the driver never went`() {
        val model = CapacityModel()
        val random = Random(11)
        var at = 0L
        // Жодної сесії вище 80 %: людина просто не заряджає до повної.
        repeat(140) {
            val span = 8.0 + random.nextDouble() * 7.0
            val from = 12.0 + random.nextDouble() * (80.0 - span - 12.0) + span
            model.learn(from, from - span, energyBetween(from - span, from), atMs = at)
            at += 2L * 24 * 3600 * 1000
        }

        val unexplored = model.kwhPerPercentAt(95.0)
        assertTrue("необстежена частина шкали не має злітати: $unexplored", unexplored in 0.15..0.75)
        assertTrue("і не має ставати від'ємною", unexplored > 0.0)

        // А там, де їздили, крива має бути такою ж точною, як і за повного покриття.
        listOf(35.0, 45.0, 55.0, 65.0).forEach { percent ->
            val truth = density(percent / 100.0) / 100.0
            assertEquals("на $percent % шкали", truth, model.kwhPerPercentAt(percent), truth * 0.15)
        }
    }

    /** Заряджання вчить кривій так само, як і рух: знаки міняються разом. */
    @Test
    fun `charging teaches the same curve as driving`() {
        val driving = CapacityModel()
        val charging = CapacityModel()
        val random = Random(5)
        var at = 0L

        repeat(140) {
            val span = 8.0 + random.nextDouble() * 7.0
            val from = 5.0 + random.nextDouble() * (100.0 - span - 5.0) + span
            val to = from - span
            val energy = energyBetween(to, from)
            driving.learn(from, to, energy, atMs = at)
            // Те саме, тільки SOC росте, а енергія приходить: заряджання.
            charging.learn(to, from, -energy, atMs = at)
            at += 2L * 24 * 3600 * 1000
        }

        listOf(25.0, 45.0, 65.0, 85.0).forEach { percent ->
            assertEquals(
                "на $percent % шкали заряд і рух мають дати те саме",
                driving.kwhPerPercentAt(percent),
                charging.kwhPerPercentAt(percent),
                driving.kwhPerPercentAt(percent) * 0.02,
            )
        }
    }

    /**
     * «Середня ємність» — скільки кіловат-годин у машині насправді, порахованих
     * найтупішим можливим способом: уся виміряна енергія, поділена на пройдені
     * відсотки шкали. Жодної кривої, жодних корзин.
     *
     * Саме через свою тупість це число й цінне: воно ні на що не спирається, тож
     * збіг із вивченою кривою означає, що крива не вигадана.
     */
    @Test
    fun `the plain average agrees with the learned curve`() {
        val model = trainedOverWholeScale()

        val average = model.averageCapacityKwh
        assertTrue("середня ємність мала порахуватися", average != null)
        assertEquals("має вийти близько до справжньої ємності", TOTAL_KWH, average!!, TOTAL_KWH * 0.15)
        assertEquals(
            "два незалежні розрахунки мають зійтися",
            model.usableCapacityKwh,
            average,
            average * 0.15,
        )
        assertTrue("шкали мало пройти багато", model.measuredScalePercent > 1000.0)
    }

    /**
     * У чому середня ємність поступається кривій, і про це варто знати.
     *
     * Це середня густина **по тих ділянках шкали, якими їздили**, а не по всій
     * шкалі. На LFP середина енергетично щільніша за краї, тож у того, хто тримає
     * заряд між сорока й сімдесятьма, середня читатиметься завищено — і це не збій,
     * а чесна властивість найпростішого розрахунку.
     *
     * Крива такої вади не має: вона знає, де саме була кожна сесія. Тому на екрані
     * стоять обидва числа, а не одне.
     */
    @Test
    fun `the plain average leans towards wherever the driver keeps the charge`() {
        val middleOnly = CapacityModel()
        val random = Random(3)
        var at = 0L
        // Людина тримає заряд між 40 і 70 %: у найщільнішій частині шкали.
        repeat(140) {
            val span = 8.0 + random.nextDouble() * 7.0
            val from = 40.0 + span + random.nextDouble() * (70.0 - span - 40.0)
            middleOnly.learn(from, from - span, energyBetween(from - span, from), atMs = at)
            at += 2L * 24 * 3600 * 1000
        }

        val average = middleOnly.averageCapacityKwh!!
        assertTrue(
            "їзда самою серединою мала завищити середню: $average проти $TOTAL_KWH",
            average > TOTAL_KWH * 1.15,
        )
        // А крива при цьому лишається в межах розумного, бо знає, де були сесії.
        assertTrue(
            "крива не мала повестися так само: ${middleOnly.usableCapacityKwh}",
            middleOnly.usableCapacityKwh < average,
        )
    }

    /** Поки шкали пройдено мало, середнє нічого не означає — і його не показують. */
    @Test
    fun `the average stays silent until enough of the scale has been covered`() {
        val model = CapacityModel()

        assertTrue("на порожньому місці середнього нема", model.averageCapacityKwh == null)

        model.learn(60.0, 50.0, energyBetween(50.0, 60.0), atMs = 0L)
        assertTrue("однієї сесії замало", model.averageCapacityKwh == null)

        var at = 1L
        listOf(90.0 to 75.0, 75.0 to 60.0, 50.0 to 35.0, 35.0 to 20.0).forEach { (from, to) ->
            model.learn(from, to, energyBetween(to, from), atMs = at++)
        }
        assertTrue("а після половини шкали вже є", model.averageCapacityKwh != null)
    }

    private fun trainedOverWholeScale(): CapacityModel {
        val model = CapacityModel()
        val random = Random(7)
        var at = 0L
        repeat(160) {
            val span = 8.0 + random.nextDouble() * 7.0
            val from = 2.0 + random.nextDouble() * (100.0 - span - 2.0) + span
            model.learn(from, from - span, energyBetween(from - span, from), atMs = at)
            at += 2L * 24 * 3600 * 1000
        }
        return model
    }

    private fun square(value: Double) = value * value

    private companion object {
        const val TOTAL_KWH = 45.0

        /** Наскільки полиця «важча» за коліна. */
        const val HUMP = 2.2
        const val CENTRE = 0.45
        const val WIDTH = 0.28
        const val STEP = 0.001

        /** Множник, який зводить горб до потрібних кіловат-годин. */
        val SCALE: Double = run {
            var sum = 0.0
            var u = 0.0
            while (u < 1.0) {
                sum += (1.0 + HUMP * exp(-((u + STEP / 2.0 - CENTRE) / WIDTH) * ((u + STEP / 2.0 - CENTRE) / WIDTH))) * STEP
                u += STEP
            }
            TOTAL_KWH / sum
        }
    }
}
