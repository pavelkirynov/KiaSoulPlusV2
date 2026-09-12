package com.kirianov.kiasoulevplus2.Data

/**
 * Напруги комірок, введені вручну. Показуються, доки з авто нічого не прийшло.
 * Живуть тут, а не в екрані, щоб не з'являлося друге сховище стану поза GeneralData.
 */
data class ManualCells(
    val voltages: Map<Int, Double> = emptyMap(),
) {
    fun voltageAt(index: Int): Double = voltages[index] ?: 0.0
}
