// ====================================================================================
// РОЗБІР РЯДКІВ РЕЖИМУ МОНІТОРА (MonitorLineParser)
//
// У режимі AT MA адаптер сипле рядками «<ID> <байти...>» без промпта.
//
// ОБХІД БАГА КЛОНІВ:
// Дешеві ELM327 v2.1 розбивають ID на два байти з паддінгом: замість
// «653 XX XX ...» віддають «00 00 06 53 XX XX ...». Без цього близько половини
// кадрів не розпізнається, і виглядає це як «адаптер не тягне».
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

    fun parse(raw: String): CanBroadcastFrame? {
        val tokens = raw.replace(Regex("[\\t\\n\\u000B\\u000C\\r]"), "")
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val normalized = unpadSplitId(splitGlued(tokens))
        val id = normalized.first().uppercase()
        if (!ID_PATTERN.matches(id)) return null

        // Зіпсований байт НЕ відкидається: без нього всі наступні зсуви поїхали б
        // на один, і замість «немає даних» вийшов би тихо неправильний пробіг.
        val payload = normalized.drop(1)
        if (payload.any { !BYTE_PATTERN.matches(it) }) return null

        return CanBroadcastFrame(id, payload.map { it.toInt(16) })
    }

    /**
     * «4F0005A000000B3C11C» -> «4F0 00 5A ...».
     * Якщо десь у налаштуваннях адаптера пробіли виявилися вимкненими (AT S0),
     * увесь кадр приходить одним словом.
     */
    private fun splitGlued(tokens: List<String>): List<String> {
        if (tokens.size != 1) return tokens

        val glued = tokens.first()
        val looksGlued = glued.length >= 5 && (glued.length - 3) % 2 == 0 &&
            HEX_PATTERN.matches(glued)
        if (!looksGlued) return tokens

        return listOf(glued.take(3)) + glued.drop(3).chunked(2)
    }

    /** «00 00 06 53 ...» -> «653 ...». Решту рядків повертає без змін. */
    private fun unpadSplitId(tokens: List<String>): List<String> {
        val looksPadded = tokens.size > 4 &&
            tokens[0] == "00" &&
            tokens[1] == "00" &&
            tokens[2].length == 2 &&
            tokens[3].length == 2 &&
            tokens[2].startsWith("0")

        return if (looksPadded) {
            listOf(tokens[2].substring(1) + tokens[3]) + tokens.drop(4)
        } else {
            tokens
        }
    }
}
