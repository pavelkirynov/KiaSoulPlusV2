// ====================================================================================
// ДЕКОДЕР КАДРУ BMS 21 05 (PackHealthDecoder)
//
// Другий кадр батареї. У першому (21 01) лежить те, що з нею зараз; тут — те, що з
// нею сталося: власна оцінка зносу комірок і справжні межі температур по пакету.
//
// Система відліку та сама, що й у BmsResponseDecoder: плоский індекс від початку
// відповіді, де байти 0-1 це `61 05`. Переклад із таблиці SoulEVSpy той самий —
// `наш = 6 + 7 × (X − 1) + n`, і він уже перевірений на трьох полях кадру 21 01.
//
// ОДНЕ ПОЛЕ ТУТ ПЕРЕВІРЯЄТЬСЯ САМО: display SOC на зміщенні 33. Воно було в наших
// записках про цю машину ЩЕ ДО SoulEVSpy — «7E4 → 21 05, байт 33 × 0.5». Те, що
// їхня таблиця дала рівно те саме зміщення, і є підтвердження, що решта кадру
// розкладена правильно.
//
// Чистий об'єкт без стану: перевіряється тестами на синтетичних кадрах.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.PackHealth
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

object PackHealthDecoder {

    private const val INLET_TEMP_INDEX = 11
    private const val MIN_TEMP_INDEX = 12
    private const val MAX_TEMP_INDEX = 13
    private const val HEATER_1_INDEX = 25
    private const val HEATER_2_INDEX = 26
    private const val MAX_DETERIORATION_INDEX = 27
    private const val MAX_DETERIORATION_CELL_INDEX = 29
    private const val MIN_DETERIORATION_INDEX = 30
    private const val MIN_DETERIORATION_CELL_INDEX = 32
    private const val DISPLAY_SOC_INDEX = 33

    /** Коротший кадр розбирати немає сенсу: найдальше поле — display SOC. */
    private const val MIN_FRAME_SIZE = DISPLAY_SOC_INDEX + 1

    /**
     * Деградація приходить у десятих відсотка й нормально тримається біля сотні.
     * Понад цю межу це не знос, а не ті байти.
     */
    private const val MAX_PLAUSIBLE_DETERIORATION = 200.0

    /** Батарея живе між мінус сорока й плюс сімдесятьма; поза цим — сміття. */
    private const val MIN_PLAUSIBLE_TEMP = -40.0
    private const val MAX_PLAUSIBLE_TEMP = 90.0

    fun decode(bytes: List<Int>): PackHealth {
        if (bytes.size < MIN_FRAME_SIZE) return PackHealth()

        return decoded(bytes).takeIf { plausible(it) } ?: PackHealth()
    }

    /**
     * Ті самі граблі, що й у кадрі 21 01: кадр правильної довжини з самих 0xFF
     * приходить, коли адаптер захлинувся, і числа з нього виглядають правдоподібно
     * саме доти, доки їх не перевірити.
     */
    private fun plausible(health: PackHealth): Boolean =
        health.maxTempC in MIN_PLAUSIBLE_TEMP..MAX_PLAUSIBLE_TEMP &&
            health.minTempC in MIN_PLAUSIBLE_TEMP..MAX_PLAUSIBLE_TEMP &&
            health.maxTempC >= health.minTempC &&
            health.maxDeteriorationPercent <= MAX_PLAUSIBLE_DETERIORATION &&
            health.minDeteriorationPercent <= MAX_PLAUSIBLE_DETERIORATION &&
            health.displaySoc <= MAX_SOC

    private const val MAX_SOC = 100.0

    private fun decoded(bytes: List<Int>): PackHealth = PackHealth(
        known = true,
        maxTempC = FrameParser.signed8(bytes, MAX_TEMP_INDEX).toDouble(),
        minTempC = FrameParser.signed8(bytes, MIN_TEMP_INDEX).toDouble(),
        inletTempC = FrameParser.signed8(bytes, INLET_TEMP_INDEX).toDouble(),
        heater1TempC = FrameParser.signed8(bytes, HEATER_1_INDEX).toDouble(),
        heater2TempC = FrameParser.signed8(bytes, HEATER_2_INDEX).toDouble(),
        // Знос — 16-бітне в десятих відсотка. Найгірша комірка йде першою, і
        // «максимальна деградація» в таблиці Kia означає саме найгіршу.
        maxDeteriorationPercent = FrameParser.unsigned16(bytes, MAX_DETERIORATION_INDEX) / 10.0,
        maxDeteriorationCell = bytes[MAX_DETERIORATION_CELL_INDEX],
        minDeteriorationPercent = FrameParser.unsigned16(bytes, MIN_DETERIORATION_INDEX) / 10.0,
        minDeteriorationCell = bytes[MIN_DETERIORATION_CELL_INDEX],
        displaySoc = bytes[DISPLAY_SOC_INDEX] / 2.0,
    )
}
