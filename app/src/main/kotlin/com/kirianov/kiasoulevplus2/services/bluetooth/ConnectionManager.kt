// ====================================================================================
// УПРАВЛІННЯ З'ЄДНАННЯМ ТА ЦИКЛОМ ОПИТУВАННЯ CAN (ConnectionManager)
//
// ЩО ВІН РОБИТЬ:
// 1. Знаходить OBD-адаптер, підключається і одноразово ініціалізує ELM327.
// 2. Крутить фоновий цикл: або звичайне зчитування 21 01, або, на запит з екрана
//    комірок, серію 21 02..21 04.
// 3. Стежить за обривом зв'язку: після кількох підряд помилок вводу-виводу
//    переводить додаток у стан «Відключено» замість того, щоб мовчки крутитися далі.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import com.kirianov.kiasoulevplus2.Data.BmsCommands
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.tools.battery.BatteryDecoder
import com.kirianov.kiasoulevplus2.tools.battery.CellDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

class ConnectionManager(
    private val bluetoothManager: ElmBluetoothManager,
    private val scope: CoroutineScope,
) {
    private val canBridge = ElmCANBridge(bluetoothManager)
    private val batteryDecoder = BatteryDecoder(canBridge)
    private val cellDecoder = CellDecoder()

    private var pollingJob: Job? = null

    fun attemptConnect() {
        if (GeneralData.state.value.connection != ConnectionState.Disconnected) return

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
            startDataPolling()
        }
    }

    private fun startDataPolling() {
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

    private suspend fun pollOnce() {
        val inputBms = GeneralData.state.value.inputBms

        if (!inputBms.scanCellsRequested) {
            GeneralData.updateBmsData(batteryDecoder.getBatteryData())
            return
        }

        val header = inputBms.customHeader.ifEmpty { BmsCommands.HEADER_BMS }
        val commands = inputBms.cellCommands.ifEmpty { BmsCommands.REQUEST_CELL_VOLTAGES }

        try {
            val responses = readCellFrames(header, commands)
            GeneralData.updateCellData(cellDecoder.decodeResponses(commands, responses))
        } finally {
            // Прапорець знімається за будь-якого результату, інакше кнопка «Зчитати комірки»
            // залишалася б заблокованою назавжди після першої ж помилки.
            GeneralData.updateInputBms { it.copy(scanCellsRequested = false) }
        }
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
        }.also { responses ->
            GeneralData.updateInputBms { it.copy(rawResponses = responses) }
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
    }
}
