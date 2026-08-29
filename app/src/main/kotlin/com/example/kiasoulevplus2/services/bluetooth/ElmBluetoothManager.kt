// ====================================================================================
// НИЗЬКОРІВНЕВИЙ BLUETOOTH-ДВИГУН ELM327 (ElmBluetoothManager)
//
// ПРИЗНАЧЕННЯ:
// Відповідає за низькорівневий обмін байтами через Bluetooth RFCOMM / SPP сокет з адаптером.
//
// ЩО ВІН РОБИТЬ:
// 1. Встановлює зв'язок із пристроєм за 3-ма алгоритмами (Insecure, Secure, Reflection).
// 2. sendCommand(): Відправляє сирий текстовий рядок (наприклад, "AT Z", "21 02\r") у Bluetooth-потік.
// 3. Чекає та збирає символи відповіді від адаптера до появи промпта '>'.
// 4. sendCANCommand(): Встановлює потрібний CAN-адрес заголовка (наприклад, "7E4" для BMS) та виконує команду.
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ знає, що означають відповіді (не перераховує HEX у Вольти).
// - НЕ обробляє логіку інтерфейсу та не оновлює State / GeneralData.
// ====================================================================================

package com.example.kiasoulevplus2.services.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class ElmBluetoothManager {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val connectMutex = Mutex()

    /**
     * Відправляє команду з попередньою установкою CAN-заголовка.
     * Наприклад: header = "7E4" (BMS), command = "21 02"
     */
    suspend fun sendCANCommand(header: String, command: String): String {
        sendCommand("AT SH $header")
        return sendCommand(command)
    }

    /**
     * Повертає список спарованих пристроїв.
     */
    fun getPairedDevices(): List<BluetoothDevice> {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            return emptyList()
        }
        return bluetoothAdapter.bondedDevices?.toList() ?: emptyList()
    }

    /**
     * Встановлює фізичне Bluetooth RFCOMM з'єднання.
     */
    suspend fun connect(device: BluetoothDevice): String = connectMutex.withLock {
        disconnect()

        return withContext(Dispatchers.IO) {
            try {
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (bluetoothAdapter?.isDiscovering == true) {
                    bluetoothAdapter.cancelDiscovery()
                }
            } catch (e: Exception) {
                // Ігноруємо відсутність дозволу SCAN
            }

            delay(100)

            // Спроба 1: Insecure SPP
            try {
                val newSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                newSocket.connect()
                if (setupStreams(newSocket)) return@withContext "OK"
            } catch (e: Exception) {
                val err1 = e.localizedMessage ?: e.javaClass.simpleName
                disconnect()
                delay(200)

                // Спроба 2: Secure SPP
                try {
                    val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    newSocket.connect()
                    if (setupStreams(newSocket)) return@withContext "OK"
                } catch (e2: Exception) {
                    val err2 = e2.localizedMessage ?: e2.javaClass.simpleName
                    disconnect()
                    delay(200)

                    // Спроба 3: Рефлексія
                    try {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        val newSocket = method.invoke(device, 1) as BluetoothSocket
                        newSocket.connect()
                        if (setupStreams(newSocket)) return@withContext "OK"
                    } catch (e3: Exception) {
                        val err3 = e3.localizedMessage ?: e3.javaClass.simpleName
                        return@withContext "Помилки: 1) $err1 | 2) $err2 | 3) $err3"
                    }
                }
            }
            "Не вдалося відкрити потоки (Stream NULL)"
        }
    }

    private fun setupStreams(newSocket: BluetoothSocket): Boolean {
        return try {
            socket = newSocket
            input = newSocket.inputStream
            output = newSocket.outputStream
            input != null && output != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Пряма відправка текстової команди в адаптер з вичитуванням відповіді до промпта '>'
     */
    fun sendCommand(command: String): String {
        val out = output ?: return "ERROR: No OutStream"
        val inp = input ?: return "ERROR: No InStream"

        return try {
            if (inp.available() > 0) {
                inp.skip(inp.available().toLong())
            }

            val formattedCommand = if (command.endsWith("\r")) command else "$command\r"
            out.write(formattedCommand.toByteArray())
            out.flush()

            val resultBuilder = StringBuilder()
            val buffer = ByteArray(1024)

            var attempts = 0
            while (attempts < 25) {
                Thread.sleep(100)
                val bytesAvailable = inp.available()

                if (bytesAvailable > 0) {
                    val bytesRead = inp.read(buffer)
                    val chunk = String(buffer, 0, bytesRead)
                    resultBuilder.append(chunk)

                    if (resultBuilder.contains(">")) {
                        break
                    }
                    attempts = 0
                } else {
                    attempts++
                }
            }

            resultBuilder.toString()
        } catch (e: Exception) {
            "ERROR: IO"
        }
    }

    fun disconnect() {
        try {
            input?.close()
            output?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        input = null
        output = null
        socket = null
    }
}
