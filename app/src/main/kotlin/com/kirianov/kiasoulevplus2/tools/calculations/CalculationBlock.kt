// ====================================================================================
// БЛОК ОБЧИСЛЕНЬ (CalculationBlock)
//
// Веде історію поїздки — знімки лічильників із часом і пробігом — і рахує з неї
// витрату за обраний діапазон. Історія починається при під'єднанні і скидається
// при від'єднанні: одне підключення — одна поїздка.
//
// Годинник передається ззовні, щоб тести не залежали від реального часу.
// Він монотонний: переведення системного часу не має зіпсувати тривалість.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.TripHistory
import com.kirianov.kiasoulevplus2.Data.TripSample
import com.kirianov.kiasoulevplus2.Data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CalculationBlock(
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Час телефона, хвилини від початку доби. Окремо від [elapsedMillis]: той
     * монотонний і про добу нічого не знає, а тут потрібен саме годинник на стіні,
     * щоб порівняти його з годинником магнітоли.
     */
    private val phoneMinutesOfDay: () -> Int = {
        val now = java.util.Calendar.getInstance()
        now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    },
) {
    private var startedAt: Long? = null

    fun start(scope: CoroutineScope) {
        recalculateOnChange(scope)
        recordSamples(scope)
        resetOnDisconnect(scope)
    }

    private data class Inputs(
        val bms: BmsData,
        val cells: CellData,
        val history: TripHistory,
        val window: ConsumptionWindow,
        val vehicle: VehicleData,
    )

    private fun recalculateOnChange(scope: CoroutineScope) {
        GeneralData.state
            .map { Inputs(it.bms, it.cells, it.tripHistory, it.consumptionWindow, it.vehicle) }
            .distinctUntilChanged()
            .onEach {
                GeneralData.updateCalculated(
                    CalculationEngine.calculate(
                        bms = it.bms,
                        cells = it.cells,
                        history = it.history,
                        window = it.window,
                        vehicle = it.vehicle,
                        phoneMinutesOfDay = phoneMinutesOfDay(),
                    ),
                )
            }
            .launchIn(scope)
    }

    private data class Reading(
        val connection: ConnectionState,
        val bms: BmsData,
        val vehicle: VehicleData,
    )

    private fun recordSamples(scope: CoroutineScope) {
        GeneralData.state
            .map { Reading(it.connection, it.bms, it.vehicle) }
            .distinctUntilChanged()
            .onEach { reading ->
                if (reading.connection != ConnectionState.Connected) return@onEach
                if (!reading.bms.hasEnergyCounters) return@onEach

                val now = elapsedMillis()
                val since = startedAt ?: now.also { startedAt = it }

                GeneralData.addTripSample(
                    TripSample(
                        elapsedMs = now - since,
                        // null, а не нуль: інакше перший знімок, знятий до першого
                        // вікна монітора, робив відстань рівною всьому пробігу авто.
                        odometerKm = reading.vehicle.odometerKm.takeIf { reading.vehicle.hasOdometer },
                        dischargedKwh = reading.bms.cumulativeEnergyDischargedKwh,
                        chargedKwh = reading.bms.cumulativeEnergyChargedKwh,
                    ),
                )
            }
            .launchIn(scope)
    }

    private fun resetOnDisconnect(scope: CoroutineScope) {
        GeneralData.state
            .map { it.connection }
            .distinctUntilChanged()
            .onEach { connection ->
                if (connection == ConnectionState.Disconnected) {
                    startedAt = null
                    GeneralData.clearTripHistory()
                }
            }
            .launchIn(scope)
    }
}
