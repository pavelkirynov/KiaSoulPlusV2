package com.kirianov.kiasoulevplus2.Data

/**
 * Величини, яких немає в кадрах BMS напряму — вони рахуються з BmsData та CellData.
 * Заповнює CalculationEngine, тому кожне поле тут завжди відповідає останньому зчитуванню.
 */
data class CalculatedData(
    val powerKw: Double = 0.0,          // Напруга * струм / 1000, від'ємна = розряд
    val minCellVoltage: Double = 0.0,   // Мін. комірка (В)
    val maxCellVoltage: Double = 0.0,   // Макс. комірка (В)
    val cellDeltaVolts: Double = 0.0,   // Розбаланс між комірками (В)
)
