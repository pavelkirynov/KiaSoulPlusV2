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
import com.kirianov.kiasoulevplus2.Data.ClockDriftHistory
import com.kirianov.kiasoulevplus2.Data.ClockDriftSample
import com.kirianov.kiasoulevplus2.Data.ClockStatus
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
     * Час телефона, секунди від початку доби. Окремо від [elapsedMillis]: той
     * монотонний і про добу нічого не знає, а тут потрібен саме годинник на стіні,
     * щоб порівняти його з годинником магнітоли.
     */
    private val phoneSecondsOfDay: () -> Int = {
        val now = java.util.Calendar.getInstance()
        now.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
            now.get(java.util.Calendar.MINUTE) * 60 +
            now.get(java.util.Calendar.SECOND)
    },
) {
    private var startedAt: Long? = null

    /** Живе в блоці, а не в хабі: інтерфейсу потрібен лише підсумок, не вся серія. */
    private var clockHistory = ClockDriftHistory()

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
                        clock = observeClock(it.vehicle),
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

    /**
     * Веде серію розходжень годинників і повертає підсумок.
     *
     * Саме серія, а не одне число: рівномірний хід (несправний кварц RTC) і стрибок
     * (підмінений час або перезавантаження магнітоли) — це різні несправності з
     * різним ремонтом, і відрізнити їх можна лише за формою розходження в часі.
     */
    private fun observeClock(vehicle: VehicleData): ClockStatus {
        val drift = CalculationEngine.clockDrift(vehicle.clockSecondsOfDay, phoneSecondsOfDay())
            ?: return ClockStatus()

        clockHistory = clockHistory.plus(ClockDriftSample(elapsedMillis(), drift))

        return ClockStatus(
            driftSeconds = clockHistory.driftSeconds,
            rateSecondsPerHour = clockHistory.rateSecondsPerHour,
            jumpCount = clockHistory.jumpCount,
            observedMs = clockHistory.spanMs,
        )
    }

    private fun resetOnDisconnect(scope: CoroutineScope) {
        GeneralData.state
            .map { it.connection }
            .distinctUntilChanged()
            .onEach { connection ->
                if (connection == ConnectionState.Disconnected) {
                    startedAt = null
                    clockHistory = ClockDriftHistory()

                    // Очистити саму серію недостатньо: вже опублікований вердикт
                    // висів би на екрані до наступного перерахунку, а перерахунок
                    // залежить від показників, які після від'єднання не приходять.
                    GeneralData.updateCalculated(
                        GeneralData.state.value.calculated.copy(clock = ClockStatus()),
                    )
                    GeneralData.clearTripHistory()
                }
            }
            .launchIn(scope)
    }
}
