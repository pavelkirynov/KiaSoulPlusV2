// ====================================================================================
// БЛОК ДАНИХ АВТО (VehicleBlock)
//
// Слухає сирі рядки, які блок Bluetooth зняв у режимі монітора, розбирає з них
// широкомовні кадри й кладе результат у GeneralData. Про Bluetooth не знає нічого.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.tools.frames.MonitorLineParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class VehicleBlock {

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { it.can.monitor }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { capture ->
                val frames = capture.lines
                    .mapNotNull(MonitorLineParser::parse)
                    .filter { it.id in BroadcastDecoder.KNOWN_IDS }

                if (frames.isEmpty()) return@onEach
                GeneralData.updateVehicle(
                    BroadcastDecoder.merge(GeneralData.state.value.vehicle, frames),
                )
            }
            .launchIn(scope)
    }
}
