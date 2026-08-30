// ====================================================================================
// ЕКРАН ДЛЯ ANDROID AUTO (MainCarScreen)
//
// Підписується на GeneralData і викликає invalidate() на кожну зміну стану —
// раніше шаблон будувався один раз і показував на магнітолі застиглий знімок.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Interface.formatDecimal
import com.kirianov.kiasoulevplus2.Interface.formatMeasurement
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                GeneralData.state
                    .onEach { invalidate() }
                    .launchIn(owner.lifecycleScope)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = GeneralData.state.value
        val bms = state.bms

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Заряд батареї (SOC)")
                    .addText(if (bms.hasData) "${formatDecimal(bms.displaySoc, 1)} %" else NO_DATA_TEXT)
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle("Напруга та струм")
                    .addText(
                        if (bms.hasData) {
                            "${formatMeasurement(bms.batteryVoltage, 1, "В")} | " +
                                formatMeasurement(bms.batteryCurrent, 1, "А")
                        } else {
                            NO_DATA_TEXT
                        },
                    )
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle("Потужність")
                    .addText(
                        if (bms.hasData) formatMeasurement(state.calculated.powerKw, 2, "кВт") else NO_DATA_TEXT,
                    )
                    .build(),
            )
            .addRow(
                Row.Builder()
                    .setTitle("Температура ВВБ")
                    .addText(if (bms.hasData) formatMeasurement(bms.batteryTempC, 1, "°C") else NO_DATA_TEXT)
                    .build(),
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(if (state.isConnected) "Kia Soul EV" else "Kia Soul EV — немає зв'язку")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private companion object {
        const val NO_DATA_TEXT = "--"
    }
}
