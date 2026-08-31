package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.EnergySession

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculationEngineTest {

    @Test
    fun `power is negative while discharging`() {
        val result = CalculationEngine.calculate(
            BmsData(batteryVoltage = 366.0, batteryCurrent = -100.0),
            CellData(),
            EnergySession(),
        )
        assertEquals(-36.6, result.powerKw, 0.0001)
    }

    @Test
    fun `power is positive while charging`() {
        val result = CalculationEngine.calculate(
            BmsData(batteryVoltage = 380.0, batteryCurrent = 25.0),
            CellData(),
            EnergySession(),
        )
        assertEquals(9.5, result.powerKw, 0.0001)
    }

    @Test
    fun `cell spread ignores unread cells`() {
        val result = CalculationEngine.calculate(
            BmsData(),
            CellData(cellVoltages = listOf(0.0, 3.90, 3.85, 0.0, 4.05)),
            EnergySession(),
        )

        assertEquals(3.85, result.minCellVoltage, 0.0001)
        assertEquals(4.05, result.maxCellVoltage, 0.0001)
        assertEquals(0.20, result.cellDeltaVolts, 0.0001)
    }

    @Test
    fun `trip energy is the difference from the mark taken at connect`() {
        val bms = BmsData(
            displaySoc = 70.0,
            cumulativeEnergyChargedKwh = 6125.0,
            cumulativeEnergyDischargedKwh = 5991.5,
        )
        val session = EnergySession(
            startedChargedKwh = 6123.4,
            startedDischargedKwh = 5987.6,
            isStarted = true,
        )

        val result = CalculationEngine.calculate(bms, CellData(), session)

        assertEquals(3.9, result.consumedKwh, 0.0001)
        assertEquals(1.6, result.recoveredKwh, 0.0001)
        assertEquals(2.3, result.netKwh, 0.0001)
    }

    /** До першого зчитування з лічильниками показувати нічого. */
    @Test
    fun `trip energy stays at zero while there is no mark`() {
        val bms = BmsData(displaySoc = 70.0, cumulativeEnergyDischargedKwh = 5991.5)

        val result = CalculationEngine.calculate(bms, CellData(), EnergySession())

        assertEquals(0.0, result.consumedKwh, 0.0001)
    }

    /**
     * Лічильник монотонний, але коротка відповідь могла обнулити поле; від'ємна
     * витрата виглядала б як помилка вимірювання, тому вона зрізається до нуля.
     */
    @Test
    fun `trip energy never goes negative`() {
        val bms = BmsData(displaySoc = 70.0, cumulativeEnergyDischargedKwh = 5000.0)
        val session = EnergySession(startedDischargedKwh = 5987.6, isStarted = true)

        assertEquals(0.0, CalculationEngine.calculate(bms, CellData(), session).consumedKwh, 0.0001)
    }

    @Test
    fun `cell spread is zero when nothing was read`() {
        val result = CalculationEngine.calculate(BmsData(), CellData(cellVoltages = listOf(0.0, 0.0)), EnergySession())

        assertEquals(0.0, result.minCellVoltage, 0.0001)
        assertEquals(0.0, result.maxCellVoltage, 0.0001)
        assertEquals(0.0, result.cellDeltaVolts, 0.0001)
    }
}
