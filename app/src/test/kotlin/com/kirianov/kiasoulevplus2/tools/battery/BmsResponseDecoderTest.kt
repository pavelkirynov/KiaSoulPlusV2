package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        chargedRaw: Long = 61_234,    // -> 6123.4 кВт·год
        dischargedRaw: Long = 59_876, // -> 5987.6 кВт·год
        size: Int = 44,
    ): List<Int> = MutableList(size) { 0 }.apply {
        this[6] = socRaw
        this[12] = currentRaw shr 8 and 0xFF
        this[13] = currentRaw and 0xFF
        this[14] = voltageRaw shr 8 and 0xFF
        this[15] = voltageRaw and 0xFF
        this[16] = tempRaw
        if (size >= 40) {
            putUnsigned32(32, chargedRaw)
            putUnsigned32(36, dischargedRaw)
        }
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

        assertEquals(6123.4, data.cumulativeEnergyChargedKwh, 0.001)
        assertEquals(5987.6, data.cumulativeEnergyDischargedKwh, 0.001)
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

        val data = BmsResponseDecoder.decode(BmsFrameParser.parse(raw))

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertEquals(-1.0, data.batteryCurrent, 0.001)
        assertEquals(25.0, data.batteryTempC, 0.001)
    }
}
