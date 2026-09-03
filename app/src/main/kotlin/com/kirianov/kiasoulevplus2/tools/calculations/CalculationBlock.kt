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
import com.kirianov.kiasoulevplus2.Data.RangeAccuracy
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
        trackRangeAccuracy(scope)
        resetOnDisconnect(scope)
    }

    /**
     * Чи стримав прогноз обіцянку: наскільки впав обіцяний запас проти того,
     * скільки насправді проїхано. Див. RangeAccuracy.
     *
     * ЩО ЙОГО СКИДАЄ, А ЩО НІ. Зарядка — скидає: вона піднімає запас, і початкова
     * обіцянка після неї означає вже інше. Обрив зв'язку — НЕ скидає, і це
     * важливо. Обидва числа тут абсолютні: одометр авто і поточний прогноз. Ні
     * те, ні інше не залежить від того, дивився застосунок чи ні, тож пауза в
     * спостереженнях нічого не псує — на відміну від скидання, після якого
     * відлік починався з нуля по кілька разів за поїздку і не встигав набрати
     * навіть тих трьох кілометрів, з яких відсоток узагалі щось означає.
     */
    private fun trackRangeAccuracy(scope: CoroutineScope) {
        GeneralData.state
            .map {
                RangeReading(
                    rangeKm = it.ml.prediction?.rangeKm ?: 0.0,
                    odometerKm = it.vehicle.odometerKm,
                    charging = it.charge.charging,
                    // Зарядка, яку застосунок не бачив живцем, теж міняє запас —
                    // її видно лише за тим, що з'явилася нова завершена сесія.
                    lastChargeEndedAtMs = it.charge.lastSessionEndedAtMs,
                )
            }
            .distinctUntilChanged()
            .onEach { reading ->
                val current = GeneralData.state.value.rangeAccuracy

                if (reading.charging || reading.lastChargeEndedAtMs != lastChargeSeenMs) {
                    lastChargeSeenMs = reading.lastChargeEndedAtMs
                    if (current.started) GeneralData.updateRangeAccuracy(RangeAccuracy())
                    return@onEach
                }

                val updated = current.observe(reading.rangeKm, reading.odometerKm)
                if (updated != current) GeneralData.updateRangeAccuracy(updated)
            }
            .launchIn(scope)
    }

    /** Коли скінчилася остання зарядка, яку ми вже врахували для скидання відліку. */
    private var lastChargeSeenMs = 0L

    private data class RangeReading(
        val rangeKm: Double,
        val odometerKm: Double,
        val charging: Boolean,
        val lastChargeEndedAtMs: Long,
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
                    // RangeAccuracy тут НЕ скидається: див. пояснення в
                    // trackRangeAccuracy. Обидва його числа абсолютні й
                    // переживають паузу в спостереженнях.
                }
            }
            .launchIn(scope)
    }
}
