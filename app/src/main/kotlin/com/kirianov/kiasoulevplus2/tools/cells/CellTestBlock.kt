// ====================================================================================
// БЛОК ТЕСТУ КОМІРОК (CellTestBlock)
//
// Накопичує проходи, поки тест іде, і перераховує підсумок після кожного. Про шину
// не знає нічого: проходи приходять уже розібраними.
//
// Підсумок перераховується щоразу навмисно, а не в кінці тесту. Так на екрані
// одразу видно, чи набирається розмах струму, — і людина розуміє, що треба
// розігнатися, ще під час тесту, а не після нього.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.cells

import com.kirianov.kiasoulevplus2.Data.CellSweep
import com.kirianov.kiasoulevplus2.Data.CellTestRequest
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CellTestBlock {

    fun start(scope: CoroutineScope) {
        answerRequests(scope)
        collectSweeps(scope)
    }

    private fun answerRequests(scope: CoroutineScope) {
        GeneralData.state
            .map { it.cellTest.request }
            .distinctUntilChanged()
            .onEach { request ->
                when (request) {
                    CellTestRequest.None -> return@onEach
                    // Старт завжди починає з чистого аркуша: домішати новий тест до
                    // старого означало б порівнювати проходи з різних поїздок, між
                    // якими встиг змінитися і заряд, і температура.
                    CellTestRequest.Start -> GeneralData.updateCellTest {
                        it.copy(running = true, sweeps = emptyList(), result = summaryOf(emptyList()))
                    }
                    CellTestRequest.Stop -> GeneralData.updateCellTest { it.copy(running = false) }
                    CellTestRequest.Clear -> GeneralData.updateCellTest {
                        it.copy(running = false, sweeps = emptyList(), result = summaryOf(emptyList()))
                    }
                }
                GeneralData.clearCellTestRequest()
            }
            .launchIn(scope)
    }

    private fun collectSweeps(scope: CoroutineScope) {
        GeneralData.state
            .map { it.cellTest.lastSweep }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { sweep ->
                GeneralData.updateCellTest { state ->
                    if (!state.running) return@updateCellTest state
                    val sweeps = (state.sweeps + sweep).takeLast(MAX_SWEEPS)
                    state.copy(sweeps = sweeps, result = summaryOf(sweeps))
                }
            }
            .launchIn(scope)
    }

    private fun summaryOf(sweeps: List<CellSweep>) =
        CellLoad.summarize(sweeps)

    private companion object {
        /**
         * Скільки проходів тримаємо. Триста — це близько п'яти хвилин тесту, а
         * довший тест і не потрібен: за цей час заряд помітно просідає, і комірки
         * порівнюються вже в різних умовах.
         */
        const val MAX_SWEEPS = 300
    }
}
