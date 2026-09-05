package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {

    private fun bytesOf(header: List<Int>, vin: String): List<Int> =
        header + vin.map { it.code }

    /** Звичайна відповідь на `09 02`: заголовок, номер порції, сімнадцять символів. */
    @Test
    fun `a plain answer yields the vin`() {
        val bytes = bytesOf(listOf(0x49, 0x02, 0x01), "KNDJX3AE5F7001234")

        assertEquals("KNDJX3AE5F7001234", VinDecoder.decode(bytes))
    }

    /**
     * Клони ELM327 добивають відповідь нулями. Просто взяти сімнадцять байтів після
     * заголовка означало б інколи отримати VIN із дірками.
     */
    @Test
    fun `padding zeroes do not get into the vin`() {
        val bytes = listOf(0x49, 0x02, 0x01, 0x00, 0x00) + "KNDJX3AE5F7001234".map { it.code }

        assertEquals("KNDJX3AE5F7001234", VinDecoder.decode(bytes))
    }

    /** Службові байти перед заголовком трапляються — прив'язка до нуля підводила. */
    @Test
    fun `the header is found even when something precedes it`() {
        val bytes = listOf(0x00, 0x00, 0x14) + bytesOf(listOf(0x49, 0x02, 0x01), "KNDJX3AE5F7001234")

        assertEquals("KNDJX3AE5F7001234", VinDecoder.decode(bytes))
    }

    /** Обрізана відповідь — не VIN. Половина номера гірша за чесне «не знаємо». */
    @Test
    fun `a truncated answer is refused`() {
        val bytes = bytesOf(listOf(0x49, 0x02, 0x01), "KNDJX3AE5F7")

        assertNull(VinDecoder.decode(bytes))
    }

    /** Чужа відповідь без заголовка `49 02` не має ставати VIN-ом. */
    @Test
    fun `another answer is not mistaken for a vin`() {
        assertNull(VinDecoder.decode(listOf(0x61, 0x01, 0x00, 0x00, 0x00)))
        assertNull(VinDecoder.decode(emptyList()))
    }

    /**
     * Літер I, O та Q у VIN не буває навмисно — щоб не плутати їх з одиницею й
     * нулем. Символ, якого там бути не може, означає, що ми читаємо не VIN.
     */
    @Test
    fun `letters that cannot appear in a vin are rejected`() {
        assertFalse(VinDecoder.isValid("KNDJX3AE5F700123O"))
        assertTrue(VinDecoder.isValid("KNDJX3AE5F7001234"))
        assertFalse(VinDecoder.isValid("KNDJX3AE5F700123"))
    }

    @Test
    fun `the short form keeps the tail`() {
        assertEquals("001234", VinDecoder.shortForm("KNDJX3AE5F7001234"))
        assertEquals("ABC", VinDecoder.shortForm("ABC"))
    }
}
