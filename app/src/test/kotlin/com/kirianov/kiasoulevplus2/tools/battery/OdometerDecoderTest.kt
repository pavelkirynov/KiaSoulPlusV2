package com.kirianov.kiasoulevplus2.tools.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerDecoderTest {

    /** Відповідь щитка на 22 B0 02 з пробігом трьома байтами на індексі 9. */
    private fun frame(km: Long = 123_456, size: Int = 14): List<Int> =
        MutableList(size) { 0 }.apply {
            if (size >= 12) {
                this[9] = (km shr 16 and 0xFF).toInt()
                this[10] = (km shr 8 and 0xFF).toInt()
                this[11] = (km and 0xFF).toInt()
            }
        }

    @Test
    fun `decodes the odometer in kilometres`() {
        val data = OdometerDecoder.decode(frame(km = 123_456))

        assertEquals(123_456.0, data.odometerKm, 0.001)
        assertTrue(data.hasOdometer)
    }

    @Test
    fun `a short frame yields no odometer`() {
        assertFalse(OdometerDecoder.decode(frame(size = 8)).hasOdometer)
        assertFalse(OdometerDecoder.decode(emptyList()).hasOdometer)
    }

    /** Нуль означає «не зчитано», а не «нова машина без пробігу». */
    @Test
    fun `zero is treated as absent`() {
        assertFalse(OdometerDecoder.decode(frame(km = 0)).hasOdometer)
    }

    /** Читання не тих байтів дало б мільйони кілометрів; краще прочерк. */
    @Test
    fun `an implausible reading is rejected`() {
        assertFalse(OdometerDecoder.decode(frame(km = 16_000_000)).hasOdometer)
    }
}
