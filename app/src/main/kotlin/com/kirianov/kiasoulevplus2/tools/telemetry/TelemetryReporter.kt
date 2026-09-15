// ====================================================================================
// ЗБІРКА ЗНІМКА ТЕЛЕМЕТРІЇ (TelemetryReporter)
//
// Чиста логіка без стану й без мережі: зі стану застосунку будує рядок для таблиці
// на сервері й вирішує, коли додавати важкі покомірні напруги. Саму відправку
// робить [TelemetryUploader], а цикл — [TelemetryBlock]. Так усе, що можна
// помилитися в даних, перевіряється тестами без емулятора й без інтернету.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.telemetry

import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.json.MiniJson

object TelemetryReporter {

    /**
     * Як часто додавати 96 покомірних напруг. Головні цифри шлються щохвилини, а
     * комірки — рідше: їх багато, а для перебору батареї важлива радше їхня
     * динаміка за хвилини-години, ніж кожна хвилина окремо.
     */
    const val CELLS_INTERVAL_MS = 5 * 60_000L

    /** Чи час знову додати покомірні напруги. Нуль означає «ще жодного разу». */
    fun cellsDue(lastCellsMs: Long, nowMs: Long): Boolean =
        lastCellsMs == 0L || nowMs - lastCellsMs >= CELLS_INTERVAL_MS

    /**
     * Рядок для таблиці `battery_snapshots`, або null — коли слати нема чого.
     *
     * Null у двох випадках: невідоме авто (немає VIN — рядок нікуди прив'язати) або
     * ще не прийшов кадр BMS (немає що вивантажувати). Час знімка не передаємо —
     * його ставить сервер (`captured_at default now()`), щоб не залежати від збитого
     * годинника авто.
     */
    fun buildRow(state: State, includeCells: Boolean): Map<String, Any?>? {
        val vin = state.garage.activeVin
        if (vin.isEmpty()) return null
        val bms = state.bms
        if (!bms.hasData) return null

        val row = linkedMapOf<String, Any?>(
            "vin" to vin,
            "soc" to bms.displaySoc.takeIf { it >= 0.0 },
            "pack_voltage" to bms.batteryVoltage,
            "pack_current" to bms.batteryCurrent,
            "aux_voltage" to bms.auxVolts.takeIf { it > 0.0 },
            "charged_kwh" to bms.cumulativeEnergyChargedKwh.takeIf { it > 0.0 },
            "discharged_kwh" to bms.cumulativeEnergyDischargedKwh.takeIf { it > 0.0 },
            "charged_ah" to bms.cumulativeChargedAh.takeIf { it > 0.0 },
            "discharged_ah" to bms.cumulativeDischargedAh.takeIf { it > 0.0 },
            "min_cell_v" to bms.minCellVolts.takeIf { it > 0.0 },
            "max_cell_v" to bms.maxCellVolts.takeIf { it > 0.0 },
            "min_cell_no" to bms.minCellNumber.takeIf { it > 0 },
            "max_cell_no" to bms.maxCellNumber.takeIf { it > 0 },
            "module_temps" to bms.moduleTempsC.takeIf { it.isNotEmpty() },
            "available_charge_kw" to bms.availableChargeKw.takeIf { it > 0.0 },
            "available_discharge_kw" to bms.availableDischargeKw.takeIf { it > 0.0 },
            "is_charging" to state.vehicle.charging.isCharging,
            "odometer_km" to state.vehicle.odometerKm.takeIf { it > 0.0 },
        )
        if (includeCells) {
            val cells = state.cells.cellVoltages
            if (cells.isNotEmpty()) row["cell_voltages"] = cells
        }
        return row
    }

    /**
     * Пакет рядків у тіло запиту: Supabase приймає одразу масив об'єктів на один
     * POST. Масив збираємо руками — [MiniJson] навмисно вміє лише плоский об'єкт,
     * а не список об'єктів.
     */
    fun toJsonArray(rows: List<Map<String, Any?>>): String =
        rows.joinToString(",", "[", "]") { MiniJson.encode(it) }
}
