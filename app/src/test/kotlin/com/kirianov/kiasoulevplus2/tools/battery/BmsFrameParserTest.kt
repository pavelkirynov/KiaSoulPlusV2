package com.kirianov.kiasoulevplus2.tools.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsFrameParserTest {

    @Test
    fun `parses a plain single-line hex response`() {
        assertEquals(listOf(0x61, 0x01, 0xFF), BmsFrameParser.parse("61 01 FF"))
    }

    @Test
    fun `strips the ELM prompt and search noise`() {
        val raw = "SEARCHING...\r61 01 AB\r>"
        assertEquals(listOf(0x61, 0x01, 0xAB), BmsFrameParser.parse(raw))
    }

    @Test
    fun `strips ISO-TP frame index prefixes from every line`() {
        val raw = "0: 61 01 FF\r1: 10 20 30\r2: 40 50 60\r>"
        assertEquals(
            listOf(0x61, 0x01, 0xFF, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60),
            BmsFrameParser.parse(raw),
        )
    }

    @Test
    fun `returns nothing for the adapter error replies`() {
        assertTrue(BmsFrameParser.parse("NO DATA").isEmpty())
        assertTrue(BmsFrameParser.parse("CAN ERROR").isEmpty())
        assertTrue(BmsFrameParser.parse("UNABLE TO CONNECT").isEmpty())
        assertTrue(BmsFrameParser.parse("").isEmpty())
        assertTrue(BmsFrameParser.parse("   ").isEmpty())
    }

    @Test
    fun `error replies are recognised regardless of case`() {
        assertTrue(BmsFrameParser.parse("no data").isEmpty())
    }

    @Test
    fun `ignores tokens that are not two hex digits`() {
        assertEquals(listOf(0xAB, 0xCD), BmsFrameParser.parse("AB ZZ CD 1 123"))
    }

    @Test
    fun `unsigned16 joins the high and low byte`() {
        assertEquals(0x0E4C, BmsFrameParser.unsigned16(listOf(0x0E, 0x4C), 0))
    }

    @Test
    fun `signed16 returns a negative value above the sign boundary`() {
        // 0xFFF6 = -10 -> струм розряду
        assertEquals(-10, BmsFrameParser.signed16(listOf(0xFF, 0xF6), 0))
        assertEquals(10, BmsFrameParser.signed16(listOf(0x00, 0x0A), 0))
    }

    @Test
    fun `signed8 returns a negative temperature`() {
        assertEquals(-5, BmsFrameParser.signed8(listOf(0xFB), 0))
        assertEquals(25, BmsFrameParser.signed8(listOf(0x19), 0))
    }
}
