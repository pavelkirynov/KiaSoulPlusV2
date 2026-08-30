// ====================================================================================
// VIEWMODEL ЕКРАНА КОМІРОК (CellsViewModel)
//
// Ставить прапорець scanCellsRequested, за яким ConnectionManager запускає цикл
// 21 02..21 04. Індикатор завантаження виводиться з самого прапорця, а не з окремого
// поля — раніше вони могли розійтися, і кнопка залишалася заблокованою назавжди.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.cells

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.StateFlow

class CellsViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    /**
     * Просить ConnectionManager зчитати комірки з авто.
     * Без з'єднання запит не ставиться: цикл опитування не працює, прапорець нікому
     * було б зняти, і кнопка залишалася б у стані «Зчитую...».
     */
    fun onRequestReadCells() {
        if (!GeneralData.state.value.isConnected) {
            GeneralData.updateDebugInfo("Немає з'єднання з адаптером — спершу підключіться")
            return
        }

        GeneralData.updateInputBms { it.copy(scanCellsRequested = true) }
        GeneralData.updateDebugInfo("Запит зчитування комірок...")
    }
}
