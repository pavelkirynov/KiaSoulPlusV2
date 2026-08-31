// ====================================================================================
// МІСТ ДО CAN-ШИНИ ЧЕРЕЗ ELM327 (ElmCANBridge)
//
// Єдина точка, через яку в додатку йдуть CAN-запити: тримає стан ініціалізації адаптера,
// сам переініціалізує його після збою і кидає IOException, коли зв'язок втрачено.
//
// ЧОМУ ТУТ М'ЮТЕКС:
// Запит — це дві посилки поспіль: «AT SH <заголовок>» і сама команда. Якщо між ними
// вклиниться інший запит зі своїм заголовком, команда піде не тому блоку, а відповіді
// двох запитів злипнуться в один потік. Саме це й сталося, коли цикл опитування та
// екран «Експерименти» пішли в адаптер одночасно. Тому пара «заголовок + команда»
// виконується неподільно, і ініціалізація теж.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ElmCANBridge(private val adapter: ElmAdapter) {

    @Volatile
    private var isInitialized = false

    /** Один запит до адаптера за раз: див. пояснення в шапці файлу. */
    private val busMutex = Mutex()

    /**
     * Виставляє CAN-заголовок і надсилає команду.
     * Кидає IOException, якщо адаптер не ініціалізувався або зв'язок обірвався.
     */
    suspend fun sendCANCommand(header: String, command: String): String = busMutex.withLock {
        if (!isInitialized) initUnlocked()

        try {
            val headerResponse = adapter.sendCommand("AT SH $header")
            if (headerResponse.contains("?") || headerResponse.uppercase().contains("ERROR")) {
                isInitialized = false
                throw IOException("Адаптер відхилив заголовок $header")
            }
            delay(INTER_COMMAND_DELAY_MS)

            val response = adapter.sendCommand(command)
            if (LINK_LOST_MARKERS.any { response.uppercase().contains(it) }) {
                isInitialized = false
                throw IOException("Втрачено зв'язок із шиною: $response")
            }
            return@withLock response
        } catch (e: IOException) {
            isInitialized = false
            throw e
        }
    }

    /** Повна послідовність налаштування ELM327. Раніше вона дублювалася в ConnectionManager. */
    suspend fun initAdapter() = busMutex.withLock { initUnlocked() }

    /** Те саме, але вже під блокуванням: викликається зсередини sendCANCommand. */
    private suspend fun initUnlocked() {
        isInitialized = false

        adapter.sendCommand("AT Z")           // повне скидання
        delay(RESET_DELAY_MS)
        adapter.sendCommand("AT E0")          // вимикаємо ехо
        delay(SHORT_DELAY_MS)
        adapter.sendCommand("AT L0")          // вимикаємо Line Feed
        delay(SHORT_DELAY_MS)
        adapter.sendCommand("AT CAF1")        // CAN Auto Formatting
        delay(SHORT_DELAY_MS)

        // ISO 15765-4 CAN, 11 bit ID, 500 kbaud
        val protocolResponse = adapter.sendCommand("AT SP 6")
        delay(PROTOCOL_DELAY_MS)

        if (protocolResponse.uppercase().contains("ERROR")) {
            throw IOException("Адаптер не прийняв протокол ISO 15765-4: $protocolResponse")
        }
        isInitialized = true
    }

    /**
     * Вікно режиму монітора: збирає сирі рядки широкомовних кадрів протягом [windowMs].
     *
     * Виконується під тим самим блокуванням, що й звичайні запити: монітор і
     * запит-відповідь на одному сокеті несумісні.
     *
     * Вихід із монітора виконується ЗАВЖДИ, навіть при помилці. Якщо не надіслати
     * пробіл і не добитися промпта через AT AR, адаптер лишається в моніторі — і всі
     * подальші запити 22 xx перестають працювати до перепідключення.
     */
    suspend fun monitorBroadcast(windowMs: Long): List<String> = busMutex.withLock {
        if (!isInitialized) initUnlocked()

        val lines = mutableListOf<String>()
        try {
            adapter.flushInput()
            adapter.writeRaw("AT MA\r")

            // Вікно міряється справжнім годинником, а не сумою таймаутів: рядок
            // приходить швидше за таймаут, і сума обрізала б вікно надто рано.
            val deadline = System.currentTimeMillis() + windowMs
            var silentReads = 0
            while (System.currentTimeMillis() < deadline && silentReads < MONITOR_SILENT_READS) {
                val line = adapter.readLine(MONITOR_LINE_TIMEOUT_MS)
                if (line.isNullOrBlank()) {
                    silentReads++
                } else {
                    silentReads = 0
                    lines += line
                }
            }
        } finally {
            leaveMonitor()
        }
        return@withLock lines
    }

    /**
     * Пробіл зупиняє AT MA, далі треба дочекатися промпта. Без цього адаптер
     * лишається в моніторі назавжди.
     */
    private suspend fun leaveMonitor() {
        runCatching { adapter.writeRaw(" ") }
        runCatching { adapter.flushInput() }

        for (attempt in 1..MONITOR_EXIT_ATTEMPTS) {
            val answer = runCatching { adapter.sendCommand("AT AR") }.getOrNull()
            if (!answer.isNullOrBlank()) break
        }

        // Знімаємо можливий фільтр, щоб він не вплинув на наступні запити.
        runCatching { adapter.sendCommand("AT CRA") }
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

        /** Скільки чекати на один рядок у моніторі: кадри йдуть щільно. */
        const val MONITOR_LINE_TIMEOUT_MS = 60L

        /** Скільки разів добиватися промпта після виходу з монітора. */
        const val MONITOR_EXIT_ATTEMPTS = 5

        /**
         * Скільки порожніх читань підряд вважати «шина молчить».
         * При вимкненому зажиганні широкомовних кадрів немає, і чекати все вікно немає сенсу.
         */
        const val MONITOR_SILENT_READS = 5
    }
}
