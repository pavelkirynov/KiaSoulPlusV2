// ====================================================================================
// ДЕКОДЕР НАПРУГ КОМІРОК БАТАРЕЇ (CellDecoder)
//
// ВХІД:  сирі відповіді ELM327 на кадри 21 02, 21 03, 21 04 (по 32 комірки в кожному).
// ВИХІД: CellData з масивом напруг, min/max/delta та логом для екрана.
//
// АЛГОРИТМ:
// 1. BmsFrameParser чистить відповідь і віддає байти.
// 2. Комірки лежать по 2 байти починаючи з 7-го байта корисної частини кадру.
// 3. Напруга = ((HighByte shl 8) or LowByte) / 50.0.
// 4. Значення поза межами PLAUSIBLE_VOLTAGE_RANGE вважається незчитаним і стає 0.0,
//    щоб не зіпсувати min/max.
//
// Клас чистий: нічого не надсилає і не чіпає UI.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.CellData

class CellDecoder {

    fun decodeResponses(commands: List<String>, responses: List<String>): CellData {
        val allVoltages = mutableListOf<Double>()
        val debugLines = mutableListOf<String>()

        responses.forEachIndexed { index, rawResponse ->
            val cmdName = commands.getOrElse(index) { "кадр ${index + 1}" }
            val bytes = BmsFrameParser.parse(rawResponse)

            val cleanRaw = rawResponse.replace('\r', ' ').replace('\n', ' ').trim()
            debugLines += if (cleanRaw.isEmpty()) {
                "[$cmdName] RAW: [ПОРОЖНЬО / NO DATA]"
            } else {
                "[$cmdName] RAW: ${cleanRaw.take(RAW_LOG_LIMIT)}"
            }

            val frameVoltages = decodeFrame(bytes)
            allVoltages += frameVoltages
            debugLines += "[$cmdName] Байтів: ${bytes.size}, комірок: ${frameVoltages.size}"
        }

        val valid = allVoltages.filter { it > 0.0 }
        val minV = valid.minOrNull() ?: 0.0
        val maxV = valid.maxOrNull() ?: 0.0

        val fullDebug = buildString {
            append(debugLines.joinToString("\n"))
            append("\n--------------------\n")
            append("Всього комірок: ${allVoltages.size} / ${CellData.TOTAL_CELLS}")
        }

        return CellData(
            cellVoltages = allVoltages,
            minVoltage = minV,
            maxVoltage = maxV,
            deltaVoltage = if (valid.isEmpty()) 0.0 else maxV - minV,
            debugInfo = fullDebug,
        )
    }

    private fun decodeFrame(bytes: List<Int>): List<Double> {
        if (bytes.size < FIRST_CELL_INDEX + CELLS_PER_FRAME * BYTES_PER_CELL) return emptyList()

        return (0 until CELLS_PER_FRAME).map { cell ->
            val voltage = BmsFrameParser.unsigned16(bytes, FIRST_CELL_INDEX + cell * BYTES_PER_CELL) /
                VOLTAGE_DIVISOR
            if (voltage in PLAUSIBLE_VOLTAGE_RANGE) voltage else 0.0
        }
    }

    companion object {
        /** Перший байт корисної частини кадру, з якого починаються комірки. */
        const val FIRST_CELL_INDEX = 7
        const val CELLS_PER_FRAME = 32
        const val BYTES_PER_CELL = 2

        /** Формула Kia Soul EV: сире 16-бітне значення ділиться на 50. */
        private const val VOLTAGE_DIVISOR = 50.0

        /** Поза цим діапазоном напруга комірки фізично неможлива. */
        private val PLAUSIBLE_VOLTAGE_RANGE = 1.5..4.5

        private const val RAW_LOG_LIMIT = 45
    }
}
