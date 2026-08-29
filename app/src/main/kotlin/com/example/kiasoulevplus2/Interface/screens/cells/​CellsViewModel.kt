// ====================================================================================
// VIEWMODEL ЕКРАНА КОМІРОК (CellsViewModel)
//
// ПРИЗНАЧЕННЯ:
// Поєднує CellsScreen із GeneralData.
// При натисканні кнопки запускає прапорець scanCellsRequested = true в InputBmsData,
// що дає сигнал ConnectionManager розпочати цикл опитування 21 02, 21 03, 21 04.
// ====================================================================================

package com.example.kiasoulevplus2.Interface.screens.cells

import androidx.lifecycle.ViewModel
import com.example.kiasoulevplus2.Data.GeneralData
import com.example.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CellsViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Дає команду ConnectionManager зчитати 96 комірок з авто
     */
    fun onRequestReadCells(action: () -> Unit = {}) {
        _isLoading.value = true

        // Виставляємо прапорець для ConnectionManager
        GeneralData.updateInputBms { currentInput ->
            currentInput.copy(scanCellsRequested = true)
        }

        GeneralData.updateState { currentState ->
            currentState.copy(
                debugInfo = "Запит зчитування комірок..."
            )
        }

        action()
    }

    /**
     * Викликається для скидання індикатора завантаження
     */
    fun onCellsLoaded() {
        _isLoading.value = false
    }
}
