// ====================================================================================
// БЛОК РУЧНИХ ЗАПИТІВ (ProbeBlock)
//
// Перетворює сиру відповідь на ручний запит у розібрані байти й кладе результат
// у GeneralData. Далі екран «Експерименти» показує байти з їхніми індексами —
// саме так зсув потрібної величини знаходиться з реальних даних, а не вгадується.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.probe

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.ProbeResult
import com.kirianov.kiasoulevplus2.tools.frames.ByteCandidates
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ProbeBlock {

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { it.can.probeFrames }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { frames ->
                val raw = frames.responses.firstOrNull().orEmpty()
                val bytes = FrameParser.parse(raw)
                GeneralData.addProbeResult(
                    ProbeResult(
                        header = frames.commands.getOrElse(0) { "" },
                        command = frames.commands.getOrElse(1) { "" },
                        rawResponse = raw,
                        bytes = bytes,
                        odometerCandidates = ByteCandidates.find(bytes, ODOMETER_RANGE),
                        error = frames.commands.getOrNull(2),
                    ),
                )
            }
            .launchIn(scope)
    }

    private companion object {
        /** Правдоподібні межі одометра: у цих межах шукаються кандидати. */
        val ODOMETER_RANGE = 1L..2_000_000L
    }
}
