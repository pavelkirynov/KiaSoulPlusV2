package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsCommands
import com.kirianov.kiasoulevplus2.Data.CellData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellDecoderTest {

    private val decoder = CellDecoder()

    /**
     * Будує відповідь адаптера: 0x61 0x02, службові байти, далі по одному байту на
     * комірку. Напруга комірки = байт * 0.02.
     */
    private fun frame(
        firstRaw: Int = 190,                    // 3.80 В
        cells: Int = CellDecoder.CELLS_PER_FRAME,
        overrides: Map<Int, Int> = emptyMap(),
    ): String {
        val bytes = mutableListOf(0x61, 0x02, 0x00, 0x00, 0x00, 0x00)
        require(bytes.size == CellDecoder.FIRST_CELL_INDEX)
        for (cell in 0 until cells) {
            bytes += overrides[cell] ?: (firstRaw + cell)
        }
        return bytes.joinToString(" ") { "%02X".format(it) } + "\r>"
    }

    @Test
    fun `decodes three frames into ninety six cells`() {
        // Кожен кадр — 32 комірки поспіль, усі в межах правдоподібних 1.5..4.5 В.
        val responses = listOf(frame(175), frame(180), frame(170))

        val result = decoder.decodeResponses(BmsCommands.REQUEST_CELL_VOLTAGES, responses)

        assertEquals(CellData.TOTAL_CELLS, result.cellVoltages.size)
        assertEquals(3.50, result.cellVoltages.first(), 0.0001)
        assertEquals(3.40, result.minVoltage, 0.0001)   // 170 * 0.02, перша комірка 3-го кадру
        assertEquals(4.22, result.maxVoltage, 0.0001)   // 211 * 0.02, остання комірка 2-го кадру
        assertEquals(0.82, result.deltaVoltage, 0.0001)
    }

    /**
     * Регресія на баг, через який екран комірок лишався порожнім: декодер вимагав
     * 71 байт на кадр (32 комірки по 2 байти), а реальна відповідь 21 02 — це
     * 0x27 = 39 байт корисних даних, і жодна комірка не проходила.
     */
    @Test
    fun `decodes a realistic thirty nine byte frame instead of giving up`() {
        val raw = "027\r" +
            "0: 61 02 FF FF FF FF\r" +
            "1: C0 C1 C2 C3 C4 C5 C6\r" +
            "2: C7 C8 C9 CA CB CC CD\r" +
            "3: CE CF D0 D1 D2 D3 D4\r" +
            "4: D5 D6 D7 D8 D9 DA DB\r" +
            "5: DC DD DE DF 00 00 00\r>"

        val result = decoder.decodeResponses(listOf("21 02"), listOf(raw))

        assertEquals(CellDecoder.CELLS_PER_FRAME, result.cellVoltages.size)
        assertEquals(0xC0 * 0.02, result.cellVoltages[0], 0.0001)   // 3.84 В
        assertEquals(0xDF * 0.02, result.cellVoltages[31], 0.0001)  // 4.46 В
        assertTrue(result.debugInfo.contains("Всього комірок: 32 / 96"))
    }

    @Test
    fun `decodes what a short frame carries rather than nothing`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf(frame(cells = 10)))

        assertEquals(10, result.cellVoltages.size)
        assertEquals(3.80, result.cellVoltages.first(), 0.0001)
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
    fun `logs the head of each frame so an offset stays visible`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf(frame(190)))
        assertTrue(result.debugInfo.contains("початок: 61 02 00 00 00 00"))
    }

    @Test
    fun `zeroes out a physically impossible voltage instead of skewing min and max`() {
        // 0x00 -> 0.0 В: комірка не зчиталася і не повинна стати мінімумом.
        val result = decoder.decodeResponses(
            listOf("21 02"),
            listOf(frame(190, overrides = mapOf(0 to 0x00))),
        )

        assertEquals(CellDecoder.CELLS_PER_FRAME, result.cellVoltages.size)
        assertEquals(0.0, result.cellVoltages[0], 0.0001)
        assertEquals(3.82, result.minVoltage, 0.0001) // друга комірка, а не зіпсована перша
        assertTrue(result.debugInfo.contains("незчитаних: 1"))
    }

    @Test
    fun `drops a voltage above the physical ceiling`() {
        // 0xE2 = 226 -> 4.52 В, вище за можливе для комірки: має стати незчитаною.
        val result = decoder.decodeResponses(
            listOf("21 02"),
            listOf(frame(190, overrides = mapOf(31 to 0xE2))),
        )

        assertEquals(0.0, result.cellVoltages[31], 0.0001)
        assertEquals(4.40, result.maxVoltage, 0.0001) // 220 * 0.02, передостання комірка
    }

    @Test
    fun `returns no cells when the frame has nothing past the header`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf("61 02 00 00\r>"))

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
        val result = decoder.decodeResponses(
            listOf("21 02", "21 03"),
            listOf("NO DATA", frame(190)),
        )

        assertEquals(CellDecoder.CELLS_PER_FRAME, result.cellVoltages.size)
        assertTrue(result.debugInfo.contains("немає даних"))
    }

    @Test
    fun `falls back to a generic label when there are more responses than commands`() {
        val result = decoder.decodeResponses(listOf("21 02"), listOf(frame(190), frame(195)))

        assertTrue(result.debugInfo.contains("кадр 2"))
    }
}
