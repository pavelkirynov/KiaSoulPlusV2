// ====================================================================================
// МІНІМАЛЬНИЙ JSON (MiniJson)
//
// У проєкті немає ані kotlinx.serialization, ані Gson, а org.json з Android SDK у
// звичайних unit-тестах кидає «not mocked». Тому розбір лежить тут: рівно стільки
// JSON, скільки треба журналу навчання — плаский об'єкт, числа, рядки, булеві
// значення і масиви чисел. Вкладених об'єктів немає й не потрібно.
//
// Чисті функції без стану: жодного звернення до Android.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.json

object MiniJson {

    /**
     * Один рядок журналу. Порядок ключів зберігається — так рядки читаються очима,
     * а не лише машиною.
     */
    fun encode(values: Map<String, Any?>): String =
        values.entries.joinToString(",", "{", "}") { (key, value) ->
            "${quote(key)}:${encodeValue(value)}"
        }

    /**
     * Повертає порожню мапу на будь-якому пошкодженому рядку, а не кидає виняток:
     * журнал дописується під час руху, тож обірваний останній рядок — звичайна річ,
     * і через нього не має падати навчання на решті історії.
     */
    fun decode(line: String): Map<String, Any?> =
        try {
            Parser(line).parseObject()
        } catch (_: IllegalArgumentException) {
            emptyMap()
        } catch (_: IndexOutOfBoundsException) {
            emptyMap()
        } catch (_: NumberFormatException) {
            emptyMap()
        }

    private fun encodeValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Long -> value.toString()
        is Double -> encodeDouble(value)
        is String -> quote(value)
        is DoubleArray -> value.joinToString(",", "[", "]") { encodeDouble(it) }
        is List<*> -> value.joinToString(",", "[", "]") { encodeValue(it) }
        else -> quote(value.toString())
    }

    /**
     * NaN і нескінченність у JSON не існують: пишемо null, щоб зіпсоване число не
     * зробило нечитабельним увесь рядок.
     */
    private fun encodeDouble(value: Double): String =
        if (value.isFinite()) value.toString() else "null"

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        text.forEach { char ->
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < ' ') out.append("\\u%04x".format(char.code)) else out.append(char)
            }
        }
        return out.append('"').toString()
    }

    private class Parser(private val text: String) {
        private var at = 0

        fun parseObject(): Map<String, Any?> {
            expect('{')
            val values = LinkedHashMap<String, Any?>()
            skipSpace()
            if (peek() == '}') {
                at++
                return values
            }
            while (true) {
                skipSpace()
                val key = parseString()
                skipSpace()
                expect(':')
                values[key] = parseValue()
                skipSpace()
                when (val separator = next()) {
                    ',' -> Unit
                    '}' -> return values
                    else -> throw IllegalArgumentException("очікували , або }, а не $separator")
                }
            }
        }

        private fun parseValue(): Any? {
            skipSpace()
            return when (peek()) {
                '"' -> parseString()
                '[' -> parseArray()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val items = mutableListOf<Any?>()
            skipSpace()
            if (peek() == ']') {
                at++
                return items
            }
            while (true) {
                items += parseValue()
                skipSpace()
                when (val separator = next()) {
                    ',' -> Unit
                    ']' -> return items
                    else -> throw IllegalArgumentException("очікували , або ], а не $separator")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val char = next()) {
                    '"' -> return out.toString()
                    '\\' -> out.append(unescape())
                    else -> out.append(char)
                }
            }
        }

        private fun unescape(): Char = when (val escaped = next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> text.substring(at, at + 4).toInt(16).toChar().also { at += 4 }
            else -> throw IllegalArgumentException("невідомий екран \\$escaped")
        }

        private fun parseNumber(): Double {
            val start = at
            while (at < text.length && (text[at].isDigit() || text[at] in "+-.eE")) at++
            return text.substring(start, at).toDoubleOrNull()
                ?: throw IllegalArgumentException("не число: ${text.substring(start, at)}")
        }

        private fun <T> literal(word: String, value: T): T {
            require(text.startsWith(word, at)) { "очікували $word" }
            at += word.length
            return value
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun peek(): Char = text[at]

        private fun next(): Char = text[at++]

        private fun expect(char: Char) {
            skipSpace()
            val actual = next()
            require(actual == char) { "очікували $char, а не $actual" }
        }
    }
}
