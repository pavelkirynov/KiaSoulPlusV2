package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.TripHistory
import com.kirianov.kiasoulevplus2.Data.WindowStats

/**
 * Рахує похідні величини з уже зчитаних даних.
 * Чиста функція без стану — саме тому її можна покрити тестами без емулятора.
 */
object CalculationEngine {

    fun calculate(
        bms: BmsData,
        cells: CellData,
        history: TripHistory,
        window: ConsumptionWindow,
    ): CalculatedData {
        val valid = cells.cellVoltages.filter { it > MIN_PLAUSIBLE_CELL_VOLTAGE }
        val minCell = valid.minOrNull() ?: 0.0
        val maxCell = valid.maxOrNull() ?: 0.0

        return CalculatedData(
            powerKw = bms.batteryVoltage * bms.batteryCurrent / 1000.0,
            minCellVoltage = minCell,
            maxCellVoltage = maxCell,
            cellDeltaVolts = if (valid.isEmpty()) 0.0 else maxCell - minCell,
            trip = statsFor(history, ConsumptionWindow.Trip),
            window = statsFor(history, window),
        )
    }

    /**
     * Різниця між першим і останнім знімком діапазону. BMS віддає лише підсумки за
     * весь час, тому будь-яка витрата «за зараз» — це саме різниця двох знімків.
     */
    fun statsFor(history: TripHistory, window: ConsumptionWindow): WindowStats {
        val latest = history.samples.lastOrNull() ?: return WindowStats()
        val start = history.startOf(window) ?: return WindowStats()

        return WindowStats(
            // Відстань беремо лише зі знімків, де пробіг відомий: знімок із «пробіг
            // невідомий» не має права зробити відстань рівною всьому пробігу авто.
            distanceKm = history.travelledKm(from = start) ?: 0.0,
            durationMs = (latest.elapsedMs - start.elapsedMs).coerceAtLeast(0L),
            consumedKwh = (latest.dischargedKwh - start.dischargedKwh).coerceAtLeast(0.0),
            recoveredKwh = (latest.chargedKwh - start.chargedKwh).coerceAtLeast(0.0),
            isComplete = history.covers(window),
        )
    }

    /** Нижче цього порога значення вважається «комірку не зчитано», а не реальною напругою. */
    private const val MIN_PLAUSIBLE_CELL_VOLTAGE = 0.5
}
