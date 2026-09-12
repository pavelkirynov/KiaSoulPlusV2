// ====================================================================================
// ПОТІК БАЙТІВ ДО ELM327 (ElmStream)
//
// Тут і тільки тут живе робота з байтами: «написати команду й дочитати до промпта»,
// «забрати те, що вже прийшло», «висушити буфер». Про Bluetooth цей файл не знає
// нічого — лише два потоки, — а значить, його поведінку можна перевірити тестами
// без адаптера й без авто.
//
// ГОЛОВНЕ ПРАВИЛО ФАЙЛУ: у кожного циклу є ЧАСОВИЙ бюджет і крапка переривання.
//
// Саме через їхню відсутність застосунок і завис. Адаптер, який лишився в режимі
// монітора, сипле кадри безперервно й ніколи не друкує промпт «>». Обидва старі
// цикли на цьому ламалися назавжди:
//
//  - «сушити буфер, поки в ньому є байти»: байти не закінчувалися ніколи, а
//    всередині циклу не було ні delay, ні перевірки часу — корутину не було чим
//    навіть скасувати, вона крутила ядро до кінця життя процесу;
//  - «читати до промпта, поки не набереться 25 тихих читань»: тиші не було ніколи,
//    бо дані йшли щомиті, тож тихі читання й не набиралися.
//
// Зовні це виглядало рівно так, як його й описали: «показує з'єднання, але дані не
// змінюються». Цикл опитування стояв в одному з цих циклів, стан «Підключено»
// ніхто не знімав, а перезапуск не допомагав — бо завис не застосунок, а адаптер,
// і новий процес одразу впирався в той самий безперервний потік.
//
// Тому тут немає жодного «поки прийдуть дані»: є «поки не вийшов час».
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.bluetooth

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay

