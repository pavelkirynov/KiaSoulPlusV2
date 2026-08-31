package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculationEngineTest {

    @Test
    fun `power is negative while discharging`() {
        val result = CalculationEngine.calculate(
            BmsData(batteryVoltage = 366.0, batteryCurrent = -100.0),
            CellData(),
        )
        assertEquals(-36.6, result.powerKw, 0.0001)
    }

    @Test
    fun `power is positive while charging`() {
        val result = CalculationEngine.calculate(
            BmsData(batteryVoltage = 380.0, batteryCurrent = 25.0),
            CellData(),
        )
        assertEquals(9.5, result.powerKw, 0.0001)
    }

    @Test
    fun `cell spread ignores unread cells`() {
        val result = CalculationEngine.calculate(
            BmsData(),
            CellData(cellVoltages = listOf(0.0, 3.90, 3.85, 0.0, 4.05)),
        )

        assertEquals(3.85, result.minCellVoltage, 0.0001)
        assertEquals(4.05, result.maxCellVoltage, 0.0001)
        assertEquals(0.20, result.cellDeltaVolts, 0.0001)
    }

    @Test
    fun `cell spread is zero when nothing was read`() {
        val result = CalculationEngine.calculate(BmsData(), CellData(cellVoltages = listOf(0.0, 0.0)))

        assertEquals(0.0, result.minCellVoltage, 0.0001)
        assertEquals(0.0, result.maxCellVoltage, 0.0001)
        assertEquals(0.0, result.cellDeltaVolts, 0.0001)
    }
}
