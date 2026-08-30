package com.kirianov.kiasoulevplus2.Data

/**
 * Рахує похідні величини з уже зчитаних даних BMS та комірок.
 * Чиста функція без стану — саме тому її можна покрити тестами без емулятора.
 */
object CalculationEngine {

    fun calculate(bms: BmsData, cells: CellData): CalculatedData {
        val valid = cells.cellVoltages.filter { it > MIN_PLAUSIBLE_CELL_VOLTAGE }
        val minCell = valid.minOrNull() ?: 0.0
        val maxCell = valid.maxOrNull() ?: 0.0

        return CalculatedData(
            powerKw = bms.batteryVoltage * bms.batteryCurrent / 1000.0,
            minCellVoltage = minCell,
            maxCellVoltage = maxCell,
            cellDeltaVolts = if (valid.isEmpty()) 0.0 else maxCell - minCell,
        )
    }

    /** Нижче цього порога значення вважається «комірку не зчитано», а не реальною напругою. */
    private const val MIN_PLAUSIBLE_CELL_VOLTAGE = 0.5
}
