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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

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

    private fun startDataPolling(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0

            while (isActive && GeneralData.state.value.isConnected) {
                try {
                    pollOnce()
                    consecutiveFailures = 0
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

    private suspend fun pollOnce() {
        val inputBms = GeneralData.state.value.inputBms
        val header = inputBms.customHeader.ifEmpty { BmsCommands.HEADER_BMS }

        if (!inputBms.scanCellsRequested) {
            val command = BmsCommands.REQUEST_BATTERY_MAIN
            val response = canBridge.sendCANCommand(header, command)
            GeneralData.publishBatteryFrames(listOf(command), listOf(response))

            // Пробіг змінюється повільно, тому питаємо його рідше, ніж струм і напругу:
            // зайвий запит щоразу лише сповільнював би основний цикл.
            if (pollTick++ % ODOMETER_EVERY_N_POLLS == 0L) pollOdometer()
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
     * Пробіг живе у щитку приладів. Якщо він не відповідає, це не привід рвати
     * з'єднання: витрата в кВт·год рахується й без пробігу.
     */
    private suspend fun pollOdometer() {
        val command = BmsCommands.REQUEST_ODOMETER
        val response = try {
            canBridge.sendCANCommand(BmsCommands.HEADER_CLUSTER, command)
        } catch (e: IOException) {
            GeneralData.updateDebugInfo("Щиток не віддав пробіг: ${e.localizedMessage ?: "немає відповіді"}")
            return
        }
        GeneralData.publishVehicleFrames(listOf(command), listOf(response))
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

    private companion object {
        const val POLL_INTERVAL_MS = 800L
        const val INTER_FRAME_DELAY_MS = 200L
        const val RETRY_DELAY_MS = 100L

        /** Скільки підряд помилок вводу-виводу вважати обривом, а не одиничним збоєм. */
        const val MAX_CONSECUTIVE_FAILURES = 3

        /** Один запит пробігу на кожні стільки циклів опитування. */
        const val ODOMETER_EVERY_N_POLLS = 5L
    }
}
