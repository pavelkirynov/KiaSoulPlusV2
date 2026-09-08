package com.kirianov.kiasoulevplus2.tools.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кадр 21 05 — другий, незалежний прилад на питання «як зносилася батарея». Наші
 * власні заміри під навантаженням кажуть своє, BMS — своє, і цінна саме
 * розбіжність. Тому зміщення тут виписані числами: тест мусить ловити зсув
 * таблиці, а не повторювати його за декодером.
 */
class PackHealthDecoderTest {

    private fun frame(size: Int = 40): List<Int> = MutableList(size) { 0 }.apply {
        this[11] = 18                      // вхід охолодження, 18 °C
        this[12] = 22                      // мінімум по пакету
        this[13] = 29                      // максимум по пакету
        this[25] = 15                      // нагрівач 1
        this[26] = 16                      // нагрівач 2
        this[27] = 0x03; this[28] = 0xE8   // 1000 × 0.1 = 100.0 % — найгірша
        this[29] = 42                      // її номер
        this[30] = 0x03; this[31] = 0xC6   // 966 × 0.1 = 96.6 % — найкраща
        this[32] = 7                       // її номер
        this[33] = 0xB4                    // 180 / 2 = 90.0 % SOC
    }

    @Test
    fun `the whole frame is decoded`() {
        val health = PackHealthDecoder.decode(frame())

        assertTrue(health.known)
        assertEquals(29.0, health.maxTempC, 0.001)
        assertEquals(22.0, health.minTempC, 0.001)
        assertEquals(18.0, health.inletTempC, 0.001)
        assertEquals(15.0, health.heater1TempC, 0.001)
        assertEquals(16.0, health.heater2TempC, 0.001)
        assertEquals(100.0, health.maxDeteriorationPercent, 0.001)
        assertEquals(42, health.maxDeteriorationCell)
        assertEquals(96.6, health.minDeteriorationPercent, 0.001)
        assertEquals(7, health.minDeteriorationCell)
        assertEquals(90.0, health.displaySoc, 0.001)
    }

    /**
     * РОЗКИД — ГОЛОВНЕ ЧИСЛО ЦЬОГО КАДРУ. Абсолютний знос BMS рахує проти рідної
     * хімії, як і відсотки заряду, тож на перепакованому пакеті він під підозрою.
     * А різниця між найгіршою й найкращою коміркою від паспорта не залежить.
     */
    @Test
    fun `the spread between worst and best cell is what matters`() {
        val health = PackHealthDecoder.decode(frame())

        assertEquals(3.4, health.deteriorationSpread, 0.001)
        assertEquals(7.0, health.tempSpreadC, 0.001)
    }

    /** Мороз читається зі знаком: батарея буває й мінусовою. */
    @Test
    fun `temperatures are signed`() {
        val frozen = frame().toMutableList().apply {
            this[12] = 0xF6  // -10
            this[13] = 0xFB  // -5
        }
        val health = PackHealthDecoder.decode(frozen)

        assertEquals(-5.0, health.maxTempC, 0.001)
        assertEquals(-10.0, health.minTempC, 0.001)
    }

    /** Короткий кадр — не привід вигадувати числа. */
    @Test
    fun `a short frame is not decoded at all`() {
        assertFalse(PackHealthDecoder.decode(frame().take(20)).known)
        assertFalse(PackHealthDecoder.decode(emptyList()).known)
    }

    /**
     * РЕГРЕСІЯ ІЗ КАДРУ 21 01, ЯКА ПОВТОРИЛАСЯ Б І ТУТ: кадр правильної довжини з
     * самих 0xFF приходить, коли адаптер захлинувся, і його числа виглядають
     * правдоподібно рівно доти, доки їх не перевірити.
     */
    @Test
    fun `a frame of all ones is rejected`() {
        assertFalse(PackHealthDecoder.decode(List(40) { 0xFF }).known)
    }

    /** Максимум нижче мінімуму — не батарея, а не ті байти. */
    @Test
    fun `a maximum below the minimum is rejected`() {
        val swapped = frame().toMutableList().apply {
            this[12] = 30
            this[13] = 20
        }

        assertFalse(PackHealthDecoder.decode(swapped).known)
    }

    /** Знос у двісті відсотків — теж не батарея. */
    @Test
    fun `impossible wear is rejected`() {
        val absurd = frame().toMutableList().apply {
            this[27] = 0x27; this[28] = 0x10  // 10000 × 0.1 = 1000 %
        }

        assertFalse(PackHealthDecoder.decode(absurd).known)
    }
}
