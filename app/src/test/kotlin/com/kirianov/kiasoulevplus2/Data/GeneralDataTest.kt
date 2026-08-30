package com.kirianov.kiasoulevplus2.Data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
    }

    @Test
    fun `writing bms data also refreshes the calculated values`() {
        GeneralData.updateBmsData(BmsData(displaySoc = 80.0, batteryVoltage = 366.0, batteryCurrent = -50.0))

        assertEquals(-18.3, GeneralData.state.value.calculated.powerKw, 0.0001)
    }

    @Test
    fun `writing cells also refreshes the calculated spread`() {
        GeneralData.updateCellData(CellData(cellVoltages = listOf(3.80, 3.95)))

        val calculated = GeneralData.state.value.calculated
        assertEquals(3.80, calculated.minCellVoltage, 0.0001)
        assertEquals(0.15, calculated.cellDeltaVolts, 0.0001)
    }

    @Test
    fun `isConnected follows the connection phase`() {
        GeneralData.updateConnection(ConnectionState.Connecting, "Підключення...")
        assertFalse(GeneralData.state.value.isConnected)

        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")
        assertTrue(GeneralData.state.value.isConnected)
    }

    @Test
    fun `cell commands default to the three frames that actually carry cells`() {
        assertEquals(listOf("21 02", "21 03", "21 04"), GeneralData.state.value.inputBms.cellCommands)
    }
}
