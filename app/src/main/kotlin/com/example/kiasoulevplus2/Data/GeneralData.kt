package com.example.kiasoulevplus2.Data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Стан додатка (винесено окремо, щоб CodeAssist чітко бачив поля та copy)
 */
data class State(
    val inputBms: InputBmsData = InputBmsData(),
    val bms: BmsData = BmsData(),
    val avto: AvtoCanData = AvtoCanData(),
    val calculated: CalculatedData = CalculatedData(),
    val cells: CellData = CellData(),
    val isConnected: Boolean = false,
    val debugInfo: String = ""
)

/**
 * Єдине центральне сховище стану додатка (Single Source of Truth)
 */
object GeneralData {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Оновлення налаштувань / команд BMS
     */
    fun updateInputBms(transform: (InputBmsData) -> InputBmsData) {
        _state.update { current ->
            current.copy(inputBms = transform(current.inputBms))
        }
    }

    /**
     * Оновлення даних про комірки
     */
    fun updateCellData(cellData: CellData) {
        _state.update { current ->
            current.copy(cells = cellData)
        }
    }

    /**
     * Універсальне оновлення стану
     */
    fun updateState(transform: (State) -> State) {
        _state.update(transform)
    }
}
