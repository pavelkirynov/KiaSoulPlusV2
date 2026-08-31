// ====================================================================================
// ЄДИНА ТОЧКА ОБМІНУ ДАНИМИ МІЖ БЛОКАМИ (GeneralData)
//
// Блоки додатка (Bluetooth, декодери, обчислення, сховище, інтерфейс, Android Auto)
// не знають один про одного і не викликають один одного. Кожен читає потрібне звідси
// і сюди ж пише свій результат.
//
// Це сховище навмисно пасивне: воно не рахує, не декодує і нікуди не звертається.
// Уся логіка живе у блоках, тому будь-який із них можна замінити, не чіпаючи решту.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object GeneralData {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // --- Запити від інтерфейсу -------------------------------------------------

    fun requestConnect() = _state.update { it.copy(request = AppRequest.Connect) }

    fun requestDisconnect() = _state.update { it.copy(request = AppRequest.Disconnect) }

    /** Викликає блок Bluetooth, коли прийняв запит до виконання. */
    fun clearRequest() = _state.update { it.copy(request = AppRequest.None) }

    fun updateInputBms(transform: (InputBmsData) -> InputBmsData) =
        _state.update { it.copy(inputBms = transform(it.inputBms)) }

    // --- Сирий обмін із шиною: пише блок Bluetooth -----------------------------

    fun publishBatteryFrames(commands: List<String>, responses: List<String>) =
        _state.update {
            it.copy(can = it.can.copy(batteryFrames = nextFrames(commands, responses)))
        }

    fun publishCellFrames(commands: List<String>, responses: List<String>) =
        _state.update {
            it.copy(can = it.can.copy(cellFrames = nextFrames(commands, responses)))
        }

    // --- Розібрані показники: пише блок декодерів ------------------------------

    fun updateBms(bms: BmsData) = _state.update { it.copy(bms = bms) }

    fun updateCells(cells: CellData) = _state.update { it.copy(cells = cells) }

    // --- Похідні величини: пише блок обчислень ---------------------------------

    fun updateCalculated(calculated: CalculatedData) =
        _state.update { it.copy(calculated = calculated) }

    // --- Ручні напруги: пише блок сховища та інтерфейс -------------------------

    fun updateManualCells(voltages: Map<Int, Double>) =
        _state.update { it.copy(manualCells = ManualCells(voltages)) }

    fun setManualCell(index: Int, voltage: Double) =
        _state.update { current ->
            current.copy(manualCells = ManualCells(current.manualCells.voltages + (index to voltage)))
        }

    // --- Стан з'єднання та лог -------------------------------------------------

    fun updateConnection(connection: ConnectionState, debugInfo: String) =
        _state.update { it.copy(connection = connection, debugInfo = debugInfo) }

    fun updateDebugInfo(debugInfo: String) = _state.update { it.copy(debugInfo = debugInfo) }

    /** Повертає сховище у вихідний стан. Потрібно тестам, бо об'єкт живе весь процес. */
    fun reset() {
        _state.value = State()
        sequence = 0
    }

    @Volatile
    private var sequence = 0L

    private fun nextFrames(commands: List<String>, responses: List<String>) =
        CanFrames(commands = commands, responses = responses, sequence = ++sequence)
}
