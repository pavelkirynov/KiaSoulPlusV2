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
        /** Що адаптер віддає шматками у вікні монітора — як у справжньому потоці. */
        private val monitorChunks: List<String> = emptyList(),
        /** Чи стихає потік після зупинки монітора. false — адаптер застряг у «AT MA». */
        private val quiet: Boolean = true,
    ) : ElmAdapter {
        val sent = mutableListOf<String>()
        val raw = mutableListOf<String>()
        var flushes = 0
        private var chunkIndex = 0

        override suspend fun sendCommand(command: String): String {
            sent += command
            delay(1)
            return if (command.startsWith("AT")) "OK" else "61 01 AA"
        }

        override suspend fun writeRaw(text: String) {
            raw += text
        }

        override suspend fun readAvailable(): String =
            monitorChunks.getOrNull(chunkIndex++) ?: ""

        override suspend fun flushInput(): Boolean {
            flushes++
            return quiet
        }
    }

    /** Адаптер, який не дає відкотити налаштування після монітора. */
    private class FailingRestoreAdapter : ElmAdapter {
        val sent = mutableListOf<String>()

        private var monitorSeen = false

        override suspend fun sendCommand(command: String): String {
            sent += command
            if (command == "AT CAF0") monitorSeen = true
            if (command == "AT CAF1" && monitorSeen) {
                monitorSeen = false
                throw java.io.IOException("адаптер відвалився")
            }
            return "OK"
        }

        override suspend fun writeRaw(text: String) = Unit
        override suspend fun readAvailable(): String = ""
        override suspend fun flushInput(): Boolean = true
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
        val adapter = RecordingAdapter(
            monitorChunks = listOf("4F0 00 5A 00 00 00 B3 C1 1C\r", "653 00 00\r"),
        )
        val bridge = ElmCANBridge(adapter)

        val lines = bridge.monitorBroadcast(windowMs = 200, filterId = "4F0")

        assertEquals(listOf("4F0 00 5A 00 00 00 B3 C1 1C", "653 00 00"), lines)
        assertTrue(adapter.raw.contains("AT MA\r"))
    }

    /**
     * Найважливіше з поля: без фільтра адаптер захлинається трафіком шини, а з
     * типовими H0/CAF1 віддає кадри без ID і зі службовими індексами «0:».
     */
    @Test
    fun `a monitor window sets the filter, headers on and formatting off`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = listOf("4F0 00\r"))
        val bridge = ElmCANBridge(adapter)
        bridge.initAdapter()
        adapter.sent.clear()

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")

        val beforeMonitor = adapter.sent.take(3)
        assertEquals(listOf("AT CRA 4F0", "AT H1", "AT CAF0"), beforeMonitor)
    }

    /** Один кадр, розрізаний між двома читаннями, мусить склеїтися, а не подвоїтися. */
    @Test
    fun `a frame split across two reads is joined`() = runBlocking {
        val adapter = RecordingAdapter(
            monitorChunks = listOf("4F0 00 5A 00 00", " 00 B3 C1 1C\r"),
        )
        val bridge = ElmCANBridge(adapter)

        val lines = bridge.monitorBroadcast(windowMs = 200, filterId = "4F0")

        assertEquals(listOf("4F0 00 5A 00 00 00 B3 C1 1C"), lines)
    }

    /**
     * Незавершений хвіст відкидається: половина кадру виглядала б як повний кадр
     * і дала б зсунуті байти, тобто тихо неправильний пробіг.
     */
    @Test
    fun `an unfinished tail is dropped`() = runBlocking {
        val adapter = RecordingAdapter(
            monitorChunks = listOf("4F0 00 5A 00 00 00 B3 C1 1C\r4F0 00 5A 00"),
        )
        val bridge = ElmCANBridge(adapter)

        val lines = bridge.monitorBroadcast(windowMs = 200, filterId = "4F0")

        assertEquals(listOf("4F0 00 5A 00 00 00 B3 C1 1C"), lines)
    }

    /** «BUFFER FULL» означає, що адаптер сам зупинив монітор: далі буде сміття. */
    @Test
    fun `the window stops at buffer full`() = runBlocking {
        val adapter = RecordingAdapter(
            monitorChunks = listOf("4F0 00 5A 00 00 00 B3 C1 1C\r", "BUFFER FULL\r"),
        )
        val bridge = ElmCANBridge(adapter)

        val started = System.currentTimeMillis()
        val lines = bridge.monitorBroadcast(windowMs = 5_000, filterId = "4F0")
        val spent = System.currentTimeMillis() - started

        assertTrue(lines.contains("4F0 00 5A 00 00 00 B3 C1 1C"))
        assertTrue("Вікно не зупинилося на BUFFER FULL: $spent мс", spent < 2_000)
    }

    /**
     * Найважливіше про монітор: якщо не надіслати пробіл і не добитися промпта,
     * адаптер лишається в моніторі, і всі подальші запити 21/22 перестають працювати.
     */
    @Test
    fun `leaving the monitor sends a space and restores the request settings`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = listOf("4F0 00\r"))
        val bridge = ElmCANBridge(adapter)

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")

        assertTrue("Пробіл не надіслано", adapter.raw.contains(" "))
        assertTrue("Промпт не добивався", adapter.sent.contains("AT AR"))
        assertTrue("Фільтр не знято", adapter.sent.contains("AT CRA"))
        assertTrue("Заголовки не вимкнено назад", adapter.sent.contains("AT H0"))
        assertTrue("Автоформатування не повернуто", adapter.sent.contains("AT CAF1"))
    }

    /**
     * Якщо відкотити H0/CAF1 не вдалося, адаптер лишився у форматі монітора.
     * Наступний запит мусить пройти повну переініціалізацію, а не тихо зламатися.
     */
    @Test
    fun `a failed restore forces a full re-init on the next request`() = runBlocking {
        val adapter = FailingRestoreAdapter()
        val bridge = ElmCANBridge(adapter)
        bridge.initAdapter()
        adapter.sent.clear()

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")
        bridge.sendCANCommand("7E4", "21 01")

        assertTrue("Переініціалізації не було", adapter.sent.contains("AT Z"))
    }

    /** Якщо промпт відповів з першого разу, добиватися його ще чотири рази немає сенсу. */
    @Test
    fun `the exit stops asking as soon as the prompt answers`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = listOf("4F0 00\r"))
        val bridge = ElmCANBridge(adapter)

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")

        assertEquals(1, adapter.sent.count { it == "AT AR" })
    }

    /** Тихої шини (запалювання вимкнене) не треба слухати все вікно. */
    @Test
    fun `a silent bus ends the window early instead of waiting it out`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = emptyList())
        val bridge = ElmCANBridge(adapter)

        val started = System.currentTimeMillis()
        val lines = bridge.monitorBroadcast(windowMs = 5_000, filterId = "4F0")
        val spent = System.currentTimeMillis() - started

        assertTrue(lines.isEmpty())
        assertTrue("Вікно чекало все 5 с: $spent мс", spent < 2_000)
    }

    /** Після монітора звичайні запити мають працювати як раніше. */
    @Test
    fun `a request after a monitor window still pairs header with command`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = listOf("4F0 00\r"))
        val bridge = ElmCANBridge(adapter)

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")
        adapter.sent.clear()
        bridge.sendCANCommand("7E4", "21 01")

        assertEquals(listOf("AT SH 7E4", "21 01"), adapter.sent)
    }

    /**
     * Найдорожчий баг цього застосунку: адаптер лишається в моніторі, сипле кадри
     * без промпта — і зупинити його ніхто не намагається. Тоді жодна команда не
     * доходить, а перезапуск застосунку не лікує, бо застряг адаптер, не застосунок.
     */
    @Test
    fun `init stops a monitor that never stopped before saying anything`() = runBlocking {
        val adapter = RecordingAdapter(quiet = false)
        val bridge = ElmCANBridge(adapter)

        bridge.initAdapter()

        // Пробіл мусить піти ПЕРЕД «AT Z», інакше скидання потонуло б у потоці кадрів.
        assertTrue("Монітор не зупиняли перед скиданням", adapter.raw.contains(" "))
        assertTrue("Буфер не сушили перед скиданням", adapter.flushes > 0)
        assertEquals("AT Z", adapter.sent.first())
    }

    /**
     * Потік, який не стих після пробілу, означає, що монітор досі працює. Вважати
     * адаптер готовим після цього не можна: наступний запит мусить його скинути.
     */
    @Test
    fun `a stream that never goes quiet forces a full re-init`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = listOf("4F0 00\r"), quiet = false)
        val bridge = ElmCANBridge(adapter)
        bridge.initAdapter()
        adapter.sent.clear()

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")
        bridge.sendCANCommand("7E4", "21 01")

        assertTrue("Переініціалізації не було", adapter.sent.contains("AT Z"))
    }

    /** А коли потік стих і промпт відповів, скидати нічого не треба. */
    @Test
    fun `a clean exit from the monitor keeps the adapter initialised`() = runBlocking {
        val adapter = RecordingAdapter(monitorChunks = listOf("4F0 00\r"))
        val bridge = ElmCANBridge(adapter)
        bridge.initAdapter()
        adapter.sent.clear()

        bridge.monitorBroadcast(windowMs = 100, filterId = "4F0")
        bridge.sendCANCommand("7E4", "21 01")

        assertTrue("Зайве скидання адаптера", !adapter.sent.contains("AT Z"))
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
