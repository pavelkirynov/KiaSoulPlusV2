// ====================================================================================
// МІСТ ДО CAN-ШИНИ ЧЕРЕЗ ELM327 (ElmCANBridge)
//
// Єдина точка, через яку в додатку йдуть CAN-запити: тримає стан ініціалізації адаптера,
// сам переініціалізує його після збою і кидає IOException, коли зв'язок втрачено.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import kotlinx.coroutines.delay
import java.io.IOException

class ElmCANBridge(private val bluetoothManager: ElmBluetoothManager) {

    @Volatile
    private var isInitialized = false

    /**
     * Виставляє CAN-заголовок і надсилає команду.
     * Кидає IOException, якщо адаптер не ініціалізувався або зв'язок обірвався.
     */
    suspend fun sendCANCommand(header: String, command: String): String {
        if (!isInitialized) initAdapter()

        try {
            val headerResponse = bluetoothManager.sendCommand("AT SH $header")
            if (headerResponse.contains("?") || headerResponse.uppercase().contains("ERROR")) {
                isInitialized = false
                throw IOException("Адаптер відхилив заголовок $header")
            }
            delay(INTER_COMMAND_DELAY_MS)

            val response = bluetoothManager.sendCommand(command)
            if (LINK_LOST_MARKERS.any { response.uppercase().contains(it) }) {
                isInitialized = false
                throw IOException("Втрачено зв'язок із шиною: $response")
            }
            return response
        } catch (e: IOException) {
            isInitialized = false
            throw e
        }
    }

    /** Повна послідовність налаштування ELM327. Раніше вона дублювалася в ConnectionManager. */
    suspend fun initAdapter() {
        isInitialized = false

        bluetoothManager.sendCommand("AT Z")           // повне скидання
        delay(RESET_DELAY_MS)
        bluetoothManager.sendCommand("AT E0")          // вимикаємо ехо
        delay(SHORT_DELAY_MS)
        bluetoothManager.sendCommand("AT L0")          // вимикаємо Line Feed
        delay(SHORT_DELAY_MS)
        bluetoothManager.sendCommand("AT CAF1")        // CAN Auto Formatting
        delay(SHORT_DELAY_MS)

        // ISO 15765-4 CAN, 11 bit ID, 500 kbaud
        val protocolResponse = bluetoothManager.sendCommand("AT SP 6")
        delay(PROTOCOL_DELAY_MS)

        if (protocolResponse.uppercase().contains("ERROR")) {
            throw IOException("Адаптер не прийняв протокол ISO 15765-4: $protocolResponse")
        }
        isInitialized = true
    }

    fun reset() {
        isInitialized = false
    }

    private companion object {
        val LINK_LOST_MARKERS = listOf("UNABLE TO CONNECT", "CAN ERROR", "BUS INIT: ERROR")
        const val INTER_COMMAND_DELAY_MS = 60L
        const val SHORT_DELAY_MS = 120L
        const val RESET_DELAY_MS = 1000L
        const val PROTOCOL_DELAY_MS = 200L
    }
}
