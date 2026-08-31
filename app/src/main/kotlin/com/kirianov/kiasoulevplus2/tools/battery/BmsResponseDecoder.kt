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

object BmsResponseDecoder {

    private const val SOC_INDEX = 6              // SOC * 2
    private const val CURRENT_HIGH_INDEX = 12    // знакове 16-бітне, * 10
    private const val VOLTAGE_HIGH_INDEX = 14    // беззнакове 16-бітне, * 10
    private const val TEMP_MAX_INDEX = 16        // знакове 8-бітне, °C

    // Лічильники за весь час життя батареї: по чотири байти, десяті кВт·год.
    private const val ENERGY_CHARGED_INDEX = 32
    private const val ENERGY_DISCHARGED_INDEX = 36

    /** Найбільший індекс основних показників: коротший кадр розбирати немає сенсу. */
    private const val MIN_FRAME_SIZE = TEMP_MAX_INDEX + 1

    /** Лічильники лежать далі за основні показники і в короткому кадрі їх просто немає. */
    private const val MIN_FRAME_SIZE_WITH_COUNTERS = ENERGY_DISCHARGED_INDEX + 4

    /**
     * Понад цю межу значення лічильника не може бути фізичним і означає, що ми
     * прочитали не ті байти. Краще показати прочерк, ніж правдоподібне сміття.
     */
    private const val MAX_PLAUSIBLE_LIFETIME_KWH = 1_000_000.0

    /**
     * Повертає BmsData з кадру. Якщо кадр коротший за очікуваний — повертає
     * порожній BmsData з displaySoc = NO_DATA, щоб UI показав «--», а не нулі.
     */
    fun decode(bytes: List<Int>): BmsData {
        if (bytes.size < MIN_FRAME_SIZE) return BmsData()

        return BmsData(
            displaySoc = bytes[SOC_INDEX] / 2.0,
            batteryVoltage = BmsFrameParser.unsigned16(bytes, VOLTAGE_HIGH_INDEX) / 10.0,
            batteryCurrent = BmsFrameParser.signed16(bytes, CURRENT_HIGH_INDEX) / 10.0,
            batteryTempC = BmsFrameParser.signed8(bytes, TEMP_MAX_INDEX).toDouble(),
            cumulativeEnergyChargedKwh = energyAt(bytes, ENERGY_CHARGED_INDEX),
            cumulativeEnergyDischargedKwh = energyAt(bytes, ENERGY_DISCHARGED_INDEX),
        )
    }

    /**
     * Лічильники читаються окремо від решти: короткий кадр не повинен позбавляти
     * додаток заряду й напруги, які лежать на початку і вже прочитані.
     */
    private fun energyAt(bytes: List<Int>, index: Int): Double {
        if (bytes.size < MIN_FRAME_SIZE_WITH_COUNTERS) return 0.0

        val value = BmsFrameParser.unsigned32(bytes, index) / 10.0
        return if (value <= MAX_PLAUSIBLE_LIFETIME_KWH) value else 0.0
    }
}
