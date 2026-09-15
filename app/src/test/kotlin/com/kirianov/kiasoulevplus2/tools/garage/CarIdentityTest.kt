package com.kirianov.kiasoulevplus2.tools.garage

import com.kirianov.kiasoulevplus2.Data.CarIdentity
import com.kirianov.kiasoulevplus2.Data.CarProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarIdentityTest {

    private val car = CarProfile(
        vin = "KNAJX81EFF7004107",
        lastOdometerKm = 190_838.0,
        lastKwhIn = 27_444.9,
        lastKwhOut = 26_413.3,
    )

    /** Та сама машина за годину: усе трохи виросло — впізнаємо. */
    @Test
    fun `small continuous growth is the same car`() {
        assertTrue(
            CarIdentity.continues(car, odometerKm = 190_975.8, kwhIn = 27_476.7, kwhOut = 26_445.5),
        )
    }

    /** Числа стоять на місці (щойно під'єднались) — теж те саме авто. */
    @Test
    fun `unchanged numbers are the same car`() {
        assertTrue(
            CarIdentity.continues(car, odometerKm = 190_838.0, kwhIn = 27_444.9, kwhOut = 26_413.3),
        )
    }

    /** Без відбитка порівнювати нема з чим — не впізнаємо. */
    @Test
    fun `no fingerprint means no match`() {
        val fresh = CarProfile(vin = "NEW")
        assertFalse(CarIdentity.continues(fresh, 100.0, 100.0, 100.0))
    }

    /** Чужа батарея: інші абсолютні лічильники — неперервність не складається. */
    @Test
    fun `a foreign battery does not continue`() {
        assertFalse(
            CarIdentity.continues(car, odometerKm = 190_900.0, kwhIn = 15_000.0, kwhOut = 14_000.0),
        )
    }

    /** Чуже авто з меншим пробігом: пробіг «упав» — точно не воно. */
    @Test
    fun `a smaller odometer is a different car`() {
        assertFalse(
            CarIdentity.continues(car, odometerKm = 120_000.0, kwhIn = 27_445.0, kwhOut = 26_414.0),
        )
    }

    /** Забагато за раз (понад межу) — радше пересіли, ніж це воно: не впізнаємо. */
    @Test
    fun `growth beyond the plausible limit is refused`() {
        assertFalse(
            CarIdentity.continues(
                car,
                odometerKm = car.lastOdometerKm + CarIdentity.MAX_ODOMETER_GROWTH_KM + 1.0,
                kwhIn = car.lastKwhIn + 10.0,
                kwhOut = car.lastKwhOut + 10.0,
            ),
        )
    }

    /** Лічильник стрибнув понад межу (чужа батарея, що близька за пробігом) — ні. */
    @Test
    fun `a counter jump beyond the limit is refused`() {
        assertFalse(
            CarIdentity.continues(
                car,
                odometerKm = car.lastOdometerKm + 5.0,
                kwhIn = car.lastKwhIn + CarIdentity.MAX_COUNTER_GROWTH_KWH + 1.0,
                kwhOut = car.lastKwhOut + 5.0,
            ),
        )
    }
}
