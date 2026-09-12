// ====================================================================================
// РОЗБІР ВІДПОВІДІ ПРО ПОМИЛКИ (FaultDecoder)
//
// ДВІ МОВИ, ОДНЕ ПИТАННЯ. Блок може відповісти на UDS `19 02` або на KWP `18 02`, і
// відповіді в них влаштовані по-різному:
//
//   UDS: `59 02 <маска> [3 байти коду + 1 байт стану]…`
//   KWP: `58 <скільки кодів> [2 байти коду + 1 байт стану]…`
//
// Різниця не косметична: у UDS третій байт коду каже ТИП відмови (обрив, коротке,
// поза межами), і його прийнято писати через дефіс — P0162-00. У KWP його немає
// зовсім, і код закінчується на четвертому знаку.
//
// КОД ЗАПАКОВАНИЙ У ДВА ПЕРШИХ БАЙТИ, і розпаковка в нього незвична — однакова для
// обох мов. Старші два біти першого байта це літера (P, C, B, U), наступні два —
// перша цифра, а далі йдуть звичайні шістнадцяткові півбайти. Тобто «0x01 0x62» це
// не 0162, а P0162.
//
// ВІДМОВУ ТРЕБА ВІДРІЗНЯТИ ВІД МОВЧАННЯ — і, як показала машина, ще й одну відмову
// від іншої. На запит блок може відповісти негативно, `7F <служба> <причина>`, і
// причини діляться на три різні речі:
//
//   «не вмію»    — 0x11, 0x12, 0x31. Питати цією мовою марно, треба іншою.
//   «не зараз»   — 0x22, 0x7E, 0x7F. Блок є, але в цьому стані не відповідає.
//   «зачекай»    — 0x21, 0x78. Це НЕ відмова: блок просить повторити або дати час.
//
// Третю групу перше опитування живої машини показало обидвома кодами одразу — 0x78
// від датчиків тиску, 0x21 від підсилювача керма, — і обидва тоді потрапили в
// «відмовив, причина 0x…». Насправді там треба було просто спитати ще раз.
//
// Чистий об'єкт без стану: перевіряється тестами на синтетичних відповідях.
// ====================================================================================

package com.kirianov.kiasoulevplus2.car.dtc

import com.kirianov.kiasoulevplus2.Data.Ecu
import com.kirianov.kiasoulevplus2.Data.EcuFaults
import com.kirianov.kiasoulevplus2.Data.Ecus
import com.kirianov.kiasoulevplus2.Data.Fault
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser
import com.kirianov.kiasoulevplus2.tools.frames.NegativeResponse

object FaultDecoder {

    /**
     * Розбирає відповіді блока на всі запити з [Ecus.REQUESTS] і лишає кращу.
     *
     * «Краща» — та, що більше сказала: коди краще за «помилок немає», «помилок
     * немає» краще за відмову, відмова краще за мовчання. Мова, якою блок
     * відповів, людині не цікава; цікаво, що він сказав.
     */
    fun decode(ecu: Ecu, answers: List<String>): EcuFaults {
        val decoded = Ecus.REQUESTS.mapIndexed { index, request ->
            decodeOne(ecu, request, answers.getOrNull(index).orEmpty())
        }
        return decoded.maxByOrNull { rank(it) } ?: EcuFaults(ecu = ecu, note = "не відповів")
    }

    /** Розбір однієї відповіді на один запит. */
    fun decodeOne(ecu: Ecu, request: String, rawResponse: String): EcuFaults {
        val protocol = Protocol.of(request)
        val bytes = FrameParser.parse(rawResponse)
        if (bytes.isEmpty()) {
            return EcuFaults(ecu = ecu, note = "не відповів", answered = false)
        }

        negativeReason(rawResponse, protocol.service)?.let { reason ->
            return EcuFaults(ecu = ecu, note = reason, answered = true)
        }

        val start = payloadStart(bytes, protocol)
            ?: return EcuFaults(ecu = ecu, note = "відповідь не розібрано", answered = true)

        val faults = mutableListOf<Fault>()
        var index = start
        while (index + protocol.bytesPerFault <= bytes.size) {
            val code = codeOf(bytes, index, protocol.hasFailureType)
            // Порожні записи в хвості — звичайне доповнення кадру нулями, а не
            // помилка з кодом P0000. Ловимо їх саме тут, бо далі вони виглядали б
            // як справжні коди.
            if (code != null) {
                faults += Fault(code = code, status = bytes[index + protocol.bytesPerFault - 1])
            }
            index += protocol.bytesPerFault
        }

        return EcuFaults(
            ecu = ecu,
            faults = faults,
            note = if (faults.isEmpty()) "помилок немає" else "",
            answered = true,
        )
    }

