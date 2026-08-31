package com.kirianov.kiasoulevplus2.Data

/**
 * Показники, які віддає щиток приладів, а не BMS.
 */
data class VehicleData(
    /** Загальний пробіг, км. Нуль означає «ще не зчитано». */
    val odometerKm: Double = 0.0,
) {
    val hasOdometer: Boolean get() = odometerKm > 0.0
}
