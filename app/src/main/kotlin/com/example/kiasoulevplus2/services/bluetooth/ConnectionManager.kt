// ====================================================================================
// СЕРВІС УПРАВЛІННЯ СТАНОМ BLUETOOTH-З'ЄДНАННЯ ТА CAN-ПОЛІНГУ
//
// ОСНОВНІ ЗМІНИ:
// 1. Збільшено затримку між CAN-командами до 200 мс для стабільного прийому касет 21 02..04.
// 2. Додано тайм-аут і перевірку на порожні відповіді.
// 3. Додано ініціалізацію фільтра заголовка CAN (AT SH 7E4) для чіткої адресації BMS.
// ====================================================================================

package com.example.kiasoulevplus2.services.bluetooth

import com.example.kiasoulevplus2.Data.GeneralData
import com.example.kiasoulevplus2.tools.battery.BatteryDecoder
import com.example.kiasoulevplus2.tools.battery.CellDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectionManager(
    private val bluetoothManager: ElmBluetoothManager,
    private val scope: CoroutineScope
) {
    private val canBridge = ElmCANBridge(bluetoothManager)
    private val batteryDecoder = BatteryDecoder(bluetoothManager)
    private val cellDecoder = CellDecoder()

    private var pollingJob: Job? = null

    fun attemptConnect() {
        if (GeneralData.state.value.isConnected) return

        GeneralData.updateState { current ->
            current.copy(debugInfo = "Пошук спарованих OBD пристроїв...")
        }

        scope.launch(Dispatchers.IO) {
            try {
                val pairedDevices = bluetoothManager.getPairedDevices()

                if (pairedDevices.isEmpty()) {
                    GeneralData.updateState { current ->
                        current.copy(
                            isConnected = false,
                            debugInfo = "Немає спарованих OBD пристроїв!"
                        )
                    }
                    return@launch
                }

                val targetDevice = pairedDevices.firstOrNull {
                    val name = it.name ?: ""
                    name.contains("Vlink", ignoreCase = true) ||
                    name.contains("OBD", ignoreCase = true) ||
                    name.contains("ELM", ignoreCase = true)
                } ?: pairedDevices.first()

                GeneralData.updateState { current ->
                    current.copy(debugInfo = "Підключення до ${targetDevice.name ?: "OBD"}...")
                }

                val connectResult = bluetoothManager.connect(targetDevice)

                if (connectResult == "OK") {
                    // Базова ініціалізація ELM327
                    bluetoothManager.sendCommand("AT Z")
                    delay(600)
                    bluetoothManager.sendCommand("AT E0")
                    bluetoothManager.sendCommand("AT L0")
                    bluetoothManager.sendCommand("AT CAF1")
                    bluetoothManager.sendCommand("AT SP 6")
                    delay(200)

                    GeneralData.updateState { current ->
                        current.copy(
                            isConnected = true,
                            debugInfo = "Підключено до ${targetDevice.name}"
                        )
                    }

                    // Запускаємо єдиний фоновий процес опитування CAN
                    startDataPolling()
                } else {
                    GeneralData.updateState { current ->
                        current.copy(
                            isConnected = false,
                            debugInfo = "Збій підключення: $connectResult"
                        )
                    }
                }
            } catch (e: Exception) {
                GeneralData.updateState { current ->
                    current.copy(
                        isConnected = false,
                        debugInfo = "Помилка: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /**
     * Фоновий процес опитування CAN-шини.
     * Залежно від прапорців у GeneralData зчитує загальні дані ВВБ або комірки.
     */
    private fun startDataPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive && GeneralData.state.value.isConnected) {
                try {
                    val inputBms = GeneralData.state.value.inputBms

                    // Якщо є запит від екрана комірок
                    if (inputBms.scanCellsRequested) {
                        val header = inputBms.customHeader.ifEmpty { "7E4" }
                        val commands = inputBms.cellCommands.ifEmpty { listOf("21 02", "21 03", "21 04") }
                        val rawResponses = mutableListOf<String>()

                        for (cmd in commands) {
                            var resp = canBridge.sendCANCommand(header, cmd)
                            
                            // Коротка повторна спроба, якщо адаптер дав сміття/пустоту
                            if (resp.isBlank() || resp.contains("NO DATA")) {
                                delay(100)
                                resp = canBridge.sendCANCommand(header, cmd)
                            }

                            rawResponses.add(resp)
                            
                            // Збільшена пауза для стабільної зборки багаторамкового ISO-TP кадру
                            delay(200)
                        }

                        val updatedInput = inputBms.copy(
                            scanCellsRequested = false,
                            rawResponses = rawResponses
                        )
                        GeneralData.updateInputBms { updatedInput }

                        // Декодуємо та заносимо комірки у GeneralData.state
                        val decodedCells = cellDecoder.decodeResponses(updatedInput)
                        GeneralData.updateCellData(decodedCells)
                    } else {
                        // Звичайний режим: опитування кадру 21 01 для головного екрана
                        val freshBms = batteryDecoder.getBatteryData()
                        GeneralData.updateState { current ->
                            current.copy(bms = freshBms)
                        }
                    }
                } catch (e: Exception) {
                    GeneralData.updateState { current ->
                        current.copy(debugInfo = "Помилка CAN: ${e.localizedMessage}")
                    }
                }

                delay(800)
            }
        }
    }

    fun disconnect() {
        pollingJob?.cancel()
        canBridge.reset()
        bluetoothManager.disconnect()
        GeneralData.updateState { current ->
            current.copy(
                isConnected = false,
                debugInfo = "Відключено"
            )
        }
    }
}
