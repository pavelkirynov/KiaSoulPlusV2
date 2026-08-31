// ViewModel головного екрана: віддає стан із GeneralData і пише туди ж запит на з'єднання.

package com.kirianov.kiasoulevplus2.Interface.screens.main

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    /**
     * Ставить запит у сховище. Інтерфейс не тримає посилання на блок Bluetooth —
     * той сам побачить запит і виконає його.
     */
    fun onWindowSelected(window: ConsumptionWindow) = GeneralData.selectConsumptionWindow(window)

    fun onConnectClick() {
        if (GeneralData.state.value.isConnected) {
            GeneralData.requestDisconnect()
        } else {
            GeneralData.requestConnect()
        }
    }
}
