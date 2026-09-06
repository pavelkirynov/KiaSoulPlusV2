// ====================================================================================
// РОЗБІР ВІДПОВІДІ ПРО ПОМИЛКИ (FaultDecoder)
//
// Блок відповідає на `19 02` так: `59 02 <маска доступних станів>`, а далі підряд
// по чотири байти на кожну помилку — три байти коду і байт стану.
//
// КОД ЗАПАКОВАНИЙ У ДВА ПЕРШИХ БАЙТИ, і розпаковка в нього незвична. Старші два
// біти першого байта — це літера (P, C, B, U), наступні два — перша цифра, а далі
// йдуть звичайні шістнадцяткові півбайти. Тобто «0x01 0x62» це не 0162, а P0162.
// Третій байт — уточнення типу відмови, воно пишеться через дефіс: P0162-00.
//
// ВІДМОВУ ТРЕБА ВІДРІЗНЯТИ ВІД МОВЧАННЯ. На запит блок може відповісти негативно —
// `7F 19 <причина>`, — і це зовсім не те саме, що «помилок немає». Найчастіша
// причина, 0x31, означає «такої послуги я не знаю»; у машині 2015 року це
// нормально для половини блоків.
//
// Чистий об'єкт без стану: перевіряється тестами на синтетичних відповідях.
// ====================================================================================

package com.kirianov.kiasoulevplus2.car.dtc

import com.kirianov.kiasoulevplus2.Data.Ecu
import com.kirianov.kiasoulevplus2.Data.EcuFaults
import com.kirianov.kiasoulevplus2.Data.Fault
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

object FaultDecoder {

    fun decode(ecu: Ecu, rawResponse: String): EcuFaults {
        val bytes = FrameParser.parse(rawResponse)
        if (bytes.isEmpty()) {
            return EcuFaults(ecu = ecu, note = "не відповів", answered = false)
        }

        negativeReason(bytes)?.let { reason ->
            return EcuFaults(ecu = ecu, note = reason, answered = true)
        }

        val start = payloadStart(bytes)
            ?: return EcuFaults(ecu = ecu, note = "відповідь не розібрано", answered = true)

        val faults = mutableListOf<Fault>()
        var index = start
        while (index + BYTES_PER_FAULT <= bytes.size) {
            val code = codeOf(bytes, index)
            // Порожні четвірки в хвості — звичайне доповнення кадру нулями, а не
            // помилка з кодом P0000. Ловимо їх саме тут, бо далі вони виглядали б
            // як справжні коди.
            if (code != null) faults += Fault(code = code, status = bytes[index + 3])
            index += BYTES_PER_FAULT
        }

        return EcuFaults(
            ecu = ecu,
            faults = faults,
            note = if (faults.isEmpty()) "помилок немає" else "",
            answered = true,
        )
    }

    /**
     * Негативна відповідь: `7F 19 <причина>`.
     *
     * Причин багато, але людині потрібні дві: «не вміє» і «не зараз». Решту
     * показуємо номером — вигадувати переклад коду, якого не бачили, гірше, ніж
     * чесно показати число.
     */
    private fun negativeReason(bytes: List<Int>): String? {
        val at = bytes.indexOf(NEGATIVE)
        if (at < 0 || at + 2 >= bytes.size || bytes[at + 1] != SERVICE) return null
        return when (val code = bytes[at + 2]) {
            0x11, 0x12, 0x31 -> "не підтримує запит помилок"
            0x22, 0x7E, 0x7F -> "зараз не відповідає на запит"
            else -> "відмовив, причина 0x${code.toString(16).uppercase()}"
        }
    }

    /** Де починаються самі помилки: одразу після `59 02` і байта маски. */
    private fun payloadStart(bytes: List<Int>): Int? {
        for (index in 0 until bytes.size - 2) {
            if (bytes[index] == POSITIVE && bytes[index + 1] == SUBFUNCTION) return index + 3
        }
        return null
    }

    /**
     * Три байти в звичний код.
     *
     * Нуль замість коду означає порожнє місце в кадрі, а не помилку — див. вище.
     */
    private fun codeOf(bytes: List<Int>, index: Int): String? {
        val high = bytes[index]
        val mid = bytes[index + 1]
        val detail = bytes[index + 2]
        if (high == 0 && mid == 0 && detail == 0) return null

        val letter = LETTERS[(high shr 6) and 0x03]
        val first = (high shr 4) and 0x03
        val rest = ((high and 0x0F) shl 8) or mid
        return "$letter$first%03X-%02X".format(rest, detail)
    }

    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')

    private const val SERVICE = 0x19
    private const val POSITIVE = 0x59
    private const val SUBFUNCTION = 0x02
    private const val NEGATIVE = 0x7F
    private const val BYTES_PER_FAULT = 4
}
