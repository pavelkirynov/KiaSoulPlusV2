package com.kirianov.kiasoulevplus2.services.bluetooth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регресія на баг, через який відповіді злипалися: цикл опитування та екран
 * «Експерименти» йшли в адаптер одночасно, і чужа команда вклинювалася між
 * «AT SH <заголовок>» і самим запитом.
 *
 * Мост тут перевіряється через підставний адаптер: справжній Bluetooth не потрібен.
 */
class ElmCANBridgeTest {

    /** Записує послідовність команд і навмисно «гальмує», щоб дати шанс перемішатися. */
    private class RecordingAdapter(
        private val monitorLines: List<String> = emptyList(),
    ) : ElmAdapter {
        val sent = mutableListOf<String>()
        val raw = mutableListOf<String>()
        var flushes = 0
        private var lineIndex = 0

        override suspend fun sendCommand(command: String): String {
            sent += command
            delay(1)
            return if (command.startsWith("AT")) "OK" else "61 01 AA"
        }

        override suspend fun writeRaw(text: String) {
            raw += text
        }

        override suspend fun readLine(timeoutMs: Long): String? =
            monitorLines.getOrNull(lineIndex++)

        override suspend fun flushInput() {
            flushes++
        }
    }

    @Test
    fun `a header and its command are never split by another request`() = runBlocking {
        val adapter = RecordingAdapter()
        val bridge = ElmCANBridge(adapter)
        bridge.initAdapter()
        adapter.sent.clear()

        val requests = listOf("7E4" to "21 01", "7C6" to "22 B0 02", "7E2" to "21 01")
        requests.map { (header, command) ->
            async(Dispatchers.Default) { bridge.sendCANCommand(header, command) }
        }.awaitAll()

        // Кожна команда має йти одразу за своїм заголовком.
        assertEquals(requests.size * 2, adapter.sent.size)
        adapter.sent.chunked(2).forEach { (header, command) ->
            assertTrue("Заголовок і команда розійшлися: $header, $command", header.startsWith("AT SH"))
            val expected = requests.first { header == "AT SH ${it.first}" }.second
            assertEquals(expected, command)
        }
    }

    @Test
    fun `a monitor window collects the lines it saw`() = runBlocking {
        val adapter = RecordingAdapter(monitorLines = listOf("4F0 00 5A 00 00 00 B3 C1 1C", "653 00"))
        val bridge = ElmCANBridge(adapter)

        val lines = bridge.monitorBroadcast(windowMs = 200)

        assertTrue(lines.contains("4F0 00 5A 00 00 00 B3 C1 1C"))
        assertTrue(adapter.raw.contains("AT MA\r"))
    }

    /**
     * Найважливіше про монітор: якщо не надіслати пробіл і не добитися промпта,
     * адаптер лишається в моніторі, і всі подальші запити 22 xx перестають працювати.
     */
    @Test
    fun `leaving the monitor sends a space and waits for the prompt`() = runBlocking {
        val adapter = RecordingAdapter(monitorLines = listOf("4F0 00"))
        val bridge = ElmCANBridge(adapter)

        bridge.monitorBroadcast(windowMs = 100)

        assertTrue("Пробіл не надіслано", adapter.raw.contains(" "))
        assertTrue("Промпт не добивався", adapter.sent.contains("AT AR"))
        assertTrue("Фільтр не знято", adapter.sent.contains("AT CRA"))
    }

    /** Якщо промпт відповів з першого разу, добиватися його ще чотири рази немає сенсу. */
    @Test
    fun `the exit stops asking as soon as the prompt answers`() = runBlocking {
        val adapter = RecordingAdapter(monitorLines = listOf("4F0 00"))
        val bridge = ElmCANBridge(adapter)

        bridge.monitorBroadcast(windowMs = 100)

        assertEquals(1, adapter.sent.count { it == "AT AR" })
    }

    /** Тихої шини (зажигання вимкнене) не треба слухати все вікно. */
    @Test
    fun `a silent bus ends the window early instead of waiting it out`() = runBlocking {
        val adapter = RecordingAdapter(monitorLines = emptyList())
        val bridge = ElmCANBridge(adapter)

        val started = System.currentTimeMillis()
        val lines = bridge.monitorBroadcast(windowMs = 5_000)
        val spent = System.currentTimeMillis() - started

        assertTrue(lines.isEmpty())
        assertTrue("Вікно чекало все 5 с: $spent мс", spent < 2_000)
    }

    /** Після монітора звичайні запити мають працювати як раніше. */
    @Test
    fun `a request after a monitor window still pairs header with command`() = runBlocking {
        val adapter = RecordingAdapter(monitorLines = listOf("4F0 00"))
        val bridge = ElmCANBridge(adapter)

        bridge.monitorBroadcast(windowMs = 100)
        adapter.sent.clear()
        bridge.sendCANCommand("7E4", "21 01")

        assertEquals(listOf("AT SH 7E4", "21 01"), adapter.sent)
    }

    @Test
    fun `the adapter is initialised once, not per request`() = runBlocking {
        val adapter = RecordingAdapter()
        val bridge = ElmCANBridge(adapter)

        bridge.sendCANCommand("7E4", "21 01")
        bridge.sendCANCommand("7E4", "21 02")

        assertEquals(1, adapter.sent.count { it == "AT Z" })
    }
}
