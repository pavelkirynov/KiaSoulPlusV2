package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsCommands
import com.kirianov.kiasoulevplus2.Data.CellData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellDecoderTest {

    private val decoder = CellDecoder()

    /** Будує сиру відповідь адаптера з 32 комірок: напруга комірки = raw / 50. */
    private fun frame(firstRaw: Int, overrides: Map<Int, Int> = emptyMap()): String {
        val bytes = MutableList(CellDecoder.FIRST_CELL_INDEX) { 0 }
        for (cell in 0 until CellDecoder.CELLS_PER_FRAME) {
            val raw = overrides[cell] ?: (firstRaw + cell)
            bytes += raw shr 8 and 0xFF
            bytes += raw and 0xFF
        }
        return bytes.joinToString(" ") { "%02X".format(it) } + "\r>"
    }

    @Test
    fun `decodes three frames into ninety six cells`() {
        val responses = listOf(frame(175), frame(180), frame(170))

        val result = decoder.decodeResponses(BmsCommands.REQUEST_CELL_VOLTAGES, responses)

        assertEquals(CellData.TOTAL_CELLS, result.cellVoltages.size)
        assertEquals(3.50, result.cellVoltages.first(), 0.0001)
        assertEquals(3.40, result.minVoltage, 0.0001)   // перша комірка третього кадру
        assertEquals(4.22, result.maxVoltage, 0.0001)   // остання комірка другого кадру
        assertEquals(0.82, result.deltaVoltage, 0.0001)
    }

    @Test
    fun `reports the real cell count in the debug log`() {
        val result = decoder.decodeResponses(
            BmsCommands.REQUEST_CELL_VOLTAGES,
            listOf(frame(175), frame(180), frame(170)),
        )
        assertTrue(result.debugInfo.contains("Всього комірок: 96 / 96"))
    }

    @Test
    fun `zeroes out a physically impossible voltage instead of skewing min and max`() {
        // 0x0000 -> 0.0 В: комірка не зчиталася і не повинна стати мінімумом.
        val responses = listOf(frame(175, overrides = mapOf(0 to 0x0000)))

        val result = decoder.decodeResponses(listOf("21 02"), responses)

        assertEquals(CellDecoder.CELLS_PER_FRAME, result.cellVoltages.size)
        assertEquals(0.0, result.cellVoltages[0], 0.0001)
        assertEquals(3.52, result.minVoltage, 0.0001) // друга комірка, а не зіпсована перша
    }

    @Test
    fun `returns no cells for a truncated frame`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf("61 02 00 00 00\r>"))

        assertTrue(result.cellVoltages.isEmpty())
        assertEquals(0.0, result.deltaVoltage, 0.0001)
    }

    @Test
    fun `survives an empty adapter reply and says so in the log`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf(""))

        assertTrue(result.cellVoltages.isEmpty())
        assertTrue(result.debugInfo.contains("ПОРОЖНЬО"))
    }

    @Test
    fun `handles NO DATA without throwing`() {
        val result = decoder.decodeResponses(listOf("21 02", "21 03"), listOf("NO DATA", frame(175)))

        assertEquals(CellDecoder.CELLS_PER_FRAME, result.cellVoltages.size)
    }

    @Test
    fun `falls back to a generic label when there are more responses than commands`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf(frame(175), frame(180)))

        assertTrue(result.debugInfo.contains("кадр 2"))
    }
}
