// ====================================================================================
// РОЗБІР РЯДКІВ РЕЖИМУ МОНІТОРА (MonitorLineParser)
//
// У режимі AT MA адаптер сипле рядками «<ID> <байти...>» без промпта.
//
// ЧОМУ ПОТРІБЕН ОЧІКУВАНИЙ ID:
// Вікно монітора завжди знімається з фільтром «AT CRA <id>», тобто наперед відомо,
// чий це кадр. Це рятує від двох речей одразу:
//   1) клони ELM327 інколи віддають кадр без ID або розбивають ID на два байти
//      з паддінгом («00 00 06 53 ...» замість «653 ...»);
//   2) вгадування «схоже на ID» помилялося: рядок даних «00 00 01 FF FD 07 40 44»
//      перетворювався на неіснуючий кадр 1FF.
// Тепер безголовий рядок приписується саме тому ID, який замовляли, а рядок із
// чужим ID відкидається.
//
// Чисті функції без стану — спільна бібліотека, як і решта tools/frames.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame

object MonitorLineParser {

    private val ID_PATTERN = Regex("^[0-9A-F]{3}$")
    private val BYTE_PATTERN = Regex("^[0-9A-Fa-f]{2}$")
    private val HEX_PATTERN = Regex("^[0-9A-Fa-f]+$")
    private val WHITESPACE = Regex("\\s+")

    /** Максимум байтів даних у класичному CAN-кадрі. */
    private const val MAX_PAYLOAD = 8

    /**
     * @param expectedId ID, на який стояв фільтр вікна. Рядку без ID приписується він.
     */
    fun parse(raw: String, expectedId: String? = null): CanBroadcastFrame? {
        val tokens = splitGlued(
            raw.trim().split(WHITESPACE).filter { it.isNotEmpty() },
        )
        if (tokens.isEmpty()) return null

        val target = expectedId?.uppercase()

        withLeadingId(tokens)?.let { return it }
        unpaddedId(tokens, target)?.let { return it }
        return headerless(tokens, target)
    }

    /** Звичайний рядок: «4F0 00 5A ...». */
    private fun withLeadingId(tokens: List<String>): CanBroadcastFrame? {
        val id = tokens.first().uppercase()
        if (!ID_PATTERN.matches(id)) return null
        return frame(id, tokens.drop(1))
    }

    /** Клон розбив ID на два байти з паддінгом: «00 00 06 53 ...» -> «653 ...». */
    private fun unpaddedId(tokens: List<String>, target: String?): CanBroadcastFrame? {
        if (target == null || tokens.size <= 4) return null
        if (tokens[0] != "00" || tokens[1] != "00") return null
        if (!BYTE_PATTERN.matches(tokens[2]) || !BYTE_PATTERN.matches(tokens[3])) return null

        val id = (tokens[2] + tokens[3]).uppercase().removePrefix("0")
        if (id != target) return null
        return frame(id, tokens.drop(4))
    }

    /** ID не показано взагалі: кадр належить тому, на кого стояв фільтр. */
    private fun headerless(tokens: List<String>, target: String?): CanBroadcastFrame? {
        if (target == null || tokens.size > MAX_PAYLOAD) return null
        return frame(target, tokens)
    }

    /**
     * Зіпсований байт НЕ відкидається поодинці: без нього всі наступні зсуви поїхали б
     * на один, і замість «немає даних» вийшов би тихо неправильний пробіг.
     * Тому рядок із будь-яким нешістнадцятковим словом відкидається цілком —
     * так само гинуть «<DATA ERROR» і «BUFFER FULL».
     */
    private fun frame(id: String, payload: List<String>): CanBroadcastFrame? {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD) return null
        if (payload.any { !BYTE_PATTERN.matches(it) }) return null
        return CanBroadcastFrame(id, payload.map { it.toInt(16) })
    }

    /**
     * «4F0005A000000B3C11C» -> «4F0 00 5A ...».
     * Якщо в адаптері вимкнені пробіли (AT S0), кадр приходить одним словом.
     */
    private fun splitGlued(tokens: List<String>): List<String> {
        if (tokens.size != 1) return tokens

        val glued = tokens.first()
        val looksGlued = glued.length >= 5 && (glued.length - 3) % 2 == 0 &&
            HEX_PATTERN.matches(glued)
        if (!looksGlued) return tokens

        return listOf(glued.take(3)) + glued.drop(3).chunked(2)
    }
}
