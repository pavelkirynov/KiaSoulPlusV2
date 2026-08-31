package com.kirianov.kiasoulevplus2.tools.format

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Регресія на баг локалі: на німецькій/українській локалі формат за замовчуванням
 * давав «3,85», і введене значення вже не розбиралося назад у число.
 */
class FormattingTest {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.GERMANY)

    @After
    fun tearDown() = Locale.setDefault(originalLocale)

    @Test
    fun `formats with a dot even on a comma locale`() {
        assertEquals("3.85", formatDecimal(3.85, 2))
        assertEquals("366.0 В", formatMeasurement(366.0, 1, "В"))
    }

    @Test
    fun `a formatted value can always be parsed back`() {
        val formatted = formatDecimal(3.857, 2)
        assertEquals(3.86, parseDecimalInput(formatted)!!, 0.0001)
    }

    @Test
    fun `accepts a comma typed on a localised keyboard`() {
        assertEquals(3.85, parseDecimalInput("3,85")!!, 0.0001)
        assertEquals(3.85, parseDecimalInput(" 3.85 ")!!, 0.0001)
    }

    @Test
    fun `rejects text that is not a number`() {
        assertNull(parseDecimalInput(""))
        assertNull(parseDecimalInput("abc"))
    }

    @Test
    fun `the clock is padded to two digits`() {
        assertEquals("00:00:00", formatClock(0))
        assertEquals("09:05:07", formatClock(9 * 3600 + 5 * 60 + 7))
        assertEquals("23:59:59", formatClock(24 * 3600 - 1))
    }

    /** Без знака не видно, спішить годинник чи відстає. */
    @Test
    fun `drift always keeps its sign`() {
        assertEquals("+0 с", formatDriftSigned(0))
        assertEquals("+42 с", formatDriftSigned(42))
        assertEquals("-42 с", formatDriftSigned(-42))
        assertEquals("+3 хв 12 с", formatDriftSigned(192))
        assertEquals("-3 хв 12 с", formatDriftSigned(-192))
    }

    @Test
    fun `the clock rate keeps its sign too`() {
        assertEquals("+145 с/год", formatRate(145.0))
        assertEquals("-145 с/год", formatRate(-145.0))
    }
}
