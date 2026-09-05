package com.kirianov.kiasoulevplus2.Data

/**
 * Показники високовольтної батареї, зчитані з кадру BMS 21 01.
 * displaySoc = -1.0 означає «даних ще немає», щоб відрізнити це від справжнього нуля.
 */
data class BmsData(
    val displaySoc: Double = NO_DATA,     // SOC з BMS (%)
    val batteryVoltage: Double = 0.0,     // Напруга батареї (В)
    // Струм (А), від'ємний = розряд. Сире значення з BMS протилежне за знаком —
    // див. пояснення в BmsResponseDecoder.
    val batteryCurrent: Double = 0.0,
    val batteryTempC: Double = 0.0,       // Максимальна температура модулів (°C)

    /**
     * Лічильники енергії за весь час життя батареї (кВт·год).
     * Самі по собі це великі числа; корисна з них саме різниця за поїздку.
     */
    val cumulativeEnergyChargedKwh: Double = 0.0,
    val cumulativeEnergyDischargedKwh: Double = 0.0,

    /**
     * Ті самі лічильники, але в амперу-годинах. Показуються поруч із кВт·год
     * навмисно: їх відношення мусить дати середню напругу пакета, і саме ця
     * перехресна перевірка викриває читання не тих байтів. Раніше як кВт·год
     * показувалися саме ці числа.
     */
    val cumulativeChargedAh: Double = 0.0,
    val cumulativeDischargedAh: Double = 0.0,
) {
    val hasData: Boolean get() = displaySoc >= 0.0

    /** Чи вдалося зчитати лічильники: кадр буває коротшим, ніж місце, де вони лежать. */
    val hasEnergyCounters: Boolean get() = cumulativeEnergyDischargedKwh > 0.0

    companion object {
        const val NO_DATA = -1.0
    }
}
