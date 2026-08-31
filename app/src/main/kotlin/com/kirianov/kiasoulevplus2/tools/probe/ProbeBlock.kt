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
        recordReplies(scope)
        rematchOnTargetChange(scope)
    }

    /**
     * Коли користувач вводить відоме число, збіги треба перерахувати і для вже
     * отриманих відповідей — інакше довелося б перепитувати авто заради пошуку.
     */
    private fun rematchOnTargetChange(scope: CoroutineScope) {
        GeneralData.state
            .map { it.probe.targetValue }
            .distinctUntilChanged()
            .onEach { target ->
                val results = GeneralData.state.value.probe.results
                if (results.isEmpty()) return@onEach
                GeneralData.updateProbeResults(
                    results.map { it.copy(matches = matchesFor(it.bytes, target)) },
                )
            }
            .launchIn(scope)
    }

    private fun matchesFor(bytes: List<Int>, target: Long?) =
        target?.let { ByteCandidates.findValue(bytes, it) }.orEmpty()

    private fun recordReplies(scope: CoroutineScope) {
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
                        matches = matchesFor(bytes, GeneralData.state.value.probe.targetValue),
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
