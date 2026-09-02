// ====================================================================================
// МІСТ ДО CAN-ШИНИ ЧЕРЕЗ ELM327 (ElmCANBridge)
//
// Єдина точка, через яку в додатку йдуть CAN-запити: тримає стан ініціалізації адаптера,
// сам переініціалізує його після збою і кидає IOException, коли зв'язок втрачено.
//
// ЧОМУ ТУТ ЖИВЕ ВІДНОВЛЕННЯ:
// Адаптер має власний стан, який переживає застосунок. Найгірший його різновид —
// «лишився в режимі монітора»: він безперервно віддає кадри й не друкує промпт,
// тож ані запити не проходять, ані перезапуск застосунку не допомагає. Тому кожна
// ініціалізація починається з зупинки монітора й чекання тишi, а вихід із вікна
// перевіряє, що потік справді стих, — і якщо ні, змушує повне скидання.
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

        // Адаптер міг лишитися в режимі монітора: застосунок закрили, телефон
        // заснув або зв'язок обірвався рівно посеред вікна. Сам адаптер про це не
        // знає й далі сипле кадри — без промпта, без кінця. «AT Z» у такому потоці
        // просто тонув, і саме тому перезапуск застосунку нічого не лікував: завис
        // не застосунок, а адаптер, і новий процес упирався в те саме.
        //
        // Тому спершу зупиняємо монітор одним символом і чекаємо тишу, і лише
        // потім говоримо. Друга спроба — бо перший символ міг піти в той момент,
        // коли адаптер віддавав кадр, і загубитися.
        quietDown()
        try {
            adapter.sendCommand("AT Z")       // повне скидання
        } catch (e: IOException) {
            quietDown()
            adapter.sendCommand("AT Z")
        }
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
     * ЧОМУ ФІЛЬТР ОБОВ'ЯЗКОВИЙ. Без «AT CRA <id>» адаптер за півсекунди захлинається
     * усім трафіком шини й віддає «BUFFER FULL», а до того — кадри навперемішки,
     * обрізані посередині. Тому за одне вікно слухаємо рівно один [filterId].
     *
     * ЧОМУ H1 І CAF0. З типовими для запит-відповіді налаштуваннями монітор віддає
     * кадри БЕЗ CAN ID (H0) і дописує до них службові індекси «0:», «1:» ISO-TP
     * (CAF1). Саме так і виглядали перші зняті вікна: суцільні дані без ID.
     *
     * Виконується під тим самим блокуванням, що й звичайні запити: монітор і
     * запит-відповідь на одному сокеті несумісні.
     *
     * Вихід із монітора виконується ЗАВЖДИ, навіть при помилці: інакше адаптер
     * лишається в моніторі й усі подальші запити 21/22 не працюють до перепідключення.
     */
    suspend fun monitorBroadcast(windowMs: Long, filterId: String): List<String> = busMutex.withLock {
        if (!isInitialized) initUnlocked()

        val buffer = StringBuilder()
        try {
            adapter.flushInput()
            adapter.sendCommand("AT CRA $filterId")
            adapter.sendCommand("AT H1")
            adapter.sendCommand("AT CAF0")
            adapter.writeRaw("AT MA\r")

            // Вікно міряється справжнім годинником, а не сумою таймаутів.
            val deadline = System.currentTimeMillis() + windowMs
            var silentPolls = 0
            while (System.currentTimeMillis() < deadline && silentPolls < MONITOR_SILENT_POLLS) {
                val chunk = adapter.readAvailable()
                if (chunk.isEmpty()) {
                    silentPolls++
                    delay(MONITOR_POLL_MS)
                } else {
                    silentPolls = 0
                    buffer.append(chunk)
                    // Після переповнення адаптер сам зупиняє монітор: далі буде сміття.
                    if (buffer.contains(BUFFER_FULL)) break
                }
            }
        } finally {
            leaveMonitor()
        }
        return@withLock splitCompleteLines(buffer.toString())
    }

    /**
     * Ріже буфер на рядки й ВІДКИДАЄ незавершений хвіст: якщо вікно закінчилося
     * посеред кадру, половина кадру виглядала б як повний і дала б зсунуті байти.
     */
    private fun splitCompleteLines(buffer: String): List<String> {
        val lines = buffer.split('\r', '\n')
        val complete = if (buffer.endsWith("\r") || buffer.endsWith("\n")) lines else lines.dropLast(1)
        return complete.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Пробіл зупиняє AT MA, далі треба дочекатися промпта, зняти фільтр і повернути
     * H0/CAF1 — інакше звичайні запити почнуть приходити з чужим форматом.
     */
    private suspend fun leaveMonitor() {
        val quiet = quietDown()

        var promptSeen = false
        for (attempt in 1..MONITOR_EXIT_ATTEMPTS) {
            val answer = runCatching { adapter.sendCommand("AT AR") }.getOrNull()
            if (!answer.isNullOrBlank()) {
                promptSeen = true
                break
            }
        }

        // Якщо відкотити налаштування не вдалося, адаптер лишився з H1/CAF0 — тоді
        // звичайні відповіді прийдуть із заголовками, і декодер побачить сміття.
        // Дешевше змусити повну переініціалізацію, ніж потім шукати причину.
        val restored = listOf("AT CRA", "AT H0", "AT CAF1").all { command ->
            runCatching { adapter.sendCommand(command) }.isSuccess
        }

        // Потік, який так і не стих, означає, що монітор досі працює: пробіл не
        // дійшов. Далі всі запити 21/22 отримували б кадри замість відповідей, а
        // читання «до промпта» впиралося б у свій бюджет на кожній команді. Тому
        // наступний запит мусить пройти повне скидання.
        if (!quiet || !promptSeen || !restored) isInitialized = false
    }

    /**
     * Зупиняє монітор і чекає, поки потік стихне.
     *
     * Будь-який символ зупиняє «AT MA» — надсилаємо пробіл. Далі важливий саме
     * результат сушіння: якщо потік не стих, адаптер до запитів не готовий.
     */
    private suspend fun quietDown(): Boolean {
        runCatching { adapter.writeRaw(STOP_MONITOR) }
        return runCatching { adapter.flushInput() }.getOrDefault(false)
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

        /** Пауза між читаннями буфера, коли адаптер мовчить. */
        const val MONITOR_POLL_MS = 20L

        /**
         * Скільки разів добиватися промпта після виходу з монітора.
         *
         * Три, а не п'ять: кожна спроба тепер має свій бюджет часу, і п'ять
         * невдалих затягували б одне опитування довше, ніж вартий той пробіг.
         */
        const val MONITOR_EXIT_ATTEMPTS = 3

        /** Будь-який символ зупиняє «AT MA»; пробіл найбезпечніший. */
        const val STOP_MONITOR = " "

        /**
         * Скільки порожніх читань підряд вважати «шина мовчить».
         * При вимкненому запалюванні широкомовних кадрів немає, і чекати все вікно немає сенсу.
         */
        const val MONITOR_SILENT_POLLS = 15

        /** Адаптер сам зупиняє монітор, коли не встигає віддавати кадри. */
        const val BUFFER_FULL = "BUFFER FULL"
    }
}
