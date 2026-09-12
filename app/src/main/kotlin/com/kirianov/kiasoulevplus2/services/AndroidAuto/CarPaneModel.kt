// ====================================================================================
// ВМІСТ ЕКРАНА ANDROID AUTO (CarPaneModel)
//
// Готові рядки для панелі, зібрані зі стану додатка. Винесено з MainCarScreen окремо,
// щоб (а) вміст можна було перевірити тестами без магнітоли і (б) екран можна було
// перемальовувати лише тоді, коли текст справді змінився: хост Android Auto обмежує
// частоту оновлення шаблонів, а опитування CAN оновлює стан кожні 800 мс.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import com.kirianov.kiasoulevplus2.tools.format.formatMeasurement

data class CarPaneModel(
    val title: String,
    val soc: String,
    val voltageAndCurrent: String,
    val power: String,
    val temperature: String,
) {
    companion object {
        const val NO_DATA_TEXT = "--"

        fun from(state: State): CarPaneModel {
            val bms = state.bms
            if (!bms.hasData) {
                return CarPaneModel(
                    title = titleFor(state),
                    soc = NO_DATA_TEXT,
                    voltageAndCurrent = NO_DATA_TEXT,
                    power = NO_DATA_TEXT,
                    temperature = NO_DATA_TEXT,
                )
            }

            return CarPaneModel(
                title = titleFor(state),
                soc = "${formatDecimal(bms.displaySoc, 1)} %",
                voltageAndCurrent = "${formatMeasurement(bms.batteryVoltage, 1, "В")} | " +
                    formatMeasurement(bms.batteryCurrent, 1, "А"),
                power = formatMeasurement(state.calculated.powerKw, 2, "кВт"),
                temperature = formatMeasurement(bms.batteryTempC, 1, "°C"),
            )
        }

        private fun titleFor(state: State) =
            if (state.isConnected) "Kia Soul EV" else "Kia Soul EV — немає зв'язку"
    }
}
