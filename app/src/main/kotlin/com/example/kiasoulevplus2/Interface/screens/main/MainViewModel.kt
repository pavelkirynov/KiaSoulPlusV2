// ViewModel для головного екрана (MainScreen).
// Працює виключно з GeneralData.state.

package com.example.kiasoulevplus2.Interface.screens.main

import androidx.lifecycle.ViewModel
import com.example.kiasoulevplus2.Data.GeneralData
import com.example.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {

    // Читаємо єдине джерело правди
    val uiState: StateFlow<State> = GeneralData.state

    /**
     * Повідомляємо систему про бажання підключитися/відключитися
     */
    fun onConnectClick(onToggleConnect: () -> Unit) {
        onToggleConnect()
    }
}
