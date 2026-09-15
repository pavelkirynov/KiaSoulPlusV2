package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterWatermarkTest {

    private fun reading(kwhIn: Double, kwhOut: Double, ahIn: Double = 0.0, ahOut: Double = 0.0) =
        BmsData(
            displaySoc = 50.0,
            cumulativeEnergyChargedKwh = kwhIn,
            cumulativeEnergyDischargedKwh = kwhOut,
            cumulativeChargedAh = ahIn,
            cumulativeDischargedAh = ahOut,
        )

    /**
     * ЖИВИЙ ВИПАДОК, ЗАРАДИ ЯКОГО ЦЕ Й НАПИСАНО.
     *
     * Лічильники стрибнули з 27131 кВт·год на 5905, а пробіг із 189420 км на
     * 113467 — і всі числа узгоджені між собою. Виявилося, що це була справжня
     * ДРУГА МАШИНА: за півтори хвилини до того телефон з'єднався з іншою
     * магнітолою. Тож правильна реакція — не мовчати, а спитати, хто це.
     */
    @Test
    fun `a fallen counter is refused and asks who this car is`() {
        val guard = CounterWatermark()
        assertTrue(guard.accept(reading(27_131.9, 26_110.1, 73_971.0, 73_872.6)))

        assertFalse(guard.accept(reading(5_905.0, 5_519.4, 16_279.5, 15_895.6)))
        assertTrue("причина має бути названа", guard.lastRejection.contains("упав"))
        assertTrue("і VIN треба перечитати", guard.wantsVinRecheck)
    }

    /** Питання ставиться один раз на подію, а не на кожне читання. */
    @Test
    fun `the vin is asked about once per event`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1))

        guard.accept(reading(5_905.0, 5_519.4))
        assertTrue(guard.wantsVinRecheck)
        guard.vinRecheckAsked()

        guard.accept(reading(5_905.0, 5_519.4))
        assertFalse("вдруге питати нема сенсу", guard.wantsVinRecheck)
    }

    /** Збій минув — облік іде далі, ніби нічого й не було. */
    @Test
    fun `the good reading after a glitch passes`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1))
        guard.accept(reading(5_905.0, 5_519.4))

        assertTrue(guard.accept(reading(27_132.3, 26_110.1)))
        assertTrue(guard.lastRejection.isEmpty())
    }

    /**
     * Лічильник МОЖЕ обнулитися по-справжньому — заміна блока BMS. Сторож, який
     * відкидає все нижче колишнього рівня, замовк би тоді назавжди. Тому нижчий
     * рівень приймається, коли тримається кілька читань: за цей час устигає прийти
     * відповідь на перечитаний VIN, і якщо авто те саме — значить справа в батареї.
     */
    @Test
    fun `a level that holds for a few readings is accepted as a new battery`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1))

        repeat(CounterWatermark.ACCEPT_AFTER_READINGS - 1) { attempt ->
            assertFalse("читання $attempt мало бути відкинуте", guard.accept(reading(10.0, 8.0)))
        }
        assertTrue("а це вже нова батарея", guard.accept(reading(10.0, 8.0)))
        assertTrue("і далі облік іде від нового рівня", guard.accept(reading(12.0, 9.0)))
    }

    /** Одне добре читання між падіннями скидає лічильник терпіння. */
    @Test
    fun `a good reading in between resets the patience`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1))
        guard.accept(reading(5_905.0, 5_519.4))
        guard.accept(reading(27_132.3, 26_110.1))

        // Нове падіння — відлік починається заново, а не продовжує колишній.
        assertFalse(guard.accept(reading(5_905.0, 5_519.4)))
        assertFalse(guard.accept(reading(5_905.0, 5_519.4)))
        assertTrue("і питання про VIN ставиться знову", guard.wantsVinRecheck)
    }

    /** Інше авто — інші лічильники, і падіння там законне й миттєве. */
    @Test
    fun `switching cars makes a lower counter legitimate`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1))

        guard.forgetCar()

        assertTrue(guard.accept(reading(5_905.0, 5_519.4)))
    }

    /** Перші кадри без лічильників пропускаємо: інакше не завестися взагалі. */
    @Test
    fun `readings without counters pass`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1))

        assertTrue(guard.accept(BmsData()))
        assertTrue(guard.accept(reading(0.0, 0.0)))
    }
}
