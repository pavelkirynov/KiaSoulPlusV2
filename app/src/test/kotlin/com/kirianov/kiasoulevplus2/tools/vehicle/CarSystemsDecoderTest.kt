package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame
import com.kirianov.kiasoulevplus2.Data.CarSystems
import com.kirianov.kiasoulevplus2.Data.LightsMode
import com.kirianov.kiasoulevplus2.Data.TurnSignal
import com.kirianov.kiasoulevplus2.Data.WiperSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSystemsDecoderTest {

    private fun frame(id: String, vararg bytes: Int) = CanBroadcastFrame(id, bytes.toList())

    private fun decode(vararg frames: CanBroadcastFrame) =
        CarSystemsDecoder.merge(CarSystems(), frames.toList())

    /** 1500 кроків на 30 = 50 км/год; кожне колесо двома байтами, молодший перший. */
    @Test
    fun `decodes four wheel speeds little endian divided by thirty`() {
        val slow = 1_470 // 49 км/год
        val fast = 1_530 // 51 км/год
        val result = decode(
            frame(
                "4B0",
                slow and 0xFF, slow shr 8,
                fast and 0xFF, fast shr 8,
                slow and 0xFF, slow shr 8,
                fast and 0xFF, fast shr 8,
            ),
        )

        assertTrue(result.wheels.known)
        assertEquals(49.0, result.wheels.frontLeftKmh, 0.001)
        assertEquals(51.0, result.wheels.frontRightKmh, 0.001)
        assertEquals(50.0, result.wheels.averageKmh, 0.001)
        assertEquals(2.0, result.wheels.spreadKmh, 0.001)
    }

    /**
     * Кадр із самих 0xFF дає 2184 км/год — і саме він приходить, коли адаптер
     * захлинувся. Такий кадр не має права нічого оновити.
     */
    @Test
    fun `refuses a wheel frame full of ff`() {
        val result = decode(frame("4B0", 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))

        assertFalse(result.wheels.known)
    }

    @Test
    fun `reads the parking brake from bit three of byte two`() {
        assertTrue(decode(frame("433", 0x00, 0x00, 0x08)).brake.parkingBrakeOn)
        assertFalse(decode(frame("433", 0x00, 0x00, 0x00)).brake.parkingBrakeOn)
        assertTrue(decode(frame("433", 0x00, 0x00, 0x00)).brake.known)
    }

    @Test
    fun `decodes lights turn signal and wipers from frame 050`() {
        val result = decode(frame("050", 0x00, 0x02, 0x11, 0x00))

        assertTrue(result.cabin.known)
        assertEquals(LightsMode.On, result.cabin.lights)
        assertEquals(TurnSignal.Right, result.cabin.turnSignal)
        assertEquals(WiperSpeed.Normal, result.cabin.wipers)
    }

    /**
     * Перебійний режим: щабель лежить у старшій половині байта 1, і 0x80 там
     * НАЙПОВІЛЬНІШИЙ — порядок зворотний до звичного.
     */
    @Test
    fun `reads the intermittent wiper step from the high nibble`() {
        val slowest = decode(frame("050", 0x00, 0x80, 0x02, 0x00)).cabin
        val fastest = decode(frame("050", 0x00, 0x00, 0x02, 0x00)).cabin

        assertEquals(WiperSpeed.Intermittent, slowest.wipers)
        assertEquals(0, slowest.wiperStep)
        assertEquals(WiperSpeed.Intermittent, fastest.wipers)
        assertEquals(4, fastest.wiperStep)
    }

    /** Щабель має сенс лише в перебійному режимі: в інших ті самі біти означають інше. */
    @Test
    fun `keeps the wiper step at zero outside the intermittent mode`() {
        val result = decode(frame("050", 0x00, 0x80, 0x01, 0x00)).cabin

        assertEquals(WiperSpeed.Normal, result.wipers)
        assertEquals(0, result.wiperStep)
    }

    @Test
    fun `decodes the clock from bytes one to three`() {
        val result = decode(frame("567", 0x00, 14, 35, 7, 0x00, 0x00, 0x00, 0x00))

        assertTrue(result.clock.known)
        assertEquals("14:35:07", result.clock.text)
    }

    @Test
    fun `refuses an impossible clock`() {
        val result = decode(frame("567", 0x00, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00))

        assertFalse(result.clock.known)
    }

    /**
     * ГОЛОВНЕ ВИПРАВЛЕННЯ ДО SoulEVSpy. Їхня формула складає в швидкість старший
     * біт байта 2 і на нерухомій машині з увімкненим запалюванням дає рівно
     * 128 км/год. Ми читаємо швидкість самим байтом 1, а ті біти — як запалювання.
     */
    @Test
    fun `does not fold the ignition bit into the speed`() {
        val result = decode(frame("4F2", 0x00, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, 0xB0))

        assertTrue(result.drive.known)
        assertEquals(0.0, result.drive.speedKmh, 0.001)
        assertTrue(result.drive.ignitionOn)
    }

    @Test
    fun `reads the speed of frame 4F2 as half of byte one`() {
        val result = decode(frame("4F2", 0x00, 100, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))

        assertEquals(50.0, result.drive.speedKmh, 0.001)
        assertFalse(result.drive.ignitionOn)
    }

    /** Кадри приходять різними вікнами, тож нове домішується до старого. */
    @Test
    fun `keeps values from frames that were not in this window`() {
        val first = decode(frame("433", 0x00, 0x00, 0x08))
        val second = CarSystemsDecoder.merge(
            first,
            listOf(frame("567", 0x00, 1, 2, 3, 0x00, 0x00, 0x00, 0x00)),
        )

        assertTrue(second.brake.parkingBrakeOn)
        assertEquals("01:02:03", second.clock.text)
    }

    @Test
    fun `ignores frames it does not own`() {
        val result = decode(frame("4F0", 0x00, 0x64, 0x00, 0x00, 0x00, 0xB3, 0xC1, 0x1C))

        assertFalse(result.anyKnown)
    }

    /** Короткий кадр не оновлює нічого: інакше зсув поїхав би тихо. */
    @Test
    fun `refuses frames that are too short`() {
        assertFalse(decode(frame("4B0", 0x00, 0x01, 0x00)).wheels.known)
        assertFalse(decode(frame("433", 0x00, 0x00)).brake.known)
        assertFalse(decode(frame("050", 0x00, 0x02)).cabin.known)
        assertFalse(decode(frame("567", 0x00, 12, 30)).clock.known)
        assertFalse(decode(frame("4F2", 0x00, 100, 0x00)).drive.known)
    }
}
