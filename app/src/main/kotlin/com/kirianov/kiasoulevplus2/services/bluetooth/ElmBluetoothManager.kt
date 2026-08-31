// ====================================================================================
// НИЗЬКОРІВНЕВИЙ BLUETOOTH-ДВИГУН ELM327 (ElmBluetoothManager)
//
// ЩО ВІН РОБИТЬ:
// 1. Встановлює RFCOMM/SPP з'єднання трьома способами (Insecure, Secure, Reflection).
// 2. sendCommand(): надсилає текстову команду і читає відповідь до промпта '>'.
// 3. Кидає IOException, якщо сокет помер або адаптер мовчить, — щоб той, хто викликав,
//    міг відрізнити «немає зв'язку» від «зв'язок є, але відповідь порожня».
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ знає, що означають відповіді, і не чіпає GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class ElmBluetoothManager : ElmAdapter {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val connectMutex = Mutex()

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Повертає список спарованих пристроїв. */
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    /**
     * Обирає адаптер OBD серед спарованих пристроїв за ключовими словами в назві.
     * Якщо жоден не підійшов — бере перший доступний.
     */
    fun findObdDevice(): BluetoothDevice? {
        val paired = getPairedDevices()
        return paired.firstOrNull { device ->
            val name = device.name ?: ""
            OBD_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
        } ?: paired.firstOrNull()
    }

    /** Встановлює фізичне Bluetooth RFCOMM з'єднання. Кидає IOException при невдачі. */
    suspend fun connect(device: BluetoothDevice) = connectMutex.withLock {
        disconnect()

        withContext(Dispatchers.IO) {
            runCatching {
                BluetoothAdapter.getDefaultAdapter()?.takeIf { it.isDiscovering }?.cancelDiscovery()
            }
            delay(100)

            val failures = mutableListOf<String>()
            for (strategy in connectionStrategies()) {
                try {
                    val newSocket = strategy.open(device)
                    newSocket.connect()
                    if (attachStreams(newSocket)) return@withContext
                    failures += "${strategy.label}: потоки не відкрилися"
                } catch (e: Exception) {
                    failures += "${strategy.label}: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    disconnect()
                    delay(200)
                }
            }
            throw IOException(failures.joinToString(" | "))
        }
    }

    private fun connectionStrategies() = listOf(
        ConnectionStrategy("Insecure SPP") { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        ConnectionStrategy("Secure SPP") { it.createRfcommSocketToServiceRecord(SPP_UUID) },
        ConnectionStrategy("Reflection") { device ->
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        },
    )

    private class ConnectionStrategy(
        val label: String,
        val open: (BluetoothDevice) -> BluetoothSocket,
    )

    private fun attachStreams(newSocket: BluetoothSocket): Boolean = try {
        socket = newSocket
        input = newSocket.inputStream
        output = newSocket.outputStream
        input != null && output != null
    } catch (e: IOException) {
        false
    }

    /**
     * Надсилає команду і читає відповідь до промпта '>'.
     * Очікування зроблене через delay, а не Thread.sleep, тому опитування можна скасувати
     * разом із корутиною, і потік вводу-виводу не блокується намертво.
     */
    override suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
        val out = output ?: throw IOException("Bluetooth-потік запису закритий")
        val inp = input ?: throw IOException("Bluetooth-потік читання закритий")

        // Викидаємо хвіст попередньої відповіді, щоб він не приклеївся до нової.
        if (inp.available() > 0) inp.skip(inp.available().toLong())

        val payload = if (command.endsWith("\r")) command else "$command\r"
        out.write(payload.toByteArray())
        out.flush()

        readUntilPrompt(inp, command)
    }

    private suspend fun readUntilPrompt(inp: InputStream, command: String): String {
        val response = StringBuilder()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var idlePolls = 0

        while (idlePolls < MAX_IDLE_POLLS) {
            delay(POLL_INTERVAL_MS)
            if (inp.available() <= 0) {
                idlePolls++
                continue
            }

            val bytesRead = inp.read(buffer)
            if (bytesRead < 0) throw IOException("З'єднання розірвано під час читання")

            response.append(String(buffer, 0, bytesRead))
            if (response.contains(PROMPT)) return response.toString()
            idlePolls = 0
        }

        throw IOException("Адаптер не відповів на «$command» за ${MAX_IDLE_POLLS * POLL_INTERVAL_MS} мс")
    }

    fun disconnect() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val OBD_NAME_HINTS = listOf("Vlink", "OBD", "ELM")
        const val PROMPT = ">"
        const val READ_BUFFER_SIZE = 1024
        const val POLL_INTERVAL_MS = 100L
        const val MAX_IDLE_POLLS = 25
    }
}
