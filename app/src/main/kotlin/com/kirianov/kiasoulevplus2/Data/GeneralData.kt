package com.kirianov.kiasoulevplus2.Data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Стан додатка.
 */
data class State(
    val inputBms: InputBmsData = InputBmsData(),
    val bms: BmsData = BmsData(),
    val calculated: CalculatedData = CalculatedData(),
    val cells: CellData = CellData(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    val debugInfo: String = "",
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected
}

/**
 * Фаза Bluetooth-з'єднання. Окремий тип замість пари булів прибирає
 * стан «підключаємось і водночас підключені», який раніше був можливий.
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

/**
 * Єдине центральне сховище стану додатка (Single Source of Truth).
 */
object GeneralData {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun updateInputBms(transform: (InputBmsData) -> InputBmsData) {
        _state.update { current -> current.copy(inputBms = transform(current.inputBms)) }
    }

    /** Записує комірки разом із перерахованими на їх основі величинами. */
    fun updateCellData(cellData: CellData) {
        _state.update { current ->
            current.copy(
                cells = cellData,
                calculated = CalculationEngine.calculate(current.bms, cellData),
            )
        }
    }

    /** Записує показники BMS разом із перерахованими на їх основі величинами. */
    fun updateBmsData(bmsData: BmsData) {
        _state.update { current ->
            current.copy(
                bms = bmsData,
                calculated = CalculationEngine.calculate(bmsData, current.cells),
            )
        }
    }

    fun updateConnection(connection: ConnectionState, debugInfo: String) {
        _state.update { current -> current.copy(connection = connection, debugInfo = debugInfo) }
    }

    fun updateDebugInfo(debugInfo: String) {
        _state.update { current -> current.copy(debugInfo = debugInfo) }
    }

    fun updateState(transform: (State) -> State) {
        _state.update(transform)
    }

    /** Повертає сховище у вихідний стан. Потрібно тестам, бо об'єкт живе весь процес. */
    fun reset() {
        _state.value = State()
    }
}
