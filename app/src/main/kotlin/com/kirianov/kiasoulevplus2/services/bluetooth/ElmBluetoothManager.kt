// ====================================================================================
// НИЗЬКОРІВНЕВИЙ BLUETOOTH-ДВИГУН ELM327 (ElmBluetoothManager)
//
// ЩО ВІН РОБИТЬ:
// 1. Встановлює RFCOMM/SPP з'єднання трьома способами (Insecure, Secure, Reflection).
// 2. Віддає операції над відкритими потоками в ElmStream і нічого не читає сам.
// 3. Кидає IOException, якщо сокета немає, — щоб той, хто викликав, міг відрізнити
//    «немає зв'язку» від «зв'язок є, але відповідь порожня».
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ знає, що означають відповіді, і не чіпає GeneralData.
// - НЕ крутить циклів читання: усі вони, разом зі своїми бюджетами часу, живуть
//   в ElmStream, де їх видно тестам. Тут лишилася тільки Bluetooth-частина, яку
//   перевірити нічим, крім самого авто.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class ElmBluetoothManager : ElmAdapter {

    private var socket: BluetoothSocket? = null

    /** Протокол над відкритими потоками. null означає «сокета немає». */
    @Volatile
    private var stream: ElmStream? = null

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
        val input = newSocket.inputStream
        val output = newSocket.outputStream
        stream = if (input != null && output != null) ElmStream(input, output) else null
        stream != null
    } catch (e: IOException) {
        false
    }

    private fun link(): ElmStream = stream ?: throw IOException("Bluetooth-потік закритий")

    /**
     * Очікування всередині зроблене через delay, а не Thread.sleep, тому опитування
     * можна скасувати разом із корутиною, і потік вводу-виводу не блокується намертво.
     */
    override suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
        link().send(command)
    }

    override suspend fun writeRaw(text: String) = withContext(Dispatchers.IO) {
        link().writeRaw(text)
    }

    override suspend fun readAvailable(): String = withContext(Dispatchers.IO) {
        link().readAvailable()
    }

    override suspend fun flushInput(): Boolean = withContext(Dispatchers.IO) {
        // Сокета вже немає — сушити нічого, і це не помилка: обрив міг статися
        // рівно між вікном монітора і виходом із нього.
        val active = stream ?: return@withContext true
        active.drain()
    }

    fun disconnect() {
        runCatching { socket?.inputStream?.close() }
        runCatching { socket?.outputStream?.close() }
        runCatching { socket?.close() }
        stream = null
        socket = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val OBD_NAME_HINTS = listOf("Vlink", "OBD", "ELM")
    }
}
