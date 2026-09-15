// ====================================================================================
// ЕКРАН ДЛЯ ANDROID AUTO (MainCarScreen)
//
// Підписується на стан і перемальовує панель лише тоді, коли змінився показаний текст:
// хост обмежує частоту оновлення шаблонів, а стан оновлюється кожні 800 мс, тож
// invalidate() на кожну зміну стану був би зайвим навантаженням на хост.
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                GeneralData.state
                    .map { CarPaneModel.from(it) }
                    .distinctUntilChanged()
                    .onEach { invalidate() }
                    .launchIn(owner.lifecycleScope)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val model = CarPaneModel.from(GeneralData.state.value)

        val pane = Pane.Builder()
            .addRow(row("Заряд батареї (SOC)", model.soc))
            .addRow(row("Напруга та струм", model.voltageAndCurrent))
            .addRow(row("Потужність", model.power))
            .addRow(row("Температура ВВБ", model.temperature))
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(model.title)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun row(title: String, text: String): Row =
        Row.Builder().setTitle(title).addText(text).build()
}
