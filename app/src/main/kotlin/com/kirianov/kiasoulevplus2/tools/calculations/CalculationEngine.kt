package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.EnergySession

/**
 * Рахує похідні величини з уже зчитаних даних BMS та комірок.
 * Чиста функція без стану — саме тому її можна покрити тестами без емулятора.
 */
object CalculationEngine {

    fun calculate(bms: BmsData, cells: CellData, session: EnergySession): CalculatedData {
        val valid = cells.cellVoltages.filter { it > MIN_PLAUSIBLE_CELL_VOLTAGE }
        val minCell = valid.minOrNull() ?: 0.0
        val maxCell = valid.maxOrNull() ?: 0.0

        return CalculatedData(
            powerKw = bms.batteryVoltage * bms.batteryCurrent / 1000.0,
            minCellVoltage = minCell,
            maxCellVoltage = maxCell,
            cellDeltaVolts = if (valid.isEmpty()) 0.0 else maxCell - minCell,
            consumedKwh = sinceSessionStart(bms.cumulativeEnergyDischargedKwh, session.startedDischargedKwh, bms, session),
            recoveredKwh = sinceSessionStart(bms.cumulativeEnergyChargedKwh, session.startedChargedKwh, bms, session),
        )
    }

    /**
     * BMS віддає лише підсумок за весь час, тому витрата за поїздку — це різниця
     * з позначкою на момент під'єднання. Поки позначки немає або лічильники
     * не зчиталися, показувати нема чого.
     */
    private fun sinceSessionStart(
        current: Double,
        atStart: Double,
        bms: BmsData,
        session: EnergySession,
    ): Double {
        if (!session.isStarted || !bms.hasEnergyCounters) return 0.0
        return (current - atStart).coerceAtLeast(0.0)
    }

    /** Нижче цього порога значення вважається «комірку не зчитано», а не реальною напругою. */
    private const val MIN_PLAUSIBLE_CELL_VOLTAGE = 0.5
}
