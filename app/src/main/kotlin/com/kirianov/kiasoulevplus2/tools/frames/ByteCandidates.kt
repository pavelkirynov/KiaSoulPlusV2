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

    private fun readUnsigned(bytes: List<Int>, index: Int, width: Int): Long =
        (0 until width).fold(0L) { acc, offset -> (acc shl 8) or bytes[index + offset].toLong() }
}
