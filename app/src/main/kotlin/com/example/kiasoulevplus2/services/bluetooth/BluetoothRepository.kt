// ====================================================================================
// МЕНЕДЖЕР BLUETOOTH-З'ЄДНАННЯ (BluetoothRepository)
//
// ПРИЗНАЧЕННЯ:
// Цей клас відповідає ВИКЛЮЧНО за пошук спарованих пристроїв та встановлення/розрив
// фізичного Bluetooth-з'єднання з OBD2-адаптером (ELM327 / Vlink).
//
// ЩО ВІН РОБИТЬ:
// 1. Отримує список спарованих пристроїв у системі Android.
// 2. Автоматично шукає пристрій за ключовими словами у назві ("Vlink", "OBD", "ELM").
// 3. Ініціалізує з'єднання через ElmBluetoothManager.
// 4. Корректно закриває Bluetooth-сокет при роз'єднанні.
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ відправляє AT-команди чи PID-запити (типу "21 02", "AT SH 7E4").
// - НЕ зчитує та не обробляє відповіді від блоків BMS / VCU / LDC.
// - НЕ оновлює дані в GeneralData чи State (лише повертає Result успіху/помилки).
// ====================================================================================

package com.example.kiasoulevplus2.services.bluetooth

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BluetoothRepository(
    private val bluetoothManager: ElmBluetoothManager
) {
    /**
     * Повертає список пристроїв, які вже спаровані в налаштуваннях Bluetooth смартфона.
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothManager.getPairedDevices()
    }

    /**
     * Виконує пошук відповідного OBD-адаптера та встановлює з ним з'єднання.
     * @param onStatusUpdate Лямбда для передачі текстового статусу в UI (наприклад, "Підключення...")
     * @return Result.success(строка) або Result.failure(exception)
     */
    suspend fun connectToObd(
        onStatusUpdate: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val pairedDevices = bluetoothManager.getPairedDevices()

            if (pairedDevices.isEmpty()) {
                return@withContext Result.failure(Exception("Немає спарованих OBD пристроїв"))
            }

            // Пріоритетний пошук пристроїв за назвою
            val targetDevice = pairedDevices.firstOrNull {
                val name = it.name ?: ""
                name.contains("Vlink", ignoreCase = true) ||
                name.contains("OBD", ignoreCase = true) ||
                name.contains("ELM", ignoreCase = true)
            } ?: pairedDevices.first()

            onStatusUpdate("Підключення до ${targetDevice.name ?: "OBD"}...")

            val result = bluetoothManager.connect(targetDevice)
            if (result == "OK") {
                Result.success("Підключено до ${targetDevice.name}")
            } else {
                Result.failure(Exception("Збій: $result"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Розриває активне Bluetooth-з'єднання.
     */
    fun disconnect() {
        bluetoothManager.disconnect()
    }
}
