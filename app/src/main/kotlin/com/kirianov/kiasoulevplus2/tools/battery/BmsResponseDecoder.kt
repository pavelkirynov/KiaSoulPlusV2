// ====================================================================================
// ДЕКОДЕР ОСНОВНОГО КАДРУ BMS 21 01 (BmsResponseDecoder)
//
// Перетворює байти кадру на BmsData. Нічого не надсилає і нічого не зберігає,
// тому кожну формулу можна перевірити тестом на синтетичному кадрі.
//
// Розкладка байтів — та сама таблиця Kia Soul EV, з якої вже взяті перевірені
// SOC (байт 6) та напруга пакета (байти 14-15).
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

object BmsResponseDecoder {

    private const val SOC_INDEX = 6              // SOC * 2
    private const val CURRENT_HIGH_INDEX = 12    // знакове 16-бітне, * 10
    private const val VOLTAGE_HIGH_INDEX = 14    // беззнакове 16-бітне, * 10
    private const val TEMP_MAX_INDEX = 16        // знакове 8-бітне, °C

    // Лічильники за весь час життя батареї. Лежать поспіль, по чотири байти,
    // усі в десятих своєї одиниці:
    //
    //   32  прийнято, Ач        36  віддано, Ач
    //   40  прийнято, кВт·год   44  віддано, кВт·год
    //
    // ЧОМУ ЦЕ ВАЖЛИВО. Спершу як кВт·год читалися саме зсуви 32 і 36, тобто
    // амперу-години. Помилку видно перехресною перевіркою: кВт·год поділити на Ач
    // мусить дати середню напругу пакета. На реальних даних це 366.8 В для заряду
    // і 353.4 В для розряду — обидва в межах робочої напруги Soul EV, і зарядна
    // вище за розрядну, як і має бути. З Ач на місці кВт·год витрата на 100 км
    // виходила завищеною приблизно в 2.7 раза.
    private const val CHARGED_AH_INDEX = 32
    private const val DISCHARGED_AH_INDEX = 36
    private const val CHARGED_KWH_INDEX = 40
    private const val DISCHARGED_KWH_INDEX = 44

    /** Найбільший індекс основних показників: коротший кадр розбирати немає сенсу. */
    private const val MIN_FRAME_SIZE = TEMP_MAX_INDEX + 1

    /**
     * Понад цю межу значення лічильника не може бути фізичним і означає, що ми
     * прочитали не ті байти. Краще показати прочерк, ніж правдоподібне сміття.
     */
    private const val MAX_PLAUSIBLE_LIFETIME_COUNTER = 1_000_000.0

    /**
     * Повертає BmsData з кадру. Якщо кадр коротший за очікуваний — повертає
     * порожній BmsData з displaySoc = NO_DATA, щоб UI показав «--», а не нулі.
     */
    fun decode(bytes: List<Int>): BmsData {
        if (bytes.size < MIN_FRAME_SIZE) return BmsData()

        return BmsData(
            displaySoc = bytes[SOC_INDEX] / 2.0,
            batteryVoltage = FrameParser.unsigned16(bytes, VOLTAGE_HIGH_INDEX) / 10.0,
            batteryCurrent = FrameParser.signed16(bytes, CURRENT_HIGH_INDEX) / 10.0,
            batteryTempC = FrameParser.signed8(bytes, TEMP_MAX_INDEX).toDouble(),
            cumulativeChargedAh = counterAt(bytes, CHARGED_AH_INDEX),
            cumulativeDischargedAh = counterAt(bytes, DISCHARGED_AH_INDEX),
            cumulativeEnergyChargedKwh = counterAt(bytes, CHARGED_KWH_INDEX),
            cumulativeEnergyDischargedKwh = counterAt(bytes, DISCHARGED_KWH_INDEX),
        )
    }

    /**
     * Лічильники читаються окремо від решти й КОЖЕН перевіряє свою довжину:
     * короткий кадр не повинен позбавляти додаток ні заряду з напругою, які лежать
     * на початку, ні тих лічильників, які до кадру все ж увійшли.
     */
    private fun counterAt(bytes: List<Int>, index: Int): Double {
        if (bytes.size < index + 4) return 0.0

        val value = FrameParser.unsigned32(bytes, index) / 10.0
        return if (value <= MAX_PLAUSIBLE_LIFETIME_COUNTER) value else 0.0
    }
}
