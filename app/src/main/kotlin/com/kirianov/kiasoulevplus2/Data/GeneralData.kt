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

    /** Сира відповідь на кадр 21 05. Розбирає її блок tools/battery. */
    fun publishPackHealthFrame(command: String, response: String) =
        _state.update {
            it.copy(
                can = it.can.copy(
                    packHealthFrames = nextFrames(listOf(command), listOf(response)),
                ),
            )
        }

    /** Розібраний знос батареї: пише блок tools/battery. */
    fun updatePackHealth(health: PackHealth) = _state.update { it.copy(packHealth = health) }

    /** Сира відповідь блока тиску в шинах. Розбирає її блок tools/vehicle. */
    fun publishTireFrame(command: String, response: String) =
        _state.update {
            it.copy(
                can = it.can.copy(tireFrames = nextFrames(listOf(command), listOf(response))),
            )
        }

    /** Розібраний тиск у шинах: пише блок tools/vehicle. */
    fun updateTires(tires: TireData) = _state.update { it.copy(tires = tires) }

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

    /** Яким із двох висновків фарбувати комірки. Вибір людини, не блока. */
    // Колір комірок більше не залежить від вердикту тесту: він рахується від
    // медіани пакета з порогами, які ставить власник. Перемикача «фарбувати за
    // опором / за мінімумом» на екрані немає, тож і сеттера тут немає — саме
    // поле лишається, бо його читають збережені заміри.

    /** Що показувати в клітинках сітки. Вибір людини, не блока. */
    fun setCellValueMode(mode: CellValueMode) = updateCellTest { it.copy(valueMode = mode) }

    // --- Історія замірів комірок -------------------------------------------------

    fun updateCellHistory(transform: (CellHistory) -> CellHistory) =
        _state.update { it.copy(cellHistory = transform(it.cellHistory)) }

    /** Зберегти те, що зараз на екрані, окремим заміром. */
    fun requestCellSnapshot() =
        updateCellHistory { it.copy(request = CellHistoryRequest.Save) }

    /** Видалити збережений замір за часом його зняття. */
    fun requestCellRecordDelete(atMs: Long) =
        updateCellHistory { it.copy(request = CellHistoryRequest.Delete, requestAtMs = atMs) }

    fun clearCellHistoryRequest() =
        updateCellHistory { it.copy(request = CellHistoryRequest.None, requestAtMs = 0L) }

    fun updateVehicle(vehicle: VehicleData) = _state.update { it.copy(vehicle = vehicle) }

    /**
     * Системи авто з широкомовних кадрів: колеса, ручник, світло, годинник,
     * запалювання. Окремий вхід, а не частина [updateVehicle], бо це поки
     * неперевірені числа й вони не мають права опинитися в розрахунках.
     */
    fun updateCarSystems(systems: CarSystems) = _state.update { it.copy(carSystems = systems) }

    // --- Помилки блоків авто -----------------------------------------------------

    fun updateFaults(transform: (FaultState) -> FaultState) =
        _state.update { it.copy(faults = transform(it.faults)) }

    /** Опитати всі блоки. Виконує блок Bluetooth: шина в нього. */
    fun requestFaultScan() = updateFaults { it.copy(request = FaultScanRequest.Scan) }

    fun clearFaultRequest() = updateFaults { it.copy(request = FaultScanRequest.None) }

    /** Опитування почалося: скільки блоків попереду. Пише блок Bluetooth. */
    fun startFaultScan(total: Int) = updateFaults {
        it.copy(running = true, results = emptyList(), done = 0, total = total, answer = null)
    }

    /** Сира відповідь одного блока. Розбирає її блок car/dtc. */
    fun publishFaultAnswer(header: String, raw: List<String>) = updateFaults {
        it.copy(answer = FaultAnswer(header, raw, ++sequence))
    }

    fun finishFaultScan(atMs: Long) = updateFaults { it.copy(running = false, scannedAtMs = atMs) }

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

    /** Умови далекої дороги з екрана прогнозу: відстань, ціна, потужність станції. */
    fun setTripConditions(trip: TripConditions) =
        _state.update { it.copy(settings = it.settings.copy(trip = trip)) }

    // --- Гараж: які авто відомі й за яке рахуємо ---------------------------------

    fun updateGarage(transform: (Garage) -> Garage) =
        _state.update { it.copy(garage = transform(it.garage)) }

    /** VIN, прочитаний із шини в цьому підключенні. Пише блок Bluetooth. */
    fun noteDetectedVin(vin: String) =
        _state.update {
            it.copy(
                garage = it.garage.copy(
                    detectedVin = vin,
                    vinNote = "прочитано $vin",
                    vinConfirmed = true,
                    vinPending = false,
                ),
            )
        }

    /**
     * Нове підключення: хто перед нами — питання відкрите, поки не відповіли.
     *
     * Викликається на початку кожного опитування шини. Доти дані показуються, але
     * в теку авто не пишуться — див. [com.kirianov.kiasoulevplus2.Data.State.carAccounting].
     */
    fun beginCarIdentification() =
        _state.update {
            it.copy(
                garage = it.garage.copy(vinConfirmed = false, vinPending = true, detectedVin = ""),
            )
        }

    /**
     * Чому VIN не прочитався. Мовчазна невдача тут коштувала переплутаних авто.
     *
     * Пишеться, коли спроби скінчилися, — тобто це не «ще питаємо», а «спитали й
     * не почули». Питання закривається: далі облік іде під наглядом сторожа
     * лічильників, бо чекати відповіді, якої не буде, означає не рахувати нічого.
     */
    fun noteVinFailure(reason: String) =
        _state.update { it.copy(garage = it.garage.copy(vinNote = reason, vinPending = false)) }

    /**
     * Перечитати VIN негайно: щось указує, що авто могло змінитися.
     *
     * Лічильник, а не прапорець: дві однакові підозри поспіль не мають злитися в
     * одну, інакше друга загубиться.
     */
    fun requestVinRecheck() =
        _state.update { it.copy(garage = it.garage.copy(vinRecheck = it.garage.vinRecheck + 1)) }

    fun updateShare(transform: (ShareState) -> ShareState) =
        updateGarage { it.copy(share = transform(it.share)) }

    /** Зібрати файл із даними активного авто. */
    fun requestCarExport() = updateShare { it.copy(request = ShareRequest.Export, note = "") }

    /** Прийняти файл із даними: злити його з нашими. */
    fun requestCarImport(path: String) =
        updateShare { it.copy(request = ShareRequest.Import, importPath = path, note = "") }

    /** Екран віддав файл системі «поділитися» — доручення виконано. */
    fun clearExportedPath() = updateShare { it.copy(exportedPath = "") }

    /**
     * Обране авто.
     *
     * Вибір руками — це ПЕРЕГЛЯД. Він показує дані обраного авто, але облік
     * лишається за тим, що на шині: [com.kirianov.kiasoulevplus2.Data.Garage.identified]
     * стає хибним, щойно обране й під'єднане розійшлися.
     */
    fun selectCar(vin: String) =
        _state.update { it.copy(garage = it.garage.copy(activeVin = vin)) }

    /** Видалити авто разом із його текою. Виконує блок гаража. */
    fun requestCarDelete(vin: String) =
        _state.update { it.copy(garage = it.garage.copy(deleteVin = vin)) }

    fun clearCarDelete() =
        _state.update { it.copy(garage = it.garage.copy(deleteVin = "")) }

    /** Корисна ємність пакета активного авто, кВт·год. Нуль означає «не задано». */
    fun setPackKwh(kwh: Double) = updateActiveCar { it.copy(packKwh = kwh) }

    fun setCarName(name: String) = updateActiveCar { it.copy(name = name) }

    /**
     * Правка КОНКРЕТНОГО авто, а не активного.
     *
     * Потрібна саме така, бо правити машину доводиться зі списку — там, де її
     * видно поруч із рештою. Робити її для цього активною означало б перемкнути
     * заразом усі екрани й графіки, тобто зробити побічну дію більшою за головну.
     */
    fun editCar(vin: String, name: String, packKwh: Double) =
        _state.update { state ->
            val garage = state.garage
            state.copy(
                garage = garage.copy(
                    cars = garage.cars.map {
                        if (it.vin == vin) it.copy(name = name, packKwh = packKwh) else it
                    },
                ),
            )
        }

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

    /** Пороги фарбування комірок з вікна налаштувань на екрані «Комірки». */
    fun setCellPalette(mode: CellValueMode, palette: CellPalette) =
        _state.update {
            it.copy(
                settings = it.settings.copy(
                    cellPalettes = it.settings.cellPalettes.with(mode, palette),
                ),
            )
        }

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
