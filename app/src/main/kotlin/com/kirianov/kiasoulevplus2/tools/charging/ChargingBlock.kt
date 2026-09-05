// ====================================================================================
// БЛОК ОБЛІКУ ЗАРЯДОК (ChargingBlock)
//
// Слухає пожиттєвий лічильник прийнятої енергії та ознаку заряджання, веде з них
// облік зарядок і кладе результат у GeneralData. Про Bluetooth і про BMS не знає
// нічого — тільки про два числа зі сховища стану.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ChargeRequest
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

class ChargingBlock(
    private val store: ChargeStore,
    /** Годинник на стіні: потрібен, щоб знати добу й час завершення зарядки. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val dayKey: () -> String = ::localDayKey,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var log = store.load() ?: ChargeLog()
            GeneralData.updateChargeLog(log)

            GeneralData.state
                .map {
                    // Усі три числа батареї — з одного кадру 21 01. Саме тому їх
                    // можна порівнювати між собою після будь-якої паузи: у них
                    // однаковий вік. Ознака заряджання приходить окремим кадром,
                    // але вона потрібна лише «прямо зараз», а не в порівнянні.
                    Reading(
                        counterKwh = it.bms.cumulativeEnergyChargedKwh,
                        dischargedKwh = it.bms.cumulativeEnergyDischargedKwh,
                        socPercent = it.bms.displaySoc,
                        isCharging = it.vehicle.charging.isCharging,
                        ignitionOn = ignitionOn(it.bms.batteryCurrent, it.vehicle),
                        request = it.charge.request,
                    )
                }
                .distinctUntilChanged()
                .collect { reading ->
                    val now = nowMs()
                    val day = dayKey()

                    // Прохання з екрана виконуємо першим і окремо: у нього свої
                    // правила — жодних порогів, бо зарядку бачила людина.
                    if (reading.request == ChargeRequest.FinishSession) {
                        GeneralData.clearChargeRequest()
                        val finished = ChargeTracker.finishManually(
                            log = log,
                            counterKwh = reading.counterKwh,
                            dischargedKwh = reading.dischargedKwh,
                            socPercent = reading.socPercent,
                            nowMs = now,
                            dayKey = day,
                        )
                        if (finished != log) {
                            log = finished
                            GeneralData.updateChargeLog(finished)
                            store.save(finished)
                        }
                        return@collect
                    }

                    val updated = ChargeTracker.observe(
                        log = log,
                        counterKwh = reading.counterKwh,
                        dischargedKwh = reading.dischargedKwh,
                        socPercent = reading.socPercent,
                        isCharging = reading.isCharging,
                        nowMs = now,
                        dayKey = day,
                        ignitionOn = reading.ignitionOn,
                    )
                    if (updated == log) return@collect

                    log = updated
                    GeneralData.updateChargeLog(updated)
                    store.save(updated)
                }
        }
    }

    private data class Reading(
        val counterKwh: Double,
        val dischargedKwh: Double,
        val socPercent: Double,
        val isCharging: Boolean,
        val ignitionOn: Boolean,
        val request: ChargeRequest,
    )

    /**
     * Чи авто ввімкнене.
     *
     * Прямої ознаки запалювання шина не передає, тож збираємо її з двох свідчень.
     * Рух — свідчення беззаперечне. Тяговий струм — майже таке саме: коли авто
     * стоїть вимкненим, він у журналі не виходить за ±3 А, а щойно його вмикають,
     * DC-DC і клімат дають десятки ампер.
     *
     * Помилитися в бік «увімкнене» тут не страшно: цей висновок закриває зарядку,
     * а зарядка, закрита на кілька хвилин раніше, все одно порахована повністю —
     * різниця лічильника береться від її початку.
     */
    private fun ignitionOn(currentA: Double, vehicle: VehicleData): Boolean =
        (vehicle.hasSpeed && vehicle.speedKmh > 0.0) ||
            abs(currentA) >= ChargeTracker.IGNITION_CURRENT_A
}

/**
 * «рррр-мм-дд» за місцевим годинником. Через Calendar, а не java.time: minSdk 23,
 * і тягнути десугарінг заради однієї дати не варто.
 */
internal fun localDayKey(): String {
    val now = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(
        now.get(java.util.Calendar.YEAR),
        now.get(java.util.Calendar.MONTH) + 1,
        now.get(java.util.Calendar.DAY_OF_MONTH),
    )
}
