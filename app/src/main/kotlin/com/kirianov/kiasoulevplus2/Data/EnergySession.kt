package com.kirianov.kiasoulevplus2.Data

/**
 * Значення лічильників енергії в момент під'єднання до авто.
 *
 * BMS віддає лише підсумок за весь час, тому «скільки витрачено зараз» рахується
 * як різниця з цією позначкою. Ставиться при першому вдалому зчитуванні і
 * скидається при від'єднанні: одне підключення — одна поїздка.
 */
data class EnergySession(
    val startedChargedKwh: Double = 0.0,
    val startedDischargedKwh: Double = 0.0,
    val isStarted: Boolean = false,
) {
    companion object {
        fun startingFrom(bms: BmsData) = EnergySession(
            startedChargedKwh = bms.cumulativeEnergyChargedKwh,
            startedDischargedKwh = bms.cumulativeEnergyDischargedKwh,
            isStarted = true,
        )
    }
}
