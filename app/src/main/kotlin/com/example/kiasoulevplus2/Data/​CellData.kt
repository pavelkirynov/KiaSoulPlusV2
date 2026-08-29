package com.example.kiasoulevplus2.Data

/**
 * Дані про комірки акумулятора
 */
data class CellData(
    val cellVoltages: List<Double> = emptyList(), // Напруги 96 комірок у Вольтах
    val minVoltage: Double = 0.0,                 // Мінімальна напруга
    val maxVoltage: Double = 0.0,                 // Максимальна напруга
    val deltaVoltage: Double = 0.0,               // Розбаланс (Макс - Мін)
    val debugInfo: String = ""                    // Текст дебаг-логу для екрана
)
