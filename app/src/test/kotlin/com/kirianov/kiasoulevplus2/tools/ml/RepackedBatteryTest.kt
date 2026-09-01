package com.kirianov.kiasoulevplus2.tools.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Заради цього випадку блок і написаний.
 *
 * Батарея перепакована іншими комірками, а BMS рахує відсоток за паспортом
 * **рідного** пакета на 27 кВт·год. Через це приладова панель бреше системно, і не
 * на сталий коефіцієнт, а по-різному вздовж шкали. Модель мусить перекрити саме це:
 * вивчити, скільки кіловат-годин насправді припадає на відсоток BMS, і рахувати
 * запас ходу та реальний заряд від виміряного, а не від паспорта.
 *
 * Числа тут — з реальної машини: пакет близько 51 кВт·год за словами того, хто його
 * збирав, і 200–300 км ходу залежно від того, як їхати.
 */
class RepackedBatteryTest {

    /**
     * Головне: модель мусить знайти справжню ємність, а не лишитися при паспортній.
     * Пакет удвічі більший за рідний, і саме це має бути видно у вивченому числі.
     */
    @Test
    fun `learns the capacity of the pack that is actually installed`() {
        val realCapacityKwh = 51.0
        val capacity = CapacityModel()

        chargeSessions().forEachIndexed { index, (from, to) ->
            // BMS проходить свою шкалу, а комірки віддають те, що в них справді є.
            val energy = realCapacityKwh * (from - to) / 100.0
            capacity.learn(from, to, energy, atMs = index * DAY_MS)
        }

        assertEquals(
            "мали вивчити справжній пакет, а не рідні 27",
            realCapacityKwh / 100.0,
            capacity.kwhPerPercentAt(50.0),
            0.02,
        )
        assertTrue(
            "перепаковка мала виявитися помітно більшою за рідну: ${capacity.timesLargerThanOriginal}",
            capacity.timesLargerThanOriginal > 1.4,
        )
    }

    /**
     * Розбіжність із панеллю — не побічний ефект, а те, за чим сюди й приходять.
     *
     * Якби запас ходу рахували від паспортних 27 кВт·год, вийшло б близько 150 км.
     * Насправді машина проїде понад двісті. Тест стереже саме цю різницю.
     */
    @Test
    fun `predicts the range of the real pack, not the one the dashboard assumes`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trained(car, realCapacityKwh = 51.0)

        val mixed = RangeEstimator.recentConditions(car.week(60, hillNoiseKw = 0.4))
        val prediction = RangeEstimator.predict(consumption, capacity, quality, 100.0, mixed)!!

        assertTrue(
            "на повному пакеті машина мала проїхати 200–300 км, а вийшло ${prediction.rangeKm}",
            prediction.rangeKm in 200.0..300.0,
        )

