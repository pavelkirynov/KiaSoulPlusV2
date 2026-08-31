package com.kirianov.kiasoulevplus2.Data

/**
 * Повний стан додатка. Кожен блок читає звідси те, що йому потрібно, і пише сюди
 * свій результат — прямих викликів між блоками немає.
 */
data class State(
    /** Запити від інтерфейсу до блока Bluetooth. */
    val request: AppRequest = AppRequest.None,
    val inputBms: InputBmsData = InputBmsData(),

    /** Сирий обмін із шиною: сюди пише блок Bluetooth, звідси читає блок декодерів. */
    val can: CanExchange = CanExchange(),

    /** Розібрані показники: сюди пише блок декодерів. */
    val bms: BmsData = BmsData(),
    val cells: CellData = CellData(),
    val vehicle: VehicleData = VehicleData(),

    /** Похідні величини: сюди пише блок обчислень. */
    val calculated: CalculatedData = CalculatedData(),

    /** Знімки лічильників за поїздку: їх веде блок обчислень. */
    val tripHistory: TripHistory = TripHistory(),

    /** Діапазон, за який рахувати витрату. Обирає користувач на екрані. */
    val consumptionWindow: ConsumptionWindow = ConsumptionWindow.Trip,

    /** Ручні запити до шини та відповіді на них. */
    val probe: ProbeState = ProbeState(),

    /** Введені вручну напруги: сюди пише блок сховища та інтерфейс. */
    val manualCells: ManualCells = ManualCells(),

    val connection: ConnectionState = ConnectionState.Disconnected,
    val debugInfo: String = "",
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected
}

/**
 * Фаза Bluetooth-з'єднання. Окремий тип замість пари булів прибирає стан
 * «підключаємось і водночас підключені», який інакше був би можливий.
 */
enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
}

/**
 * Дія, якої інтерфейс просить від блока Bluetooth. Блок виконує її та скидає в None,
 * тому інтерфейсу не потрібне посилання на сам блок.
 */
enum class AppRequest {
    None,
    Connect,
    Disconnect,
}
