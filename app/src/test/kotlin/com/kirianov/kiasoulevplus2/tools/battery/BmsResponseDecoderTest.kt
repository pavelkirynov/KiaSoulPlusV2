package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsResponseDecoderTest {

    /**
     * Синтетичний кадр 21 01 із відомими значеннями на позиціях, які читає декодер:
     * [6] SOC*2, [12..13] струм, [14..15] напруга, [16] температура.
     */
    private fun frame(
        socRaw: Int = 0xA0,           // 160 / 2 = 80.0 %
        currentRaw: Int = 0xFFF6,     // -10 -> -1.0 A
        voltageRaw: Int = 0x0E4C,     // 3660 -> 366.0 V
        tempRaw: Int = 0x19,          // 25 °C
        chargedAhRaw: Long = 734_373,      // -> 73437.3 Ач
        dischargedAhRaw: Long = 732_608,   // -> 73260.8 Ач
        chargedRaw: Long = 269_379,        // -> 26937.9 кВт·год
        dischargedRaw: Long = 258_908,     // -> 25890.8 кВт·год
        size: Int = 52,
    ): List<Int> = MutableList(size) { 0 }.apply {
        this[6] = socRaw
        this[12] = currentRaw shr 8 and 0xFF
        this[13] = currentRaw and 0xFF
        this[14] = voltageRaw shr 8 and 0xFF
        this[15] = voltageRaw and 0xFF
        this[16] = tempRaw
        if (size >= 36) putUnsigned32(32, chargedAhRaw)
        if (size >= 40) putUnsigned32(36, dischargedAhRaw)
        if (size >= 44) putUnsigned32(40, chargedRaw)
        if (size >= 48) putUnsigned32(44, dischargedRaw)
    }

    private fun MutableList<Int>.putUnsigned32(index: Int, value: Long) {
        this[index] = (value shr 24 and 0xFF).toInt()
        this[index + 1] = (value shr 16 and 0xFF).toInt()
        this[index + 2] = (value shr 8 and 0xFF).toInt()
        this[index + 3] = (value and 0xFF).toInt()
    }

    @Test
    fun `decodes soc voltage current and temperature`() {
        val data = BmsResponseDecoder.decode(frame())

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertEquals(-1.0, data.batteryCurrent, 0.001)
        assertEquals(25.0, data.batteryTempC, 0.001)
        assertTrue(data.hasData)
    }

    @Test
    fun `decodes a charging current as positive`() {
        val data = BmsResponseDecoder.decode(frame(currentRaw = 0x00C8)) // 200 -> 20.0 A
        assertEquals(20.0, data.batteryCurrent, 0.001)
    }

    @Test
    fun `decodes a temperature below zero`() {
        val data = BmsResponseDecoder.decode(frame(tempRaw = 0xFB)) // -5 °C
        assertEquals(-5.0, data.batteryTempC, 0.001)
    }

    @Test
    fun `reports no data for a truncated frame instead of showing zeroes`() {
        val data = BmsResponseDecoder.decode(listOf(0x61, 0x01, 0x00))

        assertEquals(BmsData.NO_DATA, data.displaySoc, 0.001)
        assertFalse(data.hasData)
    }

    @Test
    fun `reports no data for an empty frame`() {
        assertFalse(BmsResponseDecoder.decode(emptyList()).hasData)
    }

    @Test
    fun `decodes the lifetime energy counters`() {
        val data = BmsResponseDecoder.decode(frame())

        assertEquals(26937.9, data.cumulativeEnergyChargedKwh, 0.001)
        assertEquals(25890.8, data.cumulativeEnergyDischargedKwh, 0.001)
        assertEquals(73437.3, data.cumulativeChargedAh, 0.001)
        assertEquals(73260.8, data.cumulativeDischargedAh, 0.001)
        assertTrue(data.hasEnergyCounters)
    }

    /**
     * Лічильники лежать далеко в кадрі. Якщо адаптер віддав коротшу відповідь,
     * заряд і напруга все одно мають дійти — інакше одна відсутня величина
     * гасила б увесь екран.
     */
    @Test
    fun `a frame too short for the counters still yields soc and voltage`() {
        val data = BmsResponseDecoder.decode(frame(size = 20))

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertFalse(data.hasEnergyCounters)
        assertEquals(0.0, data.cumulativeEnergyDischargedKwh, 0.001)
    }

    /** Читання не тих байтів дало б правдоподібне сміття; краще прочерк. */
    @Test
    fun `an implausible counter is reported as absent`() {
        val data = BmsResponseDecoder.decode(frame(dischargedRaw = 4_000_000_000L))

        assertFalse(data.hasEnergyCounters)
    }

    @Test
    fun `decodes an end-to-end raw adapter reply`() {
        // Той самий кадр, але у вигляді, в якому його віддає ELM327.
        val raw = "0: 61 01 00 00 00 00 A0 00\r" +
            "1: 00 00 00 00 FF F6 0E 4C\r" +
            "2: 19 00 00 00 00 00 00 00\r>"

        val data = BmsResponseDecoder.decode(FrameParser.parse(raw))

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertEquals(-1.0, data.batteryCurrent, 0.001)
        assertEquals(25.0, data.batteryTempC, 0.001)
    }

    /**
     * Регресія на реальну помилку: як кВт·год читалися зсуви 32 і 36, тобто
     * амперу-години. Числа взяті з Soul EV Spy на тій самій машині, тому цей тест
     * ловить саме підміну однієї одиниці іншою, а не абстрактне значення.
     */
    @Test
    fun `the kilowatt-hour counters are not the ampere-hour ones`() {
        val data = BmsResponseDecoder.decode(frame())

        // Ач і кВт·год — різні числа з різних місць кадру.
        assertNotEquals(data.cumulativeChargedAh, data.cumulativeEnergyChargedKwh, 0.001)
        assertNotEquals(data.cumulativeDischargedAh, data.cumulativeEnergyDischargedKwh, 0.001)

        // Перехресна перевірка, якою помилка й виявилася: кВт·год поділити на Ач
        // мусить дати середню напругу пакета. Поза цією смугою прочитано не ті байти.
        val chargeVolts = data.cumulativeEnergyChargedKwh / data.cumulativeChargedAh * 1000.0
        val dischargeVolts = data.cumulativeEnergyDischargedKwh / data.cumulativeDischargedAh * 1000.0

        assertTrue("Зарядна напруга $chargeVolts В поза межами пакета", chargeVolts in 300.0..420.0)
        assertTrue("Розрядна напруга $dischargeVolts В поза межами пакета", dischargeVolts in 300.0..420.0)

        // Заряд іде на вищій напрузі, ніж розряд: під струмом заряду напруга росте,
        // під струмом розряду просідає.
        assertTrue("Зарядна напруга мусить бути вищою за розрядну", chargeVolts > dischargeVolts)
    }

    /** Кадр, обрізаний посередині лічильників, не має занулити ті, що вже прочитані. */
    @Test
    fun `a frame cut between counters keeps what it managed to read`() {
        val data = BmsResponseDecoder.decode(frame(size = 44))

        assertEquals(73437.3, data.cumulativeChargedAh, 0.001)
        assertEquals(73260.8, data.cumulativeDischargedAh, 0.001)
        assertEquals(26937.9, data.cumulativeEnergyChargedKwh, 0.001)
        assertEquals(0.0, data.cumulativeEnergyDischargedKwh, 0.001)
    }
}