        // Скільки показав би той самий розрахунок, якби вірив паспорту рідного пакета.
        val ifOriginalPack = prediction.rangeKm * Vehicle.ORIGINAL_CAPACITY_KWH / capacity.usableCapacityKwh
        assertTrue(
            "паспортний пакет дав би близько 150 км: $ifOriginalPack",
            ifOriginalPack < prediction.rangeKm * 0.7,
        )
    }

    /**
     * Крива, а не пряма. Нові комірки живуть за іншою кривою напруги, тож відсоток
     * BMS «важить» неоднаково вздовж шкали: унизу в ньому лишається більше енергії,
     * ніж думає панель. Саме для цього ємність зібрана з корзин — перевіряємо, що
     * вони справді ловлять форму, а не лише середнє.
     */
    @Test
    fun `follows a scale that is not linear`() {
        // dQ/du = 56 − 10·u : унизу відсоток дорожчий, угорі дешевший. Разом 51 кВт·год.
        fun trueEnergyBetween(fromPercent: Double, toPercent: Double): Double {
            val from = fromPercent / 100.0
            val to = toPercent / 100.0
            return 56.0 * (to - from) - 10.0 * (to * to - from * from) / 2.0
        }

        val capacity = CapacityModel()
        var at = 0L
        // Сесії по різних ділянках шкали: інакше форму не відновити.
        listOf(
            100.0 to 70.0, 70.0 to 40.0, 40.0 to 10.0, 90.0 to 45.0,
            60.0 to 20.0, 95.0 to 60.0, 50.0 to 12.0, 85.0 to 35.0,
            100.0 to 55.0, 75.0 to 25.0, 65.0 to 15.0, 88.0 to 42.0,
        ).forEach { (from, to) ->
            capacity.learn(from, to, trueEnergyBetween(to, from), atMs = at)
            at += DAY_MS
        }

        // Відсоток унизу шкали важчий за відсоток угорі — і модель це бачить.
        assertTrue(
            "унизу шкали відсоток мав вийти дорожчим",
            capacity.kwhPerPercentAt(15.0) > capacity.kwhPerPercentAt(85.0) * 1.10,
        )
        assertEquals(0.545, capacity.kwhPerPercentAt(15.0), 0.05)
        assertEquals(0.475, capacity.kwhPerPercentAt(85.0), 0.05)
    }

    /**
     * Наскільки реальний відсоток узагалі може розійтися з панельним — і чому не
     * дуже.
     *
     * Тут варто бути точним, бо очікування природно завищені. Дно й стелю шкали
     * модель виводить із самої ж прямої «панель ↔ BMS». Якщо енергія лежить уздовж
     * шкали рівно, то обидві величини — це те саме лінійне відображення того самого
     * відрізка в нуль-сто, і реальний відсоток **тотожно дорівнює** панельному.
     *
     * Розходяться вони лише через **кривину**: коли відсоток унизу шкали важить
     * більше кіловат-годин, ніж угорі. На перепакованих комірках із чужою кривою
     * напруги так і буває, але навіть за помітної кривини різниця — одиниці
     * відсотків, а не десятки.
     *
     * Тому головна користь блока не у відсотку, а в кіловат-годинах і кілометрах:
     * саме там паспорт рідного пакета помиляється в рази. Тест закріплює обидва
     * висновки, щоб від відсотка не чекали дива.
     */
    @Test
    fun `the real percentage tracks the dial closely, while the kilometres do not`() {
        val capacity = CapacityModel()

        // Крива: унизу відсоток дорожчий за верхній приблизно в півтора раза.
        fun trueEnergyBetween(fromPercent: Double, toPercent: Double): Double {
            val from = fromPercent / 100.0
            val to = toPercent / 100.0
            return 61.0 * (to - from) - 20.0 * (to * to - from * from) / 2.0
        }

        var at = 0L
        listOf(
            100.0 to 70.0, 70.0 to 40.0, 40.0 to 10.0, 90.0 to 45.0,
            60.0 to 20.0, 95.0 to 60.0, 50.0 to 12.0, 85.0 to 35.0,
            100.0 to 55.0, 75.0 to 25.0, 65.0 to 15.0, 88.0 to 42.0,
        ).forEach { (from, to) ->
            capacity.learn(from, to, trueEnergyBetween(to, from), atMs = at)
            at += DAY_MS
        }

        val dialAtHalf = 50.0
        val realAtHalf = capacity.realPercent(50.0)

        assertTrue(
            "кривина мала зсунути відсоток угору, а вийшло $realAtHalf",
            realAtHalf > dialAtHalf + 1.0,
        )
        assertTrue(
            "але не в десятки відсотків: $realAtHalf",
            realAtHalf < dialAtHalf + 10.0,
        )

        // А от енергія і кілометри розходяться з паспортом рідного пакета в рази.
        assertTrue(
            "пакет мав виявитися помітно більшим за рідний: ${capacity.usableCapacityKwh}",
            capacity.usableCapacityKwh > Vehicle.ORIGINAL_CAPACITY_KWH * 1.4,
        )
    }

    /**
     * Наскрізно: справжня машина, справжній пакет, справжній діапазон. Прогноз має
     * лягти в 200–300 км на змішаній їзді й помітно скоротитися взимку на трасі.
     */
    @Test
    fun `covers the real envelope of this car`() {
        val car = VirtualCar()
        val (consumption, capacity, quality) = trained(car, realCapacityKwh = 51.0)

        val mixed = RangeEstimator.recentConditions(car.week(60, hillNoiseKw = 0.4))
        val summer = RangeEstimator.predict(consumption, capacity, quality, 100.0, mixed)!!
        val winterHighway = RangeEstimator.predict(
            consumption, capacity, quality, 100.0,
            DriveConditions.steady(90.0, ambientTempC = -8.0),
        )!!

        assertTrue("змішана їзда: ${summer.rangeKm} км", summer.rangeKm in 200.0..300.0)
        assertTrue("зима на трасі: ${winterHighway.rangeKm} км", winterHighway.rangeKm in 150.0..250.0)
        assertTrue("взимку має бути коротше", winterHighway.rangeKm < summer.rangeKm)

        assertTrue("витрата має бути правдоподібною: ${summer.whPerKm}", summer.whPerKm in 140.0..230.0)
        assertTrue("на 80 % SOC — приблизно чотири п'ятих шляху", run {
            val partial = RangeEstimator.predict(consumption, capacity, quality, 80.0, mixed)!!
            partial.rangeKm in summer.rangeKm * 0.7..summer.rangeKm * 0.85
        })
    }

    private fun trained(
        car: VirtualCar,
        realCapacityKwh: Double,
    ): Triple<ConsumptionModel, CapacityModel, PredictionQuality> {
        val consumption = ConsumptionModel()
        val capacity = CapacityModel()
        val quality = PredictionQuality()

        car.week(segments = 200, hillNoiseKw = 0.5, measurementNoiseKw = 0.25).forEach { segment ->
            consumption.learn(segment)?.let { quality.observe(segment, it) }
        }
        chargeSessions().forEachIndexed { index, (from, to) ->
            capacity.learn(from, to, realCapacityKwh * (from - to) / 100.0, atMs = index * DAY_MS)
        }
        return Triple(consumption, capacity, quality)
    }

    private fun chargeSessions() = listOf(
        90.0 to 30.0, 85.0 to 25.0, 95.0 to 40.0, 80.0 to 20.0,
        100.0 to 35.0, 75.0 to 15.0, 92.0 to 28.0, 88.0 to 22.0,
    )

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
