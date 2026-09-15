package com.kirianov.kiasoulevplus2.tools.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TirePressureDecoderTest {

    /**
     * 2.3 бар — це 33.4 psi, тобто 167 кроків по 0.2 psi. Температура 25 °C
     * лежить одразу за тиском свого колеса й зміщена на п'ятдесят.
     */
    @Test
    fun `decodes four pressures and four temperatures`() {
        val result = TirePressureDecoder.decode(
            "62 C0 0B A7 4B 00 00 A8 4B 00 00 A6 4C 00 00 A7 4B 00 00",
        )

        assertTrue(result.known)
        assertEquals(4, result.pressuresBar.size)
        assertEquals(2.303, result.pressuresBar[0], 0.01)
        assertEquals(25.0, result.tempsC[0], 0.001)
        assertEquals(2.317, result.pressuresBar[1], 0.01)
        assertEquals(26.0, result.tempsC[2], 0.001)
    }

    /**
     * Нуль на всіх колесах — це не «спущені шини», а не ті байти: спущені разом
     * усі чотири не бувають, а нуль означає ще й повну відсутність датчика.
     */
    @Test
    fun `refuses an answer where the pressure is impossible`() {
        val result = TirePressureDecoder.decode(
            "62 C0 0B 00 4B 00 00 00 4B 00 00 00 4B 00 00 00 4B 00 00",
        )

        assertFalse(result.known)
        assertFalse(result.hasPressures)
    }

    /** Кадр із самих 0xFF — звична відповідь захлинутого адаптера. */
    @Test
    fun `refuses a frame full of ff`() {
        val result = TirePressureDecoder.decode("62 C0 0B " + "FF ".repeat(16))

        assertFalse(result.known)
    }

    @Test
    fun `refuses silence and short answers`() {
        assertFalse(TirePressureDecoder.decode("").known)
        assertFalse(TirePressureDecoder.decode("NO DATA").known)
        assertFalse(TirePressureDecoder.decode("62 C0 0B A7 4B 00 00").known)
    }

    /**
     * ВІДПОВІДЬ ЧУЖОЮ МОВОЮ НЕ РОЗБИРАЄТЬСЯ ЦИМИ ЗМІЩЕННЯМИ.
     *
     * Блок питається двома кандидатами — `22 C0 0B` і `21 01`, — бо котрий із них
     * його, ще не з'ясовано. Зміщення нижче відомі рівно для першого; розбирати
     * ними відповідь на другий означало б отримати правдоподібні числа з нізвідки.
     */
    @Test
    fun `refuses an answer to another request`() {
        val result = TirePressureDecoder.decode(
            "61 01 A7 4B 00 00 A8 4B 00 00 A6 4C 00 00 A7 4B 00 00",
        )

        assertFalse(result.known)
    }

    /** Розкид — те, за чим дивляться на цей екран: одне колесо нижче за решту. */
    @Test
    fun `reports the spread between the wheels`() {
        val result = TirePressureDecoder.decode(
            "62 C0 0B A7 4B 00 00 A7 4B 00 00 A7 4B 00 00 8C 4B 00 00",
        )

        assertTrue(result.known)
        assertEquals(0.372, result.spreadBar, 0.01)
        assertEquals(result.pressuresBar.min(), result.minPressureBar!!, 0.001)
    }
}
