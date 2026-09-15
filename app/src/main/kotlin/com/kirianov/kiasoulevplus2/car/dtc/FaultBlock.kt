// ====================================================================================
// БЛОК ПОМИЛОК (FaultBlock)
//
// Слухає сирі відповіді блоків, які поклав у GeneralData блок Bluetooth, і кладе
// туди ж розібрані. Про шину не знає нічого — працює з текстом відповідей, тому
// перевіряється тестами без адаптера й без авто.
//
// Той самий поділ, що й у батареї: хто питає — не розбирає, хто розбирає — не питає.
// ====================================================================================

package com.kirianov.kiasoulevplus2.car.dtc

import com.kirianov.kiasoulevplus2.Data.Ecu
import com.kirianov.kiasoulevplus2.Data.Ecus
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class FaultBlock {

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { it.faults.answer }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { answer ->
                val ecu = Ecus.ALL.firstOrNull { it.header == answer.header }
                    ?: Ecu(answer.header, answer.header)
                val decoded = FaultDecoder.decode(ecu, answer.raw)
                GeneralData.updateFaults {
                    it.copy(results = it.results + decoded, done = it.done + 1)
                }
            }
            .launchIn(scope)

        // Змінилося авто — помилки від нього. Коди належать тій машині, у якої їх
        // спитали, і лишити їх на екрані означає приписати чужу несправність.
        GeneralData.state
            .map { it.garage.activeVin }
            .distinctUntilChanged()
            .onEach {
                GeneralData.updateFaults {
                    it.copy(results = emptyList(), done = 0, total = 0, scannedAtMs = 0L)
                }
            }
            .launchIn(scope)
    }
}
