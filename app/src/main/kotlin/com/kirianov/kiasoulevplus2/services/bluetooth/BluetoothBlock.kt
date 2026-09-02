// ====================================================================================
// БЛОК BLUETOOTH (BluetoothBlock)
//
// ЩО ВІН РОБИТЬ:
// 1. Слухає запит на під'єднання/від'єднання в GeneralData — інтерфейс кличе не його,
//    а лише пише запит у сховище.
// 2. Знаходить OBD-адаптер, під'єднується і одноразово ініціалізує ELM327.
// 3. Крутить цикл опитування і кладе СИРІ відповіді в GeneralData.
// 4. Стежить за обривом зв'язку: після кількох підряд помилок вводу-виводу переводить
//    додаток у стан «Відключено».
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ декодує відповіді: цим займається блок декодерів, який теж читає GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import com.kirianov.kiasoulevplus2.Data.AppRequest
import com.kirianov.kiasoulevplus2.Data.BmsCommands
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BluetoothBlock(private val bluetoothManager: ElmBluetoothManager) {

    private val canBridge = ElmCANBridge(bluetoothManager)

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope

        GeneralData.state
            .map { it.request }
            .distinctUntilChanged()
            .onEach { request ->
                when (request) {
                    AppRequest.Connect -> { GeneralData.clearRequest(); attemptConnect() }
                    AppRequest.Disconnect -> { GeneralData.clearRequest(); disconnect() }
                    AppRequest.None -> Unit
                }
            }
            .launchIn(scope)

        GeneralData.state
            .map { it.probe.pending }
            .distinctUntilChanged()
            .onEach { request ->
                if (request == null) return@onEach
                GeneralData.clearProbeRequest()
                runProbe(request)
            }
            .launchIn(scope)
    }

    /**
     * Ручний запит з екрана «Експерименти». Помилка тут не рве з'єднання: це
     * пошук робочої команди, і відмова блока — теж корисний результат.
     */
    private fun runProbe(request: com.kirianov.kiasoulevplus2.Data.ProbeRequest) {
        val scope = scope ?: return
        if (!GeneralData.state.value.isConnected) {
            GeneralData.publishProbeFrames(
                request.header,
                request.command,
                response = "",
                error = "Немає з'єднання з адаптером",
            )
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val response = canBridge.sendCANCommand(request.header, request.command)
                GeneralData.publishProbeFrames(request.header, request.command, response)
            } catch (e: IOException) {
                GeneralData.publishProbeFrames(
                    request.header,
                    request.command,
                    response = "",
                    error = e.localizedMessage ?: "немає відповіді",
                )
            }
        }
    }

    private fun attemptConnect() {
        if (GeneralData.state.value.connection != ConnectionState.Disconnected) return
        val scope = scope ?: return

        GeneralData.updateConnection(ConnectionState.Connecting, "Пошук спарованих OBD пристроїв...")

        scope.launch(Dispatchers.IO) {
            val device = bluetoothManager.findObdDevice()
            if (device == null) {
                GeneralData.updateConnection(ConnectionState.Disconnected, "Немає спарованих OBD пристроїв!")
                return@launch
            }

            val deviceName = device.name ?: "OBD"
            GeneralData.updateConnection(ConnectionState.Connecting, "Підключення до $deviceName...")

            try {
                bluetoothManager.connect(device)
                canBridge.initAdapter()
            } catch (e: IOException) {
                bluetoothManager.disconnect()
                GeneralData.updateConnection(
                    ConnectionState.Disconnected,
                    "Збій підключення: ${e.localizedMessage ?: "невідома помилка"}",
                )
                return@launch
            }

            GeneralData.updateConnection(ConnectionState.Connected, "Підключено до $deviceName")
            startDataPolling(scope)
        }
    }

    /**
     * Цикл опитування з ВАРТОВИМ ЧАСОМ.
     *
     * Один такт не має права тривати нескінченно. Поки такого обмеження не було,
     * достатньо було одного зависання в читанні — і цикл спинявся назавжди: умова
     * `while` більше не перевірялася, стан лишався «Підключено», а екран показував
     * останні дані, які встиг отримати. Саме це й виглядало як «показує з'єднання,
     * але дані не змінюються».
     *
     * Гірше того, тихо страдало й навчання: блок прогнозу бачить розрив лише
     * тоді, коли стан став «Відключено». Замерзлий такт не змінює стану взагалі,
     * тож ані відрізок не відбраковувався, ані нового знімка не приходило —
     * години їзди просто не існували для моделі. Ось чому «пробіг навчання»
     * виходив 49 км замість двохсот.
     *
     * Спрацював вартовий — це не «одиничний збій зв'язку», який варто перечекати:
     * транспорт застряг, і другий такт застрягне так само. Тому обриваємо
     * з'єднання одразу; далі автопідключення підніме його з повною
     * переініціалізацією адаптера, а вона вміє виводити його з монітора.
     */
    private fun startDataPolling(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0

            while (isActive && GeneralData.state.value.isConnected) {
                val budgetMs = if (GeneralData.state.value.inputBms.scanCellsRequested) {
                    CELL_POLL_BUDGET_MS
                } else {
                    POLL_BUDGET_MS
                }

                try {
                    withTimeout(budgetMs) { pollOnce() }
                    consecutiveFailures = 0
                } catch (e: TimeoutCancellationException) {
                    handleConnectionLost(IOException("Адаптер не відповів за ${budgetMs / 1000} с"))
                    return@launch
                } catch (e: IOException) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        handleConnectionLost(e)
                        return@launch
                    }
                    GeneralData.updateDebugInfo(
                        "Збій зв'язку ($consecutiveFailures/$MAX_CONSECUTIVE_FAILURES): " +
                            (e.localizedMessage ?: "помилка вводу-виводу"),
                    )
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private var pollTick = 0L

    /** Номер вікна монітора: за ним обирається наступний фільтр із черги. */
    private var monitorWindow = 0L

    private suspend fun pollOnce() {
        val inputBms = GeneralData.state.value.inputBms
        val header = inputBms.customHeader.ifEmpty { BmsCommands.HEADER_BMS }

        if (!inputBms.scanCellsRequested) {
            val command = BmsCommands.REQUEST_BATTERY_MAIN
            val response = canBridge.sendCANCommand(header, command)
            GeneralData.publishBatteryFrames(listOf(command), listOf(response))

            // Пробіг і швидкість приходять широкомовними кадрами, а не на запит,
            // тому раз на кілька циклів слухаємо шину в режимі монітора.
            if (pollTick++ % MONITOR_EVERY_N_POLLS == 0L) captureBroadcast()
            return
        }

        val commands = inputBms.cellCommands.ifEmpty { BmsCommands.REQUEST_CELL_VOLTAGES }
        try {
            GeneralData.publishCellFrames(commands, readCellFrames(header, commands))
        } finally {
            // Прапорець знімається за будь-якого результату, інакше кнопка «Зчитати комірки»
            // залишалася б заблокованою назавжди після першої ж помилки.
            GeneralData.updateInputBms { it.copy(scanCellsRequested = false) }
        }
    }

    /**
     * Вікно монітора: звідси беруться пробіг, швидкість, SOC панелі, запас ходу,
     * температура за бортом і стан заряджання. Помилка тут не привід рвати
     * з'єднання: витрата в кВт·год рахується й без цих кадрів.
     */
    private suspend fun captureBroadcast() {
        // Один ID за вікно: без фільтра адаптер захлинається трафіком шини й віддає
        // «BUFFER FULL» замість кадрів. 4F0 стоїть через один, бо пробіг і швидкість
        // потрібні для витрати на 100 км, а решта змінюється повільно.
        val filterId = MONITOR_ROTATION[(monitorWindow++ % MONITOR_ROTATION.size).toInt()]

        val lines = try {
            canBridge.monitorBroadcast(MONITOR_WINDOW_MS, filterId)
        } catch (e: IOException) {
            GeneralData.updateDebugInfo(
                "Не вдалося послухати шину: ${e.localizedMessage ?: "немає відповіді"}",
            )
            return
        }
        if (lines.isNotEmpty()) GeneralData.publishMonitorLines(lines, filterId)
    }

    private suspend fun readCellFrames(header: String, commands: List<String>): List<String> =
        commands.map { command ->
            var response = canBridge.sendCANCommand(header, command)

            // Одна коротка повторна спроба, якщо адаптер віддав порожнечу.
            if (response.isBlank() || response.contains("NO DATA")) {
                delay(RETRY_DELAY_MS)
                response = canBridge.sendCANCommand(header, command)
            }

            // Пауза, щоб адаптер устиг зібрати багаторамковий ISO-TP кадр.
            delay(INTER_FRAME_DELAY_MS)
            response
        }

    private fun handleConnectionLost(cause: IOException) {
        canBridge.reset()
        bluetoothManager.disconnect()
        GeneralData.updateConnection(
            ConnectionState.Disconnected,
            "Зв'язок втрачено: ${cause.localizedMessage ?: "помилка вводу-виводу"}",
        )
        GeneralData.updateInputBms { it.copy(scanCellsRequested = false) }
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        canBridge.reset()
        bluetoothManager.disconnect()
        GeneralData.updateConnection(ConnectionState.Disconnected, "Відключено")
        GeneralData.updateInputBms { it.copy(scanCellsRequested = false) }
    }

    // internal, а не private: розкладку черги стереже тест.
    internal companion object {
        const val POLL_INTERVAL_MS = 800L
        const val INTER_FRAME_DELAY_MS = 200L
        const val RETRY_DELAY_MS = 100L

        /** Скільки підряд помилок вводу-виводу вважати обривом, а не одиничним збоєм. */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * Стеля одного такту опитування.
         *
         * Складається з найгірших випадків усередині: два запити по 3 с бюджету
         * на промпт, вікно монітора з сушінням буфера з обох боків (1.5 + 0.7 + 1.5)
         * і вихід із нього — три спроби промпта плюс три команди відкоту, теж по 3 с.
         * Разом близько 26 с; двадцять вісім лишають запас на планувальник.
         *
         * Це саме вартовий, а не робочий таймаут: у здоровому такті все це займає
         * менше секунди. Якщо ми дійшли до цієї межі, з адаптером щось не так.
         */
        const val POLL_BUDGET_MS = 28_000L

        /**
         * Окрема стеля для зчитування комірок: там до чотирнадцяти команд, кожна з
         * повтором і паузою на ISO-TP. Одна стеля на обидві гілки або душила б
         * комірки, або дозволяла б надто довгий звичайний такт.
         */
        const val CELL_POLL_BUDGET_MS = 120_000L

        /** Одне вікно монітора на кожні стільки циклів опитування. */
        const val MONITOR_EVERY_N_POLLS = 4L

        /** Скільки слухати шину за одне вікно: з фільтром кадр приходить кілька разів. */
        const val MONITOR_WINDOW_MS = 700L

        /**
         * Черга фільтрів «AT CRA» по вікнах: кожен запис — одне вікно монітора.
         *
         * Вікно займає близько п'яти секунд, тож частота кадру = довжина черги × 5 с.
         * Розкладка нерівномірна навмисно, за швидкістю самих величин:
         *
         *   4F0 (пробіг, швидкість) — 12 разів, ~10 с. Основа кожного відрізка.
         *   598 (точний SOC)        — 4 рази,  ~26 с. Було вчетверо рідше, і саме
         *                             це стримувало навчання кривої ємності:
         *                             ємність вивчається з пар «крок SOC / енергія»,
         *                             і рідкий SOC означав довгі сесії й грубу криву.
         *   594 (SOC панелі)        — 2 рази,  ~52 с.
         *   200 (запас + клімат)    — 2 рази,  ~52 с. Тепер несе ще й ціну клімату.
         *   581 (заряджання)        — 2 рази,  ~52 с. Треба, щоб вчасно розділити
         *                             рух і заряд.
         *   653 (температура)       — 2 рази,  ~52 с.
         *
         * Годинника (567) тут більше немає разом із самою діагностикою дрейфу:
         * магнітола виявилася непридатною за джерело часу, і його читання відкотили.
         * Звільнене вікно віддано температурі.
         */
        val MONITOR_ROTATION = listOf(
            "4F0", "598", "4F0", "594", "4F0", "200", "4F0", "598", "4F0", "581", "4F0", "653",
            "4F0", "598", "4F0", "594", "4F0", "200", "4F0", "598", "4F0", "581", "4F0", "653",
        )
    }
}
