// ====================================================================================
// БЛОК ДЕКОДЕРІВ (DecoderBlock)
//
// Слухає сирі кадри, які поклав у GeneralData блок Bluetooth, і кладе туди ж розібрані
// показники. Про Bluetooth не знає нічого: працює з текстом відповідей, тому його можна
// перевірити тестами, не маючи ані адаптера, ані авто.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class DecoderBlock(private val cellDecoder: CellDecoder = CellDecoder()) {

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { it.can.batteryFrames }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { frames ->
                val bytes = BmsFrameParser.parse(frames.responses.firstOrNull().orEmpty())
                GeneralData.updateBms(BmsResponseDecoder.decode(bytes))
            }
            .launchIn(scope)

        GeneralData.state
            .map { it.can.vehicleFrames }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { frames ->
                val bytes = BmsFrameParser.parse(frames.responses.firstOrNull().orEmpty())
                GeneralData.updateVehicle(OdometerDecoder.decode(bytes))
            }
            .launchIn(scope)

        GeneralData.state
            .map { it.can.cellFrames }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { frames ->
                GeneralData.updateCells(cellDecoder.decodeResponses(frames.commands, frames.responses))
            }
            .launchIn(scope)
    }
}
