package com.kirianov.kiasoulevplus2.services.bluetooth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регресія на зависання: «показує з'єднання, але дані не змінюються».
 *
 * Причина була одна на два цикли — адаптер, який лишився в режимі монітора, сипле
 * кадри безперервно й ніколи не друкує промпт «>». Старе сушіння буфера крутилося,
 * поки в буфері є байти (тобто вічно, ще й без крапки переривання), а старе читання
 * відповіді чекало 25 ТИХИХ читань, яких у балакучому потоці не буває.
 *
 * Тому нескінченний потік тут — головний піддослідний. Кожна операція мусить
 * ЗАВЕРШИТИСЯ: або результатом, або помилкою, але не мовчанням.
 */
class ElmStreamTest {

    /** Адаптер, який сипле кадри без кінця: рівно те, що робить незупинений «AT MA». */
    private class EndlessInput(chunk: String = "4F0 00 5A 00 00 00 B3 C1 1C\r") : InputStream() {
        private val bytes = chunk.toByteArray(Charsets.ISO_8859_1)
        private var index = 0

        @Volatile
        var reads = 0L
            private set

        override fun read(): Int {
            if (index >= bytes.size) index = 0
            return bytes[index++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            for (i in 0 until len) b[off + i] = read().toByte()
            reads++
            return len
        }

        // Ніколи не нуль: шина наповнює буфер швидше, ніж його читають.
        override fun available(): Int = bytes.size
    }

    /**
     * Адаптер, який мовчить, поки з ним не заговорили, а потім віддає свій сценарій
     * і замовкає. Саме такий порядок і буває насправді: відповідь приходить ПІСЛЯ
     * команди, і дешеве «зняти хвіст» перед записом не має її з'їдати.
     */
    private class ScriptedInput(script: String) : InputStream() {
        private val bytes = script.toByteArray(Charsets.ISO_8859_1)
        private var index = 0

        @Volatile
        var answering = false

        override fun read(): Int = if (available() <= 0) -1 else bytes[index++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (available() <= 0) return -1
            val count = minOf(len, bytes.size - index)
            System.arraycopy(bytes, index, b, off, count)
            index += count
            return count
        }

        override fun available(): Int = if (!answering) 0 else bytes.size - index
    }

    /** Запис у порт — це і є момент, коли адаптер починає відповідати. */
    private class TriggeringOutput(private val input: ScriptedInput) : OutputStream() {
        val written = ByteArrayOutputStream()

        override fun write(b: Int) {
            written.write(b)
            input.answering = true
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written.write(b, off, len)
            input.answering = true
        }

        fun text(): String = written.toString("ISO-8859-1")
    }

    private fun endless(drainBudgetMs: Long = 300L, input: EndlessInput = EndlessInput()) =
        ElmStream(input, ByteArrayOutputStream(), responseBudgetMs = 400L, drainBudgetMs = drainBudgetMs)

    private fun scripted(script: String, answering: Boolean = false): Pair<ElmStream, TriggeringOutput> {
        val input = ScriptedInput(script)
        input.answering = answering
        val output = TriggeringOutput(input)
        return ElmStream(input, output, responseBudgetMs = 400L, drainBudgetMs = 300L) to output
    }

    @Test
    fun `draining a stream that never stops gives up instead of spinning forever`() = runBlocking {
        val stream = endless()

        val started = System.currentTimeMillis()
        val quiet = stream.drain()
        val spent = System.currentTimeMillis() - started

        assertFalse("Сказав, що потік стих, хоча кадри йдуть", quiet)
        assertTrue("Сушіння не вклалося в бюджет: $spent мс", spent < 3_000)
    }

    @Test
    fun `draining a stream that stops reports quiet`() = runBlocking {
        // У буфері вже лежить недочитаний кадр: його треба зняти й побачити тишу.
        val (stream, _) = scripted("4F0 00 5A 00\r", answering = true)

        assertTrue("Тихий потік не визнали тихим", stream.drain())
    }

    /**
     * Найважливіше: цикл сушіння мусить мати крапку переривання. Без неї корутину
     * не скасувати — ні таймаутом, ні від'єднанням, — і вона крутить ядро до кінця
     * життя процесу.
     */
    @Test
    fun `draining can be cancelled`() = runBlocking {
        val input = EndlessInput()
        val stream = endless(drainBudgetMs = 60_000L, input = input)

        val job = launch(Dispatchers.Default) { stream.drain() }
        withTimeout(3_000) {
            while (input.reads == 0L) yield() // дочекатися, поки цикл справді почався
            job.cancel()
            job.join()
        }
        assertTrue("Сушіння не скасувалося", job.isCancelled)
    }

    /**
     * Балакучий потік без промпта — це і був вічний цикл: тихих читань не буває,
     * тож стара умова виходу не наставала ніколи.
     */
    @Test
    fun `a response that never reaches the prompt fails instead of hanging`() = runBlocking {
        val stream = endless()

        val started = System.currentTimeMillis()
        val error = runCatching { stream.send("AT Z") }.exceptionOrNull()
        val spent = System.currentTimeMillis() - started

        assertTrue("Очікували IOException, отримали $error", error is IOException)
        assertTrue("Читання не вклалося в бюджет: $spent мс", spent < 3_000)
    }

    @Test
    fun `a response with a prompt comes back whole`() = runBlocking {
        val (stream, _) = scripted("7EC 21 01\r61 01 FF\r>")

        val response = stream.send("21 01")

        assertTrue("Відповідь обрізана: $response", response.contains("61 01 FF"))
    }

    @Test
    fun `a command gets a carriage return and reaches the adapter`() = runBlocking {
        val (stream, output) = scripted(">")

        stream.send("AT SH 7E4")

        assertEquals("AT SH 7E4\r", output.text())
    }

    /** Мертвий адаптер мусить давати помилку за бюджетом, а не чекати вічно. */
    @Test
    fun `a silent adapter fails by timeout`() = runBlocking {
        val input = ScriptedInput("")
        val stream = ElmStream(input, ByteArrayOutputStream(), responseBudgetMs = 400L, drainBudgetMs = 300L)

        val error = runCatching { stream.send("AT Z") }.exceptionOrNull()

        assertTrue("Очікували IOException, отримали $error", error is IOException)
    }
}
