// ====================================================================================
// VIEWMODEL ЕКРАНА КОМІРОК (CellsViewModel)
//
// Пише в GeneralData запит на зчитування та введені вручну напруги. Ні про блок
// Bluetooth, ні про сховище не знає — ті самі побачать зміни у сховищі стану.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.cells

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.format.parseDecimalInput
import kotlinx.coroutines.flow.StateFlow

class CellsViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    /**
     * Просить блок Bluetooth зчитати комірки з авто.
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

    /** Зберігає введену вручну напругу; сам запис на диск робить блок сховища. */
    fun onManualVoltageEntered(index: Int, text: String) {
        parseDecimalInput(text)?.let { GeneralData.setManualCell(index, it) }
    }
}
