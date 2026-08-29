package com.example.kiasoulevplus2.services.bluetooth

class ElmCANBridge(private val bluetoothManager: ElmBluetoothManager) {

    @Volatile
    private var isInitialized = false

    fun sendCANCommand(header: String, command: String): String {
        if (!isInitialized) {
            try {
                if (!initAdapter()) {
                    isInitialized = false
                    return "INIT_ERROR"
                }
            } catch (e: Exception) {
                isInitialized = false
                return "INIT_ERROR"
            }
        }

        return try {
            // 1. Встановлюємо хедер (7E4 для BMS, 7E0 для приборки)
            val headerResponse = bluetoothManager.sendCommand("AT SH $header")
            if (headerResponse.contains("?") || headerResponse.contains("ERROR")) {
                isInitialized = false
                return "HEADER_ERROR"
            }
            Thread.sleep(60)

            // 2. Відправляємо запит (наприклад, "21 01")
            val response = bluetoothManager.sendCommand(command)
            
            // Якщо зв'язок втрачено або адаптер скинувся
            if (response.contains("ERROR") || 
                response.contains("STOPPED") || 
                response.contains("UNABLE TO CONNECT") ||
                response.contains("CAN ERROR")
            ) {
                isInitialized = false
            }

            response
        } catch (e: Exception) {
            isInitialized = false
            "ERROR"
        }
    }

    private fun initAdapter(): Boolean {
        isInitialized = false

        // 1. Повне скидання адаптера
        bluetoothManager.sendCommand("AT Z")
        Thread.sleep(1000)

        // 2. Вимикаємо ехо повтору команд
        bluetoothManager.sendCommand("AT E0")
        Thread.sleep(150)

        // 3. Вимикаємо Line Feed
        bluetoothManager.sendCommand("AT L0")
        Thread.sleep(100)

        // 4. Вмикаємо CAN Auto Formatting (обов'язково для кадрів 21 01)
        bluetoothManager.sendCommand("AT CAF1")
        Thread.sleep(100)

        // 5. Встановлюємо протокол ISO 15765-4 CAN (11 bit ID, 500 kbaud)
        val atsp6 = bluetoothManager.sendCommand("AT SP 6")
        Thread.sleep(200)

        // Перевіряємо, чи адаптер відповів OK на вибір протоколу
        val isOk = atsp6.uppercase().contains("OK") || !atsp6.uppercase().contains("ERROR")
        
        isInitialized = isOk
        return isInitialized
    }

    fun reset() {
        isInitialized = false
    }
}
