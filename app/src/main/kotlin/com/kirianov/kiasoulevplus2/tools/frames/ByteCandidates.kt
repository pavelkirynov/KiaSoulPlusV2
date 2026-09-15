// ====================================================================================
// ПІДБІР КАНДИДАТІВ (ByteCandidates)
//
// Для кожного зсуву рахує, яким числом були б наступні байти. Так шукана величина
// знаходиться звіркою з приладовою панеллю, а не вгадуванням зсуву.
//
// Чисті функції без стану — спільна бібліотека, як і решта tools/frames.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

import com.kirianov.kiasoulevplus2.Data.ByteCandidate
import com.kirianov.kiasoulevplus2.Data.ValueMatch

object ByteCandidates {

    private val DEFAULT_WIDTHS = listOf(2, 3, 4)

    /**
     * Усі прочитання заданої ширини, що потрапляють у [range].
     * Для пробігу діапазон задають межами правдоподібного одометра — у списку
     * лишається кілька рядків, з яких потрібний впізнається за збігом зі щитком.
     */
    fun find(
        bytes: List<Int>,
        range: LongRange,
        widths: List<Int> = DEFAULT_WIDTHS,
    ): List<ByteCandidate> =
        widths.flatMap { width ->
            (0..bytes.size - width).mapNotNull { index ->
                val value = readUnsigned(bytes, index, width)
                if (value in range) ByteCandidate(index, width, value) else null
            }
        }.sortedWith(compareBy({ it.index }, { it.width }))

    /**
     * Шукає у відповіді відоме число — наприклад, пробіг, зчитаний зі щитка.
     *
     * Перебираються ширина поля, масштаб (у кадрі величина може бути в десятих чи
     * сотих) та порядок байтів. Один точний збіг закриває питання про зсув; кілька
     * збігів звужують пошук до перевірки на іншому пробігу.
     */
    fun findValue(
        bytes: List<Int>,
        target: Long,
        widths: List<Int> = DEFAULT_WIDTHS,
        divisors: List<Int> = DEFAULT_DIVISORS,
    ): List<ValueMatch> {
        if (target <= 0) return emptyList()

        return widths.flatMap { width ->
            (0..bytes.size - width).flatMap { index ->
                listOf(true, false).flatMap { bigEndian ->
                    val raw = read(bytes, index, width, bigEndian)
                    divisors.mapNotNull { divisor ->
                        if (raw == target * divisor) {
                            ValueMatch(index, width, divisor, bigEndian, raw)
                        } else {
                            null
                        }
                    }
                }
            }
        }.distinctBy { listOf(it.index, it.width, it.divisor, it.bigEndian) }
            // Вужче прочитання ймовірніше: ширше зазвичай те саме поле з нулем спереду.
            .sortedWith(compareBy({ it.width }, { it.index }))
    }

    private fun readUnsigned(bytes: List<Int>, index: Int, width: Int): Long =
        read(bytes, index, width, bigEndian = true)

    private fun read(bytes: List<Int>, index: Int, width: Int, bigEndian: Boolean): Long {
        val order = if (bigEndian) 0 until width else (width - 1) downTo 0
        return order.fold(0L) { acc, offset -> (acc shl 8) or bytes[index + offset].toLong() }
    }

    /** Величина в кадрі буває в цілих, десятих або сотих одиницях. */
    private val DEFAULT_DIVISORS = listOf(1, 10, 100)
}
