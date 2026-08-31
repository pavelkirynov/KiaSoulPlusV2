package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CalculationBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        GeneralData.reset()
        CalculationBlock().start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    @Test
    fun `power follows the readings written by the decoder block`() {
        GeneralData.updateBms(BmsData(displaySoc = 80.0, batteryVoltage = 366.0, batteryCurrent = -50.0))

        assertEquals(-18.3, GeneralData.state.value.calculated.powerKw, 0.0001)
    }

    @Test
    fun `cell spread follows the decoded cells`() {
        GeneralData.updateCells(CellData(cellVoltages = listOf(3.80, 3.95)))

        val calculated = GeneralData.state.value.calculated
        assertEquals(3.80, calculated.minCellVoltage, 0.0001)
        assertEquals(0.15, calculated.cellDeltaVolts, 0.0001)
    }

    /** Блок пише в те саме сховище, яке слухає: перерахунок не має зациклюватися. */
    @Test
    fun `writing the result back does not retrigger the block`() {
        GeneralData.updateBms(BmsData(displaySoc = 50.0, batteryVoltage = 300.0, batteryCurrent = 10.0))

        assertEquals(3.0, GeneralData.state.value.calculated.powerKw, 0.0001)
    }
}
