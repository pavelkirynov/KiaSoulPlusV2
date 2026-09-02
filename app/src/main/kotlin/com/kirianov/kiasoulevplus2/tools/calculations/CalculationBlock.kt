// ====================================================================================
// БЛОК ОБЧИСЛЕНЬ (CalculationBlock)
//
// Веде історію поїздки — знімки лічильників із часом і пробігом — і рахує з неї
// витрату за обраний діапазон. Історія починається при під'єднанні і скидається
// при від'єднанні: одне підключення — одна поїздка.
//
// Годинник передається ззовні, щоб тести не залежали від реального часу.
//
// Він МОНОТОННИЙ І ТЕЛЕФОННИЙ, і це навмисно:
//  - не годинник авто: на цій машині годинник магнітоли йде з іншою швидкістю,
//    тобто як джерело часу він непридатний (розбір — у docs/SOUL_EV_CAN.md);
//  - не системний годинник телефона: переведення часу або зміна часового поясу
//    посеред поїздки не мають зіпсувати тривалість, а отже й витрату на годину.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.DistanceCheck
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
) {
    private var startedAt: Long? = null

    fun start(scope: CoroutineScope) {
        recalculateOnChange(scope)
        recordSamples(scope)
        trackDistance(scope)
        resetOnDisconnect(scope)
    }

    /**
     * Свій підрахунок шляху з швидкості, щоб було з чим порівняти одометр.
     * Різниця між ними — міра того, наскільки застосунок здатний рахувати
     * відстань зі своїх даних; див. DistanceCheck.
     */
    private fun trackDistance(scope: CoroutineScope) {
        GeneralData.state
            .map { SpeedReading(it.connection, it.vehicle.speedKmh, it.vehicle.hasSpeed, it.vehicle.odometerKm, it.vehicle.hasOdometer) }
            .distinctUntilChanged()
            .onEach { reading ->
                if (reading.connection != ConnectionState.Connected) return@onEach
                if (!reading.hasSpeed) return@onEach

                val updated = GeneralData.state.value.distanceCheck.plus(
                    speedKmh = reading.speedKmh,
                    atMs = elapsedMillis(),
                    odometerKm = reading.odometerKm.takeIf { reading.hasOdometer },
                )
                GeneralData.updateDistanceCheck(updated)
            }
            .launchIn(scope)
    }

    private data class SpeedReading(
        val connection: ConnectionState,
        val speedKmh: Double,
        val hasSpeed: Boolean,
        val odometerKm: Double,
        val hasOdometer: Boolean,
    )

    private data class Inputs(
        val bms: BmsData,
        val cells: CellData,
        val history: TripHistory,
        val window: ConsumptionWindow,
    )

    private fun recalculateOnChange(scope: CoroutineScope) {
        GeneralData.state
            .map { Inputs(it.bms, it.cells, it.tripHistory, it.consumptionWindow) }
            .distinctUntilChanged()
            .onEach {
                GeneralData.updateCalculated(
                    CalculationEngine.calculate(it.bms, it.cells, it.history, it.window),
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
                    GeneralData.updateDistanceCheck(DistanceCheck())
                }
            }
            .launchIn(scope)
    }
}
