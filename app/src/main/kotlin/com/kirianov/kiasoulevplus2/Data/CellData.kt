package com.kirianov.kiasoulevplus2.Data

/**
 * Напруги комірок акумулятора, зчитані з кадрів 21 02..21 04.
 */
data class CellData(
    val cellVoltages: List<Double> = emptyList(), // Напруги комірок у Вольтах
    val minVoltage: Double = 0.0,                 // Мінімальна напруга
    val maxVoltage: Double = 0.0,                 // Максимальна напруга
    val deltaVoltage: Double = 0.0,               // Розбаланс (Макс - Мін)
    val debugInfo: String = "",                   // Текст дебаг-логу для екрана
) {
    companion object {
        /** Скільки комірок у батареї Kia Soul EV: 3 кадри по 32. */
        const val TOTAL_CELLS = 96
    }
}
