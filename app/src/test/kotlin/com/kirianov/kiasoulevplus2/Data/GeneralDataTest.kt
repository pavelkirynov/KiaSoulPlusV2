package com.kirianov.kiasoulevplus2.Data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Сховище має лишатися пасивним: воно тільки зберігає те, що поклали блоки,
 * і нічого не рахує саме.
 */
class GeneralDataTest {

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() = GeneralData.reset()

    @Test
    fun `starts disconnected with no battery data`() {
        val state = GeneralData.state.value

        assertFalse(state.isConnected)
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertFalse(state.bms.hasData)
        assertEquals(AppRequest.None, state.request)
    }

    @Test
    fun `does not calculate anything on its own`() {
        GeneralData.updateBms(BmsData(displaySoc = 80.0, batteryVoltage = 366.0, batteryCurrent = -50.0))

        // Похідні величини — робота блока обчислень, а не сховища.
        assertEquals(CalculatedData(), GeneralData.state.value.calculated)
    }

    @Test
    fun `isConnected follows the connection phase`() {
        GeneralData.updateConnection(ConnectionState.Connecting, "Підключення...")
        assertFalse(GeneralData.state.value.isConnected)

        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")
        assertTrue(GeneralData.state.value.isConnected)
    }

    @Test
    fun `a connect request is stored and can be cleared by the bluetooth block`() {
        GeneralData.requestConnect()
        assertEquals(AppRequest.Connect, GeneralData.state.value.request)

        GeneralData.clearRequest()
        assertEquals(AppRequest.None, GeneralData.state.value.request)
    }

    /**
     * Без лічильника повторне однакове зчитування виглядало б як «нічого не змінилося»,
     * і блок декодерів пропустив би його.
     */
    @Test
    fun `republishing identical frames still counts as new data`() {
        GeneralData.publishCellFrames(listOf("21 02"), listOf("61 02 00"))
        val first = GeneralData.state.value.can.cellFrames

        GeneralData.publishCellFrames(listOf("21 02"), listOf("61 02 00"))
        val second = GeneralData.state.value.can.cellFrames

        assertNotEquals(first, second)
        assertEquals(first!!.sequence + 1, second!!.sequence)
    }

    @Test
    fun `battery and cell frames are kept apart`() {
        GeneralData.publishBatteryFrames(listOf("21 01"), listOf("61 01 AA"))
        GeneralData.publishCellFrames(listOf("21 02"), listOf("61 02 BB"))

        val can = GeneralData.state.value.can
        assertEquals(listOf("61 01 AA"), can.batteryFrames?.responses)
        assertEquals(listOf("61 02 BB"), can.cellFrames?.responses)
    }

    @Test
    fun `a manual voltage is stored without dropping the others`() {
        GeneralData.updateManualCells(mapOf(0 to 3.80, 1 to 3.85))
        GeneralData.setManualCell(1, 3.90)

        val manual = GeneralData.state.value.manualCells
        assertEquals(3.80, manual.voltageAt(0), 0.0001)
        assertEquals(3.90, manual.voltageAt(1), 0.0001)
        assertEquals(0.0, manual.voltageAt(50), 0.0001)
    }

    @Test
    fun `the consumption window defaults to the whole trip and can be switched`() {
        assertEquals(ConsumptionWindow.Trip, GeneralData.state.value.consumptionWindow)

        GeneralData.selectConsumptionWindow(ConsumptionWindow.Last5Km)

        assertEquals(ConsumptionWindow.Last5Km, GeneralData.state.value.consumptionWindow)
    }

    @Test
    fun `monitor lines are kept apart from the battery frames`() {
        GeneralData.publishBatteryFrames(listOf("21 01"), listOf("61 01 AA"))
        GeneralData.publishMonitorLines(listOf("4F0 00 00 00 00 00 B3 C1 1C"))

        val can = GeneralData.state.value.can
        assertEquals(listOf("61 01 AA"), can.batteryFrames?.responses)
        assertEquals(listOf("4F0 00 00 00 00 00 B3 C1 1C"), can.monitor?.lines)
    }

    @Test
    fun `every monitor window gets its own sequence`() {
        GeneralData.publishMonitorLines(listOf("4F0 00"))
        val first = GeneralData.state.value.can.monitor?.sequence

        GeneralData.publishMonitorLines(listOf("4F0 00"))
        val second = GeneralData.state.value.can.monitor?.sequence

        assertNotNull(first)
        assertNotNull(second)
        assertTrue("послідовність мусить рости", second!! > first!!)
    }

    @Test
    fun `cell commands default to the three frames that actually carry cells`() {
        assertEquals(listOf("21 02", "21 03", "21 04"), GeneralData.state.value.inputBms.cellCommands)
    }
}
