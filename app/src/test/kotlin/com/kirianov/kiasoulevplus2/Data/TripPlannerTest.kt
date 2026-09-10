package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPlannerTest {

    /** Витрата взята з реальних чисел цієї машини: 117 на 60, 158 на 90, 198 на 110. */
    private val scenarios = listOf(
        RangeScenario(speedKmh = 60.0, rangeKm = 0.0, whPerKm = 117.0),
        RangeScenario(speedKmh = 90.0, rangeKm = 0.0, whPerKm = 158.0),
        RangeScenario(speedKmh = 110.0, rangeKm = 0.0, whPerKm = 198.0),
    )

    private fun optionAt(speed: Double, options: List<TripOption>) =
        options.first { it.speedKmh == speed }

    /** Без відстані рахувати нічого: екран мусить просити числа, а не вигадувати їх. */
    @Test
    fun `no distance means no options`() {
        assertTrue(TripPlanner.options(scenarios, 40.0, TripConditions()).isEmpty())
    }

    /**
     * КОРОТКА ДОРОГА — ПИТАННЯ ЗНЯТЕ. Якщо енергії в пакеті вистачає, швидкість
     * впливає тільки на час у дорозі, і найшвидша просто найшвидша.
     */
    @Test
    fun `a short trip needs no stops and the fastest speed wins`() {
        val options = TripPlanner.options(
            scenarios = scenarios,
            availableKwh = 40.0,
            conditions = TripConditions(distanceKm = 100.0, priceUahPerKwh = 8.0, chargerKw = 50.0),
        )

        assertTrue(options.all { it.reachableWithoutCharging })
        assertEquals(0.0, optionAt(110.0, options).costUah, 0.001)
        assertEquals(110.0, TripPlanner.fastest(options)!!.speedKmh, 0.001)
    }

    /**
     * А ОСЬ НА ДАЛЕКІЙ ДОРОЗІ ШВИДКІСТЬ ПОЧИНАЄ КОШТУВАТИ.
     *
     * П'ятсот кілометрів на 110 км/год це 99 кВт·год, на 60 — 58.5. Різницю
     * доводиться доливати на станції, і виграні за кермом години частково
     * повертаються стоянням.
     */
    @Test
    fun `a long trip pays for speed with charging time`() {
        val options = TripPlanner.options(
            scenarios = scenarios,
            availableKwh = 40.0,
            conditions = TripConditions(distanceKm = 500.0, priceUahPerKwh = 8.0, chargerKw = 50.0),
        )

        val slow = optionAt(60.0, options)
        val fast = optionAt(110.0, options)

        assertEquals(58.5, slow.neededKwh, 0.01)
        assertEquals(99.0, fast.neededKwh, 0.01)

        // Швидша швидкість — менше часу за кермом, більше на зарядці й дорожче.
        assertTrue(fast.drivingHours < slow.drivingHours)
        assertTrue(fast.chargingHours > slow.chargingHours)
        assertTrue(fast.costUah > slow.costUah)
    }

    /** Долив рахується від того, чого не вистачає, з поправкою на резерв. */
    @Test
    fun `the reserve is never counted as available`() {
        val options = TripPlanner.options(
            scenarios = listOf(RangeScenario(60.0, 0.0, 100.0)),
            availableKwh = 12.0,
            conditions = TripConditions(distanceKm = 100.0, priceUahPerKwh = 10.0, chargerKw = 50.0),
        )

        // Треба 10 кВт·год, у пакеті 12, але 2 недоторканні — отже, доливати нічого
        // не треба й рівно нічого не лишається на запас.
        val option = options.single()
        assertEquals(10.0, option.neededKwh, 0.001)
        assertEquals(0.0, option.chargedKwh, 0.001)
    }

    /** Кожна зупинка додає свої накладні хвилини, і їх видно окремо. */
    @Test
    fun `stops are counted and each one costs its overhead`() {
        val options = TripPlanner.options(
            scenarios = listOf(RangeScenario(90.0, 0.0, 200.0)),
            availableKwh = 2.0,
            conditions = TripConditions(distanceKm = 450.0, priceUahPerKwh = 10.0, chargerKw = 50.0),
        )

        val option = options.single()
        assertEquals(90.0, option.neededKwh, 0.001)
        assertEquals(90.0, option.chargedKwh, 0.001)
        assertEquals(3, option.stops)
        // 90 кВт·год на 50 кВт це 1.8 години плюс три зупинки по 15 хвилин.
        assertEquals(1.8 + 0.75, option.chargingHours, 0.001)
        assertEquals(900.0, option.costUah, 0.001)
    }

    /** Різниця рахується від обраного: додатне означає «довше й дорожче». */
    @Test
    fun `the difference is signed from the chosen speed`() {
        val options = TripPlanner.options(
            scenarios = scenarios,
            availableKwh = 40.0,
            conditions = TripConditions(distanceKm = 500.0, priceUahPerKwh = 8.0, chargerKw = 50.0),
        )
        val difference = TripPlanner.compare(
            chosen = optionAt(110.0, options),
            reference = optionAt(60.0, options),
        )

        assertTrue("на 110 дорожче", difference.costUah > 0.0)
        assertTrue("за кермом менше", difference.drivingHours < 0.0)
        assertTrue("на зарядці більше", difference.chargingHours > 0.0)
    }
}
