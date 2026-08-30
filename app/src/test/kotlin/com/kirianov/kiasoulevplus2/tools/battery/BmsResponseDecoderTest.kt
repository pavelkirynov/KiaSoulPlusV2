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
        socRaw: Int = 0xA0,        // 160 / 2 = 80.0 %
        currentRaw: Int = 0xFFF6,  // -10 -> -1.0 A
        voltageRaw: Int = 0x0E4C,  // 3660 -> 366.0 V
        tempRaw: Int = 0x19,       // 25 °C
    ): List<Int> = MutableList(20) { 0 }.apply {
        this[6] = socRaw
        this[12] = currentRaw shr 8 and 0xFF
        this[13] = currentRaw and 0xFF
        this[14] = voltageRaw shr 8 and 0xFF
        this[15] = voltageRaw and 0xFF
        this[16] = tempRaw
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
