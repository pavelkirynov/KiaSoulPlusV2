package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame
import com.kirianov.kiasoulevplus2.Data.ChargerType
import com.kirianov.kiasoulevplus2.Data.VehicleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastDecoderTest {

    private fun frame(id: String, vararg bytes: Int) = CanBroadcastFrame(id, bytes.toList())

    /**
     * Звірка з довідника: панель показує 188459.5 км -> сире 1884595 = 0x1CC1B3,
     * і в кадрі це лежить little-endian у байтах 5, 6, 7: B3 C1 1C.
     *
     * Саме через зворотний порядок і десяті кілометра пошук цілого числа км
     * у прямому порядку байтів ніколи цього не знаходив.
     */
    @Test
    fun `decodes the odometer little endian in tenths of a kilometre`() {
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("4F0", 0x00, 0x00, 0x00, 0x00, 0x00, 0xB3, 0xC1, 0x1C)),
        )

        assertEquals(188_459.5, result.odometerKm, 0.001)
        assertTrue(result.hasOdometer)
    }

    @Test
    fun `decodes speed from the same frame`() {
        // b1 = 0x5A = 90, старший біт швидкості в b2 нульовий -> 45 км/год
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("4F0", 0x00, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )

        assertEquals(45.0, result.speedKmh, 0.001)
    }

    @Test
    fun `speed uses the ninth bit from the next byte`() {
        // b1 = 0x00, b2 бит 0 = 1 -> (0 | 256) / 2 = 128 км/год
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("4F0", 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)),
        )

        assertEquals(128.0, result.speedKmh, 0.001)
    }

    @Test
    fun `decodes the dashboard soc with its tenth`() {
        // b5 = 0xA0 = 160 -> 80.0 %, b6 & 0x07 = 3 -> +0.3
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("594", 0, 0, 0, 0, 0, 0xA0, 0x03, 0)),
        )

        assertEquals(80.3, result.displaySocPercent, 0.001)
    }

    @Test
    fun `decodes the precise soc from the bms frame`() {
        // (0x50 << 8) + 0x80 = 20608 -> /256 = 80.5 %
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("598", 0, 0, 0, 0, 0x80, 0x50, 0, 0)),
        )

        assertEquals(80.5, result.preciseSocPercent, 0.001)
    }

    @Test
    fun `decodes the remaining range`() {
        // (0x64 << 1) + (0x80 >> 7) = 200 + 1 = 201 км
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("200", 0, 0x80, 0x64, 0, 0, 0, 0, 0)),
        )

        assertEquals(201, result.rangeKm)
    }

    @Test
    fun `decodes the ambient temperature below zero`() {
        // b5 = 0x46 = 70 -> 70/2 - 40 = -5.0 °C
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("653", 0, 0, 0, 0, 0, 0x46, 0, 0)),
        )

        assertEquals(-5.0, result.ambientTempC, 0.001)
        assertTrue("−5 °C це дійсне значення, а не «немає даних»", result.hasAmbientTemp)
    }

    @Test
    fun `minus one degree is a real reading, not the missing-data marker`() {
        // b5 = 0x4E = 78 -> 78/2 - 40 = -1.0 °C, тобто рівно старе значення NO_DATA
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("653", 0, 0, 0, 0, 0, 0x4E, 0, 0)),
        )

        assertEquals(-1.0, result.ambientTempC, 0.001)
        assertTrue(result.hasAmbientTemp)
    }

    @Test
    fun `decodes the car clock`() {
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("567", 0, 21, 47, 30, 0, 0, 0, 0)),
        )

        assertEquals(21 * 60 + 47, result.clockMinutesOfDay)
        assertTrue(result.hasClock)
    }

    /** Midnight is a real reading, so the marker cannot be a number. */
    @Test
    fun `midnight is a real clock reading`() {
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("567", 0, 0, 0, 0, 0, 0, 0, 0)),
        )

        assertEquals(0, result.clockMinutesOfDay)
        assertTrue(result.hasClock)
    }

    /** Значення поза межами доби — це сміття в кадрі, а не «північ». */
    @Test
    fun `an impossible clock value is ignored`() {
        val previous = VehicleData(clockMinutesOfDay = 10 * 60)

        val result = BroadcastDecoder.merge(
            previous,
            listOf(frame("567", 0, 47, 99, 0, 0, 0, 0, 0)),
        )

        assertEquals(10 * 60, result.clockMinutesOfDay)
    }

    @Test
    fun `the clock is unknown until frame 567 arrives`() {
        assertFalse(VehicleData().hasClock)
    }

    @Test
    fun `temperature is unknown until frame 653 arrives`() {
        assertFalse(VehicleData().hasAmbientTemp)
    }

    @Test
    fun `decodes charging state and power`() {
        // b3 != 0 -> заряджається, b5 = 0x0E -> J1772, (0x0B << 8) + 0x00 = 2816 -> 11 кВт
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("581", 0, 0, 0, 0x01, 0, 0x0E, 0x00, 0x0B)),
        )

        assertTrue(result.charging.isCharging)
        assertEquals(ChargerType.J1772, result.charging.chargerType)
        assertEquals(11.0, result.charging.powerKw, 0.001)
    }

    /**
     * За одне вікно монітора приходять не всі кадри. Якщо 653 не було, температура
     * має лишитися старою, а не обнулитися.
     */
    @Test
    fun `values missing from this window keep their previous reading`() {
        val previous = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("653", 0, 0, 0, 0, 0, 0x64, 0, 0)),
        )
        assertEquals(10.0, previous.ambientTempC, 0.001)

        val result = BroadcastDecoder.merge(
            previous,
            listOf(frame("4F0", 0x00, 0x00, 0x00, 0x00, 0x00, 0xB3, 0xC1, 0x1C)),
        )

        assertEquals(10.0, result.ambientTempC, 0.001)
        assertTrue(result.hasOdometer)
    }

    @Test
    fun `a short frame is ignored rather than decoded into nonsense`() {
        val result = BroadcastDecoder.merge(VehicleData(), listOf(frame("4F0", 0x00, 0x5A)))

        assertFalse(result.hasOdometer)
        assertFalse(result.hasSpeed)
    }

    @Test
    fun `unknown ids are ignored`() {
        val result = BroadcastDecoder.merge(
            VehicleData(),
            listOf(frame("7FF", 1, 2, 3, 4, 5, 6, 7, 8)),
        )

        assertEquals(VehicleData(), result)
    }
}
