package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsCommands
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Перевіряє зв'язок «блок Bluetooth поклав сирі кадри -> блок декодерів поклав показники»,
 * не маючи ані адаптера, ані авто: блоки спілкуються лише через GeneralData.
 *
 * Unconfined-диспетчер виконує підписку синхронно на emit, тож після publish* результат
 * уже лежить у сховищі.
 */
class DecoderBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        GeneralData.reset()
        DecoderBlock().start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    @Test
    fun `raw battery frames become decoded readings`() {
        val raw = "0: 61 01 00 00 00 00 A0 00\r" +
            "1: 00 00 00 00 FF F6 0E 4C\r" +
            "2: 19 00 00 00 00 00 00 00\r>"

        GeneralData.publishBatteryFrames(listOf(BmsCommands.REQUEST_BATTERY_MAIN), listOf(raw))

        val bms = GeneralData.state.value.bms
        assertEquals(80.0, bms.displaySoc, 0.001)
        assertEquals(366.0, bms.batteryVoltage, 0.001)
        assertEquals(-1.0, bms.batteryCurrent, 0.001)
        assertEquals(25.0, bms.batteryTempC, 0.001)
    }

    @Test
    fun `raw cell frames become decoded voltages`() {
        val frame = (mutableListOf(0x61, 0x02, 0, 0, 0, 0) + List(32) { 190 + it })
            .joinToString(" ") { "%02X".format(it) } + "\r>"

        GeneralData.publishCellFrames(listOf("21 02"), listOf(frame))

        val cells = GeneralData.state.value.cells
        assertEquals(32, cells.cellVoltages.size)
        assertEquals(3.80, cells.cellVoltages.first(), 0.0001)
    }

    @Test
    fun `an empty reply leaves the readings marked as absent`() {
        GeneralData.publishBatteryFrames(listOf(BmsCommands.REQUEST_BATTERY_MAIN), listOf("NO DATA"))

        assertFalse(GeneralData.state.value.bms.hasData)
    }

    @Test
    fun `repeating the same frames decodes again rather than being skipped`() {
        val frame = (mutableListOf(0x61, 0x02, 0, 0, 0, 0) + List(32) { 190 })
            .joinToString(" ") { "%02X".format(it) } + "\r>"

        GeneralData.publishCellFrames(listOf("21 02"), listOf(frame))
        GeneralData.updateCells(com.kirianov.kiasoulevplus2.Data.CellData())
        GeneralData.publishCellFrames(listOf("21 02"), listOf(frame))

        assertTrue(GeneralData.state.value.cells.cellVoltages.isNotEmpty())
    }
}
