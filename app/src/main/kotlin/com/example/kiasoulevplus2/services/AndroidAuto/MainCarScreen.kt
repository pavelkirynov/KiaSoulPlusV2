package com.kirianov.kiasoulevplus2.services.AndroidAuto



import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.example.kiasoulevplus2.Data.GeneralData

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val state = GeneralData.state.value
        val bms = state.bms

        val socRow = Row.Builder()
            .setTitle("Заряд батареї (SOC)")
            .addText("${bms.displaySoc} % (Actual: ${bms.actualSoc}%)")
            .build()

        val voltagePowerRow = Row.Builder()
            .setTitle("Напруга та Струм")
            .addText("${bms.batteryVoltage} В | ${bms.batteryCurrent} А")
            .build()

        val tempRow = Row.Builder()
            .setTitle("Температура ВВБ")
            .addText("${bms.batteryTempC} °C")
            .build()

        val pane = Pane.Builder()
            .addRow(socRow)
            .addRow(voltagePowerRow)
            .addRow(tempRow)
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Kia Soul EV")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
