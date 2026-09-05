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
     * На шині на три хвилини з'явився ЗВ'ЯЗНИЙ набір чужих чисел: 5905 кВт·год
     * замість 27131, і всі лічильники узгоджені між собою так, ніби це справді інше
     * авто. Не сміття, яке видно з першого погляду.
     */
    @Test
    fun `a coherent but foreign set of counters is refused`() {
        val guard = CounterWatermark()
        assertTrue(guard.accept(reading(27_131.9, 26_110.1, 73_971.0, 73_872.6), 0L))

        assertFalse(guard.accept(reading(5_905.0, 5_519.4, 16_279.5, 15_895.6), 60_000L))
        assertTrue("причина має бути названа", guard.lastRejection.contains("упав"))
    }

    /** Збій минув — облік іде далі, ніби нічого й не було. */
    @Test
    fun `the good reading after a glitch passes`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1), 0L)
        guard.accept(reading(5_905.0, 5_519.4), 60_000L)

        assertTrue(guard.accept(reading(27_132.3, 26_110.1), 120_000L))
        assertTrue(guard.lastRejection.isEmpty())
    }

    /**
     * Лічильник МОЖЕ обнулитися по-справжньому — заміна блока BMS. Сторож, який
     * відкидає все нижче колишнього рівня, замовк би тоді назавжди. Тому падіння
     * приймається, коли тримається довго: збій тривав три хвилини, справжнє
     * обнулення не мине ніколи.
     */
    @Test
    fun `a drop that holds long enough is accepted as a real reset`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1), 0L)

        assertFalse(guard.accept(reading(10.0, 8.0), 60_000L))
        assertFalse("три хвилини — ще збій", guard.accept(reading(10.0, 8.0), 180_000L))

        val later = 60_000L + CounterWatermark.ACCEPT_AFTER_MS
        assertTrue("а півгодини — вже нова батарея", guard.accept(reading(10.0, 8.0), later))
        // І далі облік іде від нового рівня.
        assertTrue(guard.accept(reading(12.0, 9.0), later + 1_000L))
    }

    /** Одне добре читання між падіннями скидає лічильник терпіння. */
    @Test
    fun `a good reading in between resets the patience`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1), 0L)
        guard.accept(reading(5_905.0, 5_519.4), 60_000L)
        guard.accept(reading(27_132.3, 26_110.1), 120_000L)

        // Нове падіння — відлік починається заново, а не продовжує колишній.
        assertFalse(guard.accept(reading(5_905.0, 5_519.4), 180_000L))
        assertFalse(guard.accept(reading(5_905.0, 5_519.4), 600_000L))
    }

    /** Інше авто — інші лічильники, і падіння там законне й миттєве. */
    @Test
    fun `switching cars makes a lower counter legitimate`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1), 0L)

        guard.forgetCar()

        assertTrue(guard.accept(reading(5_905.0, 5_519.4), 1_000L))
    }

    /** Перші кадри без лічильників пропускаємо: інакше не завестися взагалі. */
    @Test
    fun `readings without counters pass`() {
        val guard = CounterWatermark()
        guard.accept(reading(27_131.9, 26_110.1), 0L)

        assertTrue(guard.accept(BmsData(), 1_000L))
        assertTrue(guard.accept(reading(0.0, 0.0), 2_000L))
    }
}