class ElmStream(
    private val input: InputStream,
    private val output: OutputStream,
    /** Скільки всього чекати на відповідь із промптом, перш ніж визнати збій. */
    private val responseBudgetMs: Long = RESPONSE_BUDGET_MS,
    /** Скільки всього сушити буфер, перш ніж визнати потік нескінченним. */
    private val drainBudgetMs: Long = DRAIN_BUDGET_MS,
) {

    /**
     * Надсилає команду й читає відповідь до промпта '>'.
     *
     * Кидає IOException, якщо промпта немає ні за бюджетом часу, ні за тишею:
     * «відповіді немає» і «відповідь без кінця» однаково означають, що адаптером
     * далі користуватися не можна, і мовчки чекати тут не можна нічого.
     */
    suspend fun send(command: String): String {
        // Хвіст попередньої відповіді, щоб не приклеївся до нової. Тут саме дешеве
        // «зняти те, що вже лежить», а не повне сушіння: у гарячому циклі опитування
        // це виконується двічі на кожен такт.
        skipAvailable()

        val payload = if (command.endsWith("\r")) command else "$command\r"
        output.write(payload.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        return readUntilPrompt(command)
    }

    /** Пише байти як є, без очікування відповіді. Потрібно для «AT MA» і пробілу. */
    suspend fun writeRaw(text: String) {
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /**
     * Віддає все, що вже прийшло, не чекаючи ні рядка, ні промпта.
     *
     * У моніторі промпта немає, а читання «до \r» з таймаутом повертало обрізану
     * половину кадру як повний рядок — байти зсувалися й пробіг виходив сміттям.
     */
    fun readAvailable(): String {
        val available = input.available()
        if (available <= 0) return ""

        val buffer = ByteArray(minOf(available, READ_BUFFER_SIZE))
        val bytesRead = input.read(buffer)
        return if (bytesRead <= 0) "" else String(buffer, 0, bytesRead, Charsets.ISO_8859_1)
    }

    /**
     * Сушить буфер, поки потік не стихне на [QUIET_MS] підряд.
     *
     * Одна тиха перевірка нічого не доводить: між кадрами буфер буває порожній
     * якраз у момент погляду. Тиша має протриматися.
     *
     * @return true, якщо потік стих; false — якщо бюджет вичерпано, тобто адаптер
     * сипле безперервно. Різниця важлива: у другому випадку в якому він режимі —
     * невідомо, і його треба скидати повністю, а не вважати готовим до запитів.
     */
    suspend fun drain(): Boolean {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val deadline = System.currentTimeMillis() + drainBudgetMs
        var quietSince: Long? = null

        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                if (input.read(buffer) < 0) return true // потік закрився: сушити нічого
                quietSince = null
                // Крапка переривання. Без неї цикл не скасувати — і саме тут він
                // і зависав намертво, поки адаптер лишався в моніторі.
                delay(BUSY_POLL_MS)
                continue
            }

            val now = System.currentTimeMillis()
            val since = quietSince
            if (since == null) {
                quietSince = now
            } else if (now - since >= QUIET_MS) {
                return true
            }
            delay(QUIET_POLL_MS)
        }
        return false
    }

    /**
     * Знімає те, що вже лежить у буфері, і не більше: обмежене число читань,
     * щоб безперервний потік не зробив із цього нескінченний цикл.
     */
    private fun skipAvailable() {
        var reads = 0
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (input.available() > 0 && reads++ < MAX_SKIP_READS) {
            if (input.read(buffer) < 0) return
        }
    }

    private suspend fun readUntilPrompt(command: String): String {
        val response = StringBuilder()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val deadline = System.currentTimeMillis() + responseBudgetMs
        var idlePolls = 0

        while (idlePolls < MAX_IDLE_POLLS) {
            if (System.currentTimeMillis() >= deadline) {
                // Потік не мовчить, але промпта в ньому немає. Майже завжди це
                // живий «AT MA», який не зупинився: чекати далі й означало зависнути.
                throw IOException("Промпт на «$command» не прийшов за $responseBudgetMs мс")
            }

            delay(POLL_INTERVAL_MS)
            if (input.available() <= 0) {
                idlePolls++
                continue
            }

            val bytesRead = input.read(buffer)
            if (bytesRead < 0) throw IOException("З'єднання розірвано під час читання")

            response.append(String(buffer, 0, bytesRead, Charsets.ISO_8859_1))
            if (response.contains(PROMPT)) return response.toString()
            if (response.length > MAX_RESPONSE_CHARS) {
                throw IOException("Відповідь на «$command» без кінця: вже ${response.length} байтів")
            }
            idlePolls = 0
        }

        throw IOException("Адаптер не відповів на «$command» за ${MAX_IDLE_POLLS * POLL_INTERVAL_MS} мс")
    }

    // internal, а не private: бюджети стережуть тести.
    internal companion object {
        const val PROMPT = ">"
        const val READ_BUFFER_SIZE = 1024

        /** Крок перевірки буфера під час читання відповіді. */
        const val POLL_INTERVAL_MS = 100L

        /** Скільки тихих кроків підряд вважати «відповіді не буде». */
        const val MAX_IDLE_POLLS = 25

        /**
         * Скільки всього чекати на промпт. Трохи більше за тишу вище: тиша ловить
         * мертвий адаптер, а цей бюджет — балакучий, у якого промпта не буде взагалі.
         */
        const val RESPONSE_BUDGET_MS = 3_000L

        /** Довша відповідь без промпта — це не відповідь, а потік кадрів. */
        const val MAX_RESPONSE_CHARS = 8 * 1024

        /** Скільки всього сушити буфер. */
        const val DRAIN_BUDGET_MS = 1_500L

        /** Скільки тиші поспіль вважати справжньою тишею. */
        const val QUIET_MS = 60L

        /** Крок перевірки, поки тихо. */
        const val QUIET_POLL_MS = 20L

        /** Крок між читаннями, поки дані сиплються: потрібен як крапка переривання. */
        const val BUSY_POLL_MS = 1L

        /** Скільки читань дозволено дешевому «зняти хвіст». */
        const val MAX_SKIP_READS = 8
    }
}
