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
    private class RecordingAdapter : ElmAdapter {
        val sent = mutableListOf<String>()

        override suspend fun sendCommand(command: String): String {
            sent += command
            delay(1)
            return if (command.startsWith("AT")) "OK" else "61 01 AA"
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
    fun `the adapter is initialised once, not per request`() = runBlocking {
        val adapter = RecordingAdapter()
        val bridge = ElmCANBridge(adapter)

        bridge.sendCANCommand("7E4", "21 01")
        bridge.sendCANCommand("7E4", "21 02")

        assertEquals(1, adapter.sent.count { it == "AT Z" })
    }
}
