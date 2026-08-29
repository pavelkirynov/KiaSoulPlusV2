// Модель даних інформації з CAN-шини авто (швидкість, пробіг, стан запалювання тощо).

package com.example.kiasoulevplus2.Data

data class AvtoCanData(
    val speedKmH: Float = 0f,             // Швидкість з CAN
    val odometerKm: Long = 0,             // Загальний пробіг авто
    val isIgnitionOn: Boolean = false,    // Стан запалювання
    val isCharging: Boolean = false,      // Чи підключена зарядка
    val gearPosition: String = "P"        // Режим селектора (P, R, N, D)
)
