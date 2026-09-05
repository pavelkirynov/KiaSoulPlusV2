// ====================================================================================
// БЛОК ДЕКОДЕРІВ (DecoderBlock)
//
// Слухає сирі кадри, які поклав у GeneralData блок Bluetooth, і кладе туди ж розібрані
// показники. Про Bluetooth не знає нічого: працює з текстом відповідей, тому його можна
// перевірити тестами, не маючи ані адаптера, ані авто.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.CellSweep
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser
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
                val bytes = FrameParser.parse(frames.responses.firstOrNull().orEmpty())
                GeneralData.updateBms(BmsResponseDecoder.decode(bytes))
            }
            .launchIn(scope)

        // Прохід тесту комірок: напруги в обрамленні двох замірів струму. Розбирає
        // його той самий декодер — просто збирає з трьох відповідей одну картину.
        GeneralData.state
            .map { it.can.cellSweep }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { frames ->
                val before = BmsResponseDecoder.decode(FrameParser.parse(frames.beforeResponse))
                val after = BmsResponseDecoder.decode(FrameParser.parse(frames.afterResponse))
                val cells = cellDecoder.decodeResponses(frames.cellCommands, frames.cellResponses)
                if (cells.cellVoltages.isEmpty()) return@onEach

                // Таблиця комірок оновлюється й від проходу теж, а не лише від
                // кнопки «Зчитати комірки». Без цього під час тесту, який читає
                // комірки частіше за будь-що інше, сітка на екрані стояла порожня —
                // рівно там, куди людина й дивиться, поки тест іде.
                GeneralData.updateCells(cells)

                GeneralData.publishDecodedSweep(
                    CellSweep(
                        voltages = cells.cellVoltages,
                        currentBeforeA = before.batteryCurrent,
                        currentAfterA = after.batteryCurrent,
                        // Напруга пакета береться з ПЕРШОГО заміру: другий уже
                        // після проходу, а нам потрібна та, при якій читалися
                        // комірки. Різниця між ними — і є та невизначеність, яку
                        // ловить розбіжність струмів.
                        packVolts = before.batteryVoltage,
                        atMs = frames.atMs,
                    ),
                )
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
