// ====================================================================================
// БЛОК ОБЛІКУ ЗАРЯДОК (ChargingBlock)
//
// Слухає пожиттєвий лічильник прийнятої енергії та ознаку заряджання, веде з них
// облік зарядок і кладе результат у GeneralData. Про Bluetooth і про BMS не знає
// нічого — тільки про два числа зі сховища стану.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
                .map { Reading(it.bms.cumulativeEnergyChargedKwh, it.vehicle.charging.isCharging) }
                .distinctUntilChanged()
                .collect { reading ->
                    val updated = ChargeTracker.observe(
                        log = log,
                        counterKwh = reading.counterKwh,
                        isCharging = reading.isCharging,
                        nowMs = nowMs(),
                        dayKey = dayKey(),
                    )
                    if (updated == log) return@collect

                    log = updated
                    GeneralData.updateChargeLog(updated)
                    store.save(updated)
                }
        }
    }

    private data class Reading(val counterKwh: Double, val isCharging: Boolean)
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
