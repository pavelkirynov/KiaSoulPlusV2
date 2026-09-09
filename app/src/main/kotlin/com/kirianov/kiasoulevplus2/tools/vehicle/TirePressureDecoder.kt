// ====================================================================================
// ДЕКОДЕР ВІДПОВІДІ БЛОКА ТИСКУ (TirePressureDecoder)
//
// Відповідь на `22 C0 0B` від блока 7A0. Система відліку та сама, що й у решти
// наших декодерів запит-відповідь: плоский індекс від початку відповіді, де байти
// 0-2 це `62 C0 0B`.
//
// РОЗКЛАДКА ТУТ — ЗДОГАД, і це сказано прямо. Вона взята з наборів Torque та
// Car Scanner для Hyundai/Kia того ж покоління: чотири байти на колесо, перший
// тиск (0.2 psi на крок), другий температура (мінус п'ятдесят). Ні на цій машині,
// ні на будь-якій іншій ми її не перевіряли.
//
// Через це дві речі зроблені навмисно. По-перше, сира відповідь іде в журнал
// цілком — щоб виправлення коштувало однієї константи, а не ще однієї поїздки.
// По-друге, перевірка правдоподібності тут СТРОГА: тиск легкової шини живе між
// 1.2 і 3.6 бар, і числа поза цим не показуються взагалі. Краще прочерк, ніж
// «0.4 бар» на цілому колесі.
//
// Сам запит («7A0 → 22 C0 0B») лежить у Data.TireCommands: команду однаково має
// бачити і той, хто питає, і той, хто розбирає.
//
// Чистий об'єкт без стану: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.TireData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

object TirePressureDecoder {

    /** Перший байт тиску; далі кожні чотири байти — наступне колесо. */
    private const val FIRST_PRESSURE_INDEX = 3
    private const val BYTES_PER_WHEEL = 4

    /** Температура лежить одразу за тиском свого колеса. */
    private const val TEMP_OFFSET = 1

    private const val TEMP_ZERO = 50.0

    /** Нижче й вище цього це не тиск у шині, а не ті байти. */
    private const val MIN_PLAUSIBLE_BAR = 1.2
    private const val MAX_PLAUSIBLE_BAR = 3.6

    private const val MIN_PLAUSIBLE_TEMP = -40.0
    private const val MAX_PLAUSIBLE_TEMP = 100.0

    fun decode(response: String): TireData {
        val bytes = FrameParser.parse(response)
        val last = FIRST_PRESSURE_INDEX + BYTES_PER_WHEEL * (TireData.WHEELS - 1) + TEMP_OFFSET
        if (bytes.size <= last) return TireData()

        val pressures = (0 until TireData.WHEELS).map { wheel ->
            bytes[FIRST_PRESSURE_INDEX + wheel * BYTES_PER_WHEEL] *
                TireData.PSI_PER_STEP * TireData.BAR_PER_PSI
        }
        val temps = (0 until TireData.WHEELS).map { wheel ->
            bytes[FIRST_PRESSURE_INDEX + wheel * BYTES_PER_WHEEL + TEMP_OFFSET] - TEMP_ZERO
        }

        if (pressures.any { it < MIN_PLAUSIBLE_BAR || it > MAX_PLAUSIBLE_BAR }) return TireData()
        if (temps.any { it < MIN_PLAUSIBLE_TEMP || it > MAX_PLAUSIBLE_TEMP }) return TireData()

        return TireData(known = true, pressuresBar = pressures, tempsC = temps)
    }
}