    /** Наскільки відповідь змістовна: див. пояснення в [decode]. */
    private fun rank(result: EcuFaults): Int = when {
        result.hasFaults -> 4
        result.note == "помилок немає" -> 3
        result.answered -> 2
        else -> 1
    }

    /**
     * Негативна відповідь: `7F <служба> <причина>`.
     *
     * Причин багато, але людині потрібні три: «не вміє», «не зараз» і «просив
     * зачекати». Решту показуємо номером — вигадувати переклад коду, якого не
     * бачили, гірше, ніж чесно показати число.
     */
    private fun negativeReason(rawResponse: String, service: Int): String? =
        when (val code = NegativeResponse.codeOf(rawResponse, service)) {
            null -> null
            0x11, 0x12, 0x31 -> "не підтримує запит помилок"
            0x22, 0x7E, 0x7F -> "зараз не відповідає на запит"
            // Сюди потрапляє лише те, що пережило повтори: блок просив зачекати,
            // ми зачекали, і він так і не відповів.
            0x21 -> "був зайнятий і не звільнився"
            0x78 -> "готував відповідь і не встиг"
            else -> "відмовив, причина 0x${code.toString(16).uppercase()}"
        }

    /** Де починаються самі помилки: одразу після позитивної відповіді та її шапки. */
    private fun payloadStart(bytes: List<Int>, protocol: Protocol): Int? {
        for (index in 0 until bytes.size) {
            if (bytes[index] != protocol.positive) continue
            val subFunction = protocol.subFunction
            if (subFunction != null && bytes.getOrNull(index + 1) != subFunction) continue
            val start = index + protocol.headerBytes
            if (start <= bytes.size) return start
        }
        return null
    }

    /**
     * Два або три байти в звичний код.
     *
     * Нуль замість коду означає порожнє місце в кадрі, а не помилку — див. вище.
     */
    private fun codeOf(bytes: List<Int>, index: Int, hasFailureType: Boolean): String? {
        val high = bytes[index]
        val mid = bytes[index + 1]
        if (high == 0 && mid == 0) return null

        val letter = LETTERS[(high shr 6) and 0x03]
        val first = (high shr 4) and 0x03
        val rest = ((high and 0x0F) shl 8) or mid
        val code = "$letter$first%03X".format(rest)
        return if (hasFailureType) "$code-%02X".format(bytes[index + 2]) else code
    }

    /**
     * Чим відрізняється розбір однієї мови від іншої.
     *
     * Виведено із самого запиту, а не задано окремо: інакше довелося б тримати
     * список запитів у двох місцях і стежити, щоб вони не розійшлися.
     */
    private data class Protocol(
        val service: Int,
        val positive: Int,
        /** Байт піднастройки одразу після позитивної відповіді; null — його немає. */
        val subFunction: Int?,
        /** Скільки байтів шапки до першого коду, разом із самим байтом відповіді. */
        val headerBytes: Int,
        val bytesPerFault: Int,
        val hasFailureType: Boolean,
    ) {
        companion object {
            fun of(request: String): Protocol =
                if (request.trim().startsWith("18")) KWP else UDS

            /** `59 02 <маска>` і по чотири байти на код. */
            private val UDS = Protocol(
                service = 0x19,
                positive = 0x59,
                subFunction = 0x02,
                headerBytes = 3,
                bytesPerFault = 4,
                hasFailureType = true,
            )

            /** `58 <кількість>` і по три байти на код. */
            private val KWP = Protocol(
                service = 0x18,
                positive = 0x58,
                subFunction = null,
                headerBytes = 2,
                bytesPerFault = 3,
                hasFailureType = false,
            )
        }
    }

    private val LETTERS = charArrayOf('P', 'C', 'B', 'U')
}
