package com.example.kiasoulevplus2.Data

data class BmsData(
    val displaySoc: Double = -1.0,        // SOC з BMS (%)
    val actualSoc: Double = -1.0,         // BMS Actual SOC (%)
    val batteryVoltage: Double = 0.0,     // Напруга батареї (В)
    val batteryCurrent: Double = 0.0,     // Струм (А)
    val batteryTempC: Double = 0.0,        // Температура (°C)
    val cumulativeChargeAh: Double = 0.0,  // Накопичена зарядка
    val cumulativeDischargeAh: Double = 0.0 // Накопичена розрядка
)
