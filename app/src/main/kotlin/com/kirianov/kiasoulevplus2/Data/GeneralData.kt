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

    /**
     * «Водій сів в авто»: магнітола щойно з'єдналася з телефоном.
     *
     * Наміру користувача це не міняє — якщо він сам натиснув «Відключити», так і
     * лишиться. Це лише привід спробувати зараз, а не через дві хвилини.
     */
    fun noteArrival() = _state.update { it.copy(arrivals = it.arrivals + 1) }

    fun updateInputBms(transform: (InputBmsData) -> InputBmsData) =
        _state.update { it.copy(inputBms = transform(it.inputBms)) }

    // --- Сирий обмін із шиною: пише блок Bluetooth -----------------------------

    fun publishBatteryFrames(commands: List<String>, responses: List<String>) =
        _state.update {
            it.copy(can = it.can.copy(batteryFrames = nextFrames(commands, responses)))
        }

    /**
     * Сирі відповіді на ручний запит. У commands кладеться заголовок, команда і,
     * якщо запит не вдався, текст помилки — так блок розбору отримує весь контекст.
     */
    fun publishProbeFrames(header: String, command: String, response: String, error: String? = null) =
        _state.update {
            it.copy(
                can = it.can.copy(
                    probeFrames = nextFrames(listOfNotNull(header, command, error), listOf(response)),
                ),
            )
        }

    fun publishMonitorLines(lines: List<String>, filterId: String) =
        _state.update {
            it.copy(can = it.can.copy(monitor = MonitorCapture(lines, filterId, ++sequence)))
        }

    fun publishCellFrames(commands: List<String>, responses: List<String>) =
        _state.update {
            it.copy(can = it.can.copy(cellFrames = nextFrames(commands, responses)))
        }

    // --- Розібрані показники: пише блок декодерів ------------------------------

    fun updateBms(bms: BmsData) = _state.update { it.copy(bms = bms) }

    fun updateCells(cells: CellData) = _state.update { it.copy(cells = cells) }

    // --- Тест комірок під навантаженням ------------------------------------------

    /** Сирий прохід: пише блок Bluetooth, читає блок декодерів. */
    fun publishCellSweep(
        beforeResponse: String,
        cellCommands: List<String>,
        cellResponses: List<String>,
        afterResponse: String,
        atMs: Long,
    ) = _state.update {
        it.copy(
            can = it.can.copy(
                cellSweep = CellSweepFrames(
                    beforeResponse = beforeResponse,
                    cellCommands = cellCommands,
                    cellResponses = cellResponses,
                    afterResponse = afterResponse,
                    atMs = atMs,
                    sequence = ++sequence,
                ),
            ),
        )
    }

    /** Розібраний прохід: пише блок декодерів, накопичує блок тесту. */
    fun publishDecodedSweep(sweep: CellSweep) =
        _state.update { it.copy(cellTest = it.cellTest.copy(lastSweep = sweep)) }

    fun updateCellTest(transform: (CellTestState) -> CellTestState) =
        _state.update { it.copy(cellTest = transform(it.cellTest)) }

    fun requestCellTest(request: CellTestRequest) =
        updateCellTest { it.copy(request = request) }

    fun clearCellTestRequest() = updateCellTest { it.copy(request = CellTestRequest.None) }

    fun updateVehicle(vehicle: VehicleData) = _state.update { it.copy(vehicle = vehicle) }

    /** Облік зарядок за пожиттєвим лічильником: пише блок tools/charging. */
    fun updateChargeLog(charge: ChargeLog) = _state.update { it.copy(charge = charge) }

    /** «Кінець зарядки» вручну: закрити сесію різницею пожиттєвого лічильника. */
    fun requestChargeFinish() =
        _state.update { it.copy(charge = it.charge.copy(request = ChargeRequest.FinishSession)) }

    fun clearChargeRequest() =
        _state.update { it.copy(charge = it.charge.copy(request = ChargeRequest.None)) }

    // --- Похідні величини: пише блок обчислень ---------------------------------

    fun updateCalculated(calculated: CalculatedData) =
        _state.update { it.copy(calculated = calculated) }

    fun addTripSample(sample: TripSample) =
        _state.update { it.copy(tripHistory = it.tripHistory.plus(sample)) }

    fun clearTripHistory() = _state.update { it.copy(tripHistory = TripHistory()) }

    fun updateSettings(settings: Settings) = _state.update { it.copy(settings = settings) }

    fun setAutoConnect(enabled: Boolean) =
        _state.update { it.copy(settings = it.settings.copy(autoConnect = enabled)) }

    fun setWakeOnDevice(address: String) =
        _state.update { it.copy(settings = it.settings.copy(wakeOnDeviceAddress = address)) }

    // --- Гараж: які авто відомі й за яке рахуємо ---------------------------------

    fun updateGarage(transform: (Garage) -> Garage) =
        _state.update { it.copy(garage = transform(it.garage)) }

    /** VIN, прочитаний із шини в цьому підключенні. Пише блок Bluetooth. */
    fun noteDetectedVin(vin: String) =
        _state.update { it.copy(garage = it.garage.copy(detectedVin = vin)) }

    /** Обране авто. Вручну — лише коли зв'язку немає: на шині VIN сам себе назве. */
    fun selectCar(vin: String) =
        _state.update { it.copy(garage = it.garage.copy(activeVin = vin)) }

    /** Корисна ємність пакета активного авто, кВт·год. Нуль означає «не задано». */
    fun setPackKwh(kwh: Double) = updateActiveCar { it.copy(packKwh = kwh) }

    fun setCarName(name: String) = updateActiveCar { it.copy(name = name) }

    private fun updateActiveCar(transform: (CarProfile) -> CarProfile) =
        _state.update { state ->
            val garage = state.garage
            val updated = transform(garage.active)
            val cars = if (garage.cars.any { it.vin == updated.vin }) {
                garage.cars.map { if (it.vin == updated.vin) updated else it }
            } else {
                garage.cars + updated
            }
            state.copy(garage = garage.copy(cars = cars, activeVin = updated.vin))
        }

    /** Список спарованих пристроїв: публікує блок Bluetooth. */
    fun updatePairedDevices(devices: List<PairedDevice>) =
        _state.update { it.copy(pairedDevices = devices) }

    fun setJournalEnabled(enabled: Boolean) =
        _state.update { it.copy(settings = it.settings.copy(journal = enabled)) }

    // --- Виміряна крива ємності: пише блок tools/energy ------------------------

    fun updateCurve(transform: (BatteryCurve) -> BatteryCurve) =
        _state.update { it.copy(curve = transform(it.curve)) }

    /** Кнопка «Забути криву» з екрана «Прогноз». */
    fun requestCurveReset() = updateCurve { it.copy(request = CurveRequest.Reset) }

    /** Викликає блок виміру, коли прийняв запит до виконання. */
    fun clearCurveRequest() = updateCurve { it.copy(request = CurveRequest.None) }

    // --- Журнал діагностики: пише блок tools/journal ---------------------------

    fun updateJournal(transform: (Journal) -> Journal) =
        _state.update { it.copy(journal = transform(it.journal)) }

    /** Кнопка «Очистити журнал» з екрана «Експерименти». */
    fun requestJournalClear() = updateJournal { it.copy(request = JournalRequest.Clear) }

    /** Викликає блок журналу, коли прийняв запит до виконання. */
    fun clearJournalRequest() = updateJournal { it.copy(request = JournalRequest.None) }

    /** Скидання відліку точності прогнозу кнопкою з екрана «Прогноз». */
    fun resetRangeAccuracy() = _state.update { it.copy(rangeAccuracy = RangeAccuracy()) }

    fun updateRangeAccuracy(accuracy: RangeAccuracy) = _state.update { it.copy(rangeAccuracy = accuracy) }

    // --- Прогноз залишку ходу: пише блок прогнозу ------------------------------

    fun updateMl(transform: (MlData) -> MlData) = _state.update { it.copy(ml = transform(it.ml)) }

    // --- Запити до блока прогнозу: пише інтерфейс ------------------------------

    fun requestMlRetrain() = updateMl { it.copy(request = MlRequest.Retrain) }

    fun requestMlReset() = updateMl { it.copy(request = MlRequest.Reset) }

    /** Викликає блок прогнозу, коли прийняв запит до виконання. */
    fun clearMlRequest() = updateMl { it.copy(request = MlRequest.None) }

    // --- Вибір діапазону: пише інтерфейс ---------------------------------------

    fun selectConsumptionWindow(window: ConsumptionWindow) =
        _state.update { it.copy(consumptionWindow = window) }

    // --- Ручні запити -----------------------------------------------------------

    fun requestProbe(header: String, command: String) =
        _state.update {
            it.copy(probe = it.probe.copy(pending = ProbeRequest(header, command, ++sequence)))
        }

    /** Викликає блок Bluetooth, коли прийняв запит до виконання. */
    fun clearProbeRequest() = _state.update { it.copy(probe = it.probe.copy(pending = null)) }

    fun addProbeResult(result: ProbeResult) =
        _state.update { it.copy(probe = it.probe.plus(result)) }

    fun setProbeTarget(value: Long?) =
        _state.update { it.copy(probe = it.probe.copy(targetValue = value)) }

    /** Переписує вже збережені відповіді: потрібно, коли змінилося шукане значення. */
    fun updateProbeResults(results: List<ProbeResult>) =
        _state.update { it.copy(probe = it.probe.copy(results = results)) }

    // --- Пошук невідомої ознаки на шині -----------------------------------------

    /** Послухати шину без фільтра: побачити, які кадри на ній узагалі є. */
    fun requestBusSweep() =
        _state.update { it.copy(probe = it.probe.copy(sweep = SweepRequest(++sequence))) }

    fun clearSweepRequest() = _state.update { it.copy(probe = it.probe.copy(sweep = null)) }

    /** Домішує щойно побачені кадри до пам'яті шини. Пише блок ручних запитів. */
    fun rememberBusFrames(frames: Map<String, List<Int>>) {
        if (frames.isEmpty()) return
        _state.update { it.copy(probe = it.probe.copy(liveFrames = it.probe.liveFrames + frames)) }
    }

    /**
     * Зберегти поточну пам'ять шини як знімок [slot] («A» або «B»).
     *
     * Знімок береться з пам'яті, а не з останнього вікна: одне вікно бачить рівно
     * один ID, і знімком із нього порівнювати було б нічого.
     */
    fun captureBusSnapshot(slot: BusSlot, label: String, nowMs: Long) =
        _state.update {
            val snapshot = BusSnapshot(label = label, atMs = nowMs, frames = it.probe.liveFrames)
            it.copy(
                probe = when (slot) {
                    BusSlot.A -> it.probe.copy(snapshotA = snapshot)
                    BusSlot.B -> it.probe.copy(snapshotB = snapshot)
                },
            )
        }

    /** Забути пам'ять шини: перед новим виміром старі кадри лише заважають. */
    fun forgetBusFrames() =
        _state.update { it.copy(probe = it.probe.copy(liveFrames = emptyMap())) }

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
