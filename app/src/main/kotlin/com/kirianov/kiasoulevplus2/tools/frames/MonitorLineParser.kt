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
    private val WHITESPACE = Regex("\\s+")

    fun parse(raw: String): CanBroadcastFrame? {
        val tokens = raw.replace(Regex("[\\t\\n\\u000B\\u000C\\r]"), "")
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val normalized = unpadSplitId(tokens)
        val id = normalized.first().uppercase()
        if (!ID_PATTERN.matches(id)) return null

        val bytes = normalized.drop(1)
            .filter { BYTE_PATTERN.matches(it) }
            .map { it.toInt(16) }

        return CanBroadcastFrame(id, bytes)
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
