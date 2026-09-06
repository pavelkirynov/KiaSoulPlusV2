// ====================================================================================
// БЛОК ТЕСТУ КОМІРОК (CellTestBlock)
//
// Накопичує проходи, поки тест іде, і перераховує підсумок після кожного. Про шину
// не знає нічого: проходи приходять уже розібраними.
//
// Підсумок перераховується щоразу навмисно, а не в кінці тесту. Так на екрані
// одразу видно, чи набирається розмах струму, — і людина розуміє, що треба
// розігнатися, ще під час тесту, а не після нього.
//
// ТУТ ЖЕ ВЕДЕТЬСЯ ІСТОРІЯ ЗАМІРІВ. Замір комірок має сенс лише в порівнянні: одна
// табличка напруг не каже нічого, відповідь дає різниця між двома, знятими в різні
// дні. Тому кожен закінчений тест лягає у файл авто сам, без запитань, — питати
// «зберегти?» означало б утратити той замір, на якому людина забула натиснути.
//
// Простий замір без тесту зберігається кнопкою: він знімається щосекунди, і
// складати у файл кожен було б безглуздо.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.cells

import com.kirianov.kiasoulevplus2.Data.CellHistoryRequest
import com.kirianov.kiasoulevplus2.Data.CellRecord
import com.kirianov.kiasoulevplus2.Data.CellSweep
import com.kirianov.kiasoulevplus2.Data.CellTestRequest
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class CellTestBlock(
    private val store: CellHistoryStore = NoCellHistoryStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun start(scope: CoroutineScope) {
        answerRequests(scope)
        collectSweeps(scope)
        watchCar(scope)
        answerHistoryRequests(scope)
    }

    /**
     * Змінилося авто — історія в нього своя.
     *
     * Тест теж скидається: проходи належать тому пакету, на якому знімалися, і
     * домішати до них комірки іншої машини означало б порівнювати різні батареї.
     */
    private fun watchCar(scope: CoroutineScope) {
        GeneralData.state
            .map { it.garage.activeVin }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged()
            .onEach { vin ->
                val records = withContext(ioDispatcher) {
                    store.useCar(vin)
                    store.load()
                }
                GeneralData.updateCellTest {
                    it.copy(running = false, sweeps = emptyList(), result = CellLoad.summarize(emptyList()))
                }
                GeneralData.updateCellHistory { it.copy(records = records, loaded = true) }
            }
            .launchIn(scope)
    }

    private fun answerHistoryRequests(scope: CoroutineScope) {
        GeneralData.state
            .map { it.cellHistory.request to it.cellHistory.requestAtMs }
            .distinctUntilChanged()
            .onEach { (request, atMs) ->
                when (request) {
                    CellHistoryRequest.None -> return@onEach
                    CellHistoryRequest.Save -> snapshot()
                    CellHistoryRequest.Delete -> remove(atMs)
                }
                GeneralData.clearCellHistoryRequest()
            }
            .launchIn(scope)
    }

    /** Замір спокою: те, що комірки показують просто зараз. */
    private suspend fun snapshot() {
        val state = GeneralData.state.value
        val volts = state.cells.cellVoltages
        if (volts.isEmpty()) return
        keep(recordOf(state, volts))
    }

    private suspend fun remove(atMs: Long) {
        val left = GeneralData.state.value.cellHistory.records.filterNot { it.atMs == atMs }
        withContext(ioDispatcher) { store.save(left) }
        GeneralData.updateCellHistory { it.copy(records = left) }
    }

    private suspend fun keep(record: CellRecord) {
        val records = (listOf(record) + GeneralData.state.value.cellHistory.records)
            .sortedByDescending { it.atMs }
        withContext(ioDispatcher) { store.save(records) }
        GeneralData.updateCellHistory { it.copy(records = records, loaded = true) }
    }

    /**
     * Замір із тесту: до напруг додаються мінімуми під навантаженням і опір.
     *
     * Порядок комірок у списках — той самий, що й у сітці, тобто за індексом. Без
     * цього два заміри неможливо було б покласти поруч.
     */
    private suspend fun keepTest(state: com.kirianov.kiasoulevplus2.Data.State) {
        val result = state.cellTest.result
        if (!result.hasCells) return
        val cells = result.cells.sortedBy { it.index }
        keep(
            recordOf(state, cells.map { it.restVolts }).copy(
                minVolts = cells.map { it.minVolts },
                excessMilliOhm = if (result.resistanceKnown) {
                    cells.map { it.excessMilliOhm ?: 0.0 }
                } else {
                    emptyList()
                },
                sweeps = result.sweeps,
                currentSpreadA = result.currentSpreadA,
            ),
        )
    }

    /**
     * Умови заміру беруться з того самого стану, що й напруги.
     *
     * Без пробігу, заряду й температури запис нічого не варт: комірки на 20 % і на
     * 90 % шкали, на теплій і на холодній батареї показують різне, і різниця між
     * двома замірами була б різницею умов, а не пакетів.
     */
    private fun recordOf(
        state: com.kirianov.kiasoulevplus2.Data.State,
        volts: List<Double>,
    ) = CellRecord(
        atMs = nowMs(),
        odometerKm = if (state.vehicle.hasOdometer) state.vehicle.odometerKm else 0.0,
        socPercent = if (state.bms.hasData) state.bms.displaySoc else 0.0,
        batteryTempC = state.bms.batteryTempC,
        restVolts = volts,
    )

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
                    // Кінець тесту — і його підсумок одразу лягає в історію.
                    // Питати тут «зберегти?» означало б утратити рівно ті заміри,
                    // на яких людина забула натиснути.
                    CellTestRequest.Stop -> {
                        GeneralData.updateCellTest { it.copy(running = false) }
                        keepTest(GeneralData.state.value)
                    }
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

/**
 * Історія, якої немає: для тестів і для складання без сховища.
 *
 * Об'єкт, а не null: блок не мусить питати «а чи є сховище» на кожній дії.
 */
object NoCellHistoryStore : CellHistoryStore {
    override fun load(): List<CellRecord> = emptyList()
    override fun save(records: List<CellRecord>) {}
}
