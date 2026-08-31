// ====================================================================================
// ДЕКОДЕР НАПРУГ КОМІРОК БАТАРЕЇ (CellDecoder)
//
// ВХІД:  сирі відповіді ELM327 на кадри 21 02, 21 03, 21 04 (по 32 комірки в кожному).
// ВИХІД: CellData з масивом напруг, min/max/delta та логом для екрана.
//
// ФОРМАТ ВІДПОВІДІ:
// Кадр 21 02 — багаторамкова ISO-TP відповідь довжиною 0x27 = 39 байт корисних даних.
// У 39 байт 32 двобайтові комірки просто не помістяться: у Kia/Hyundai напруга комірки
// займає ОДИН байт, а вольти рахуються як байт * 0.02 (тобто байт / 50).
//
// АЛГОРИТМ:
// 1. FrameParser чистить відповідь і віддає байти.
// 2. Комірки читаються по одному байту починаючи з FIRST_CELL_INDEX.
// 3. Значення поза PLAUSIBLE_VOLTAGE_RANGE вважається незчитаним і стає 0.0,
//    щоб не зіпсувати min/max.
// 4. Якщо кадр коротший за очікуваний — декодується стільки комірок, скільки є,
//    а не нічого: часткові дані корисніші за порожній екран, і лог показує різницю.
//
// Клас чистий: нічого не надсилає і не чіпає UI.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

class CellDecoder {

    fun decodeResponses(commands: List<String>, responses: List<String>): CellData {
        val allVoltages = mutableListOf<Double>()
        val debugLines = mutableListOf<String>()

        responses.forEachIndexed { index, rawResponse ->
            val cmdName = commands.getOrElse(index) { "кадр ${index + 1}" }
            val bytes = FrameParser.parse(rawResponse)
            val frameVoltages = decodeFrame(bytes)
            allVoltages += frameVoltages

            debugLines += if (bytes.isEmpty()) {
                val cleanRaw = rawResponse.replace('\r', ' ').replace('\n', ' ').trim()
                "[$cmdName] немає даних: ${cleanRaw.ifEmpty { "[ПОРОЖНЬО]" }.take(RAW_LOG_LIMIT)}"
            } else {
                // Префікс кадру друкується, щоб було видно зсув, якщо комірки поїдуть на байт.
                val head = bytes.take(HEAD_PREVIEW_BYTES).joinToString(" ") { "%02X".format(it) }
                val unread = frameVoltages.count { it == 0.0 }
                "[$cmdName] байтів: ${bytes.size}, комірок: ${frameVoltages.size}" +
                    (if (unread > 0) ", незчитаних: $unread" else "") +
                    "\n    початок: $head"
            }
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
        if (bytes.size <= FIRST_CELL_INDEX) return emptyList()

        val available = minOf(CELLS_PER_FRAME, bytes.size - FIRST_CELL_INDEX)
        return (0 until available).map { cell ->
            val voltage = bytes[FIRST_CELL_INDEX + cell] * VOLTS_PER_STEP
            if (voltage in PLAUSIBLE_VOLTAGE_RANGE) voltage else 0.0
        }
    }

    companion object {
        /**
         * Індекс першого байта комірок у склеєній відповіді, де [0] = 0x61, [1] = 0x02.
         */
        const val FIRST_CELL_INDEX = 6
        const val CELLS_PER_FRAME = 32

        /** Формула Kia/Hyundai: один байт на комірку, крок 0.02 В (тобто байт / 50). */
        const val VOLTS_PER_STEP = 0.02

        /** Поза цим діапазоном напруга комірки фізично неможлива. */
        private val PLAUSIBLE_VOLTAGE_RANGE = 1.5..4.5

        private const val RAW_LOG_LIMIT = 45
        private const val HEAD_PREVIEW_BYTES = 8
    }
}
