// ====================================================================================
// РОЗБІР СИРИХ ВІДПОВІДЕЙ ELM327 (FrameParser)
//
// Єдине місце, де текст від адаптера перетворюється на байти.
//
// Живе в tools/frames, а не в tools/battery: до батареї це не має стосунку, і тим
// самим розбором користуються і декодери, і екран «Експерименти». Це бібліотека
// чистих функцій без стану, а не канал обміну даними, тому нею можуть користуватися
// всі блоки — так само, як tools/format.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

object FrameParser {

    /** Службові відповіді адаптера, які означають «корисних даних немає». */
    private val NO_PAYLOAD_MARKERS = listOf("NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT")

    /** Сміття, яке ELM327 домішує до відповіді і яке треба прибрати перед розбором. */
    private val NOISE_TOKENS = listOf(">", "SEARCHING...", "STOPPED")

    /** Префікс номера кадру ISO-TP на початку рядка, наприклад "0:" або "1:". */
    private val FRAME_INDEX_PREFIX = Regex("^[0-9A-Fa-f]:")

    /**
     * Перетворює багаторамкову відповідь на список байтів.
     * Повертає порожній список, якщо адаптер відповів помилкою або нічим.
     */
    fun parse(rawResponse: String): List<Int> {
        if (rawResponse.isBlank()) return emptyList()

        val upper = rawResponse.uppercase()
        if (NO_PAYLOAD_MARKERS.any { upper.contains(it) }) return emptyList()

        var cleaned = rawResponse
        NOISE_TOKENS.forEach { cleaned = cleaned.replace(it, "") }

        return cleaned
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line -> parseLine(line) }
    }

    private fun parseLine(line: String): List<Int> =
        line.replace(FRAME_INDEX_PREFIX, "")
            .trim()
            .split(' ', '\t')
            .mapNotNull { token ->
                token.trim().takeIf { it.length == 2 }?.toIntOrNull(16)
            }

    /** Два байти як беззнакове 16-бітне число. */
    fun unsigned16(bytes: List<Int>, highIndex: Int): Int =
        (bytes[highIndex] shl 8) or bytes[highIndex + 1]

    /** Два байти як знакове 16-бітне число: струм батареї від'ємний під час розряду. */
    fun signed16(bytes: List<Int>, highIndex: Int): Int {
        val raw = unsigned16(bytes, highIndex)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    /** Три байти як беззнакове число: так щиток віддає пробіг. */
    fun unsigned24(bytes: List<Int>, highIndex: Int): Long =
        (bytes[highIndex].toLong() shl 16) or
            (bytes[highIndex + 1].toLong() shl 8) or
            bytes[highIndex + 2].toLong()

    /** Чотири байти як беззнакове 32-бітне число: так лежать лічильники енергії. */
    fun unsigned32(bytes: List<Int>, highIndex: Int): Long =
        (bytes[highIndex].toLong() shl 24) or
            (bytes[highIndex + 1].toLong() shl 16) or
            (bytes[highIndex + 2].toLong() shl 8) or
            bytes[highIndex + 3].toLong()

    /** Один байт як знакове 8-бітне число: температура буває нижче нуля. */
    fun signed8(bytes: List<Int>, index: Int): Int {
        val raw = bytes[index]
        return if (raw >= 0x80) raw - 0x100 else raw
    }
}
