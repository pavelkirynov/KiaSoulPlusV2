package com.example.kiasoulevplus2.Data

data class CalculatedData(
    val powerKw: Double = 0.0,            // Потужність (В * А / 1000)
    val minCellVoltage: Float = 0f,       // Мін. комірка
    val maxCellVoltage: Float = 0f,       // Макс. комірка
    val cellDeltaVolts: Float = 0f,       // Дисбаланс між комірками (В)
    
    val remainingRangeKm: Float = 0f,     // Розрахований запас ходу
    val drivenDistanceSessionKm: Float = 0f, // Проїхали за поїздку
    val avgConsumptionKwhPer100: Float = 0f   // Середня витрата
)
