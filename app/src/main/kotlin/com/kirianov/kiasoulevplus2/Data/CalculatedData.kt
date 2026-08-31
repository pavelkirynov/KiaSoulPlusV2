package com.kirianov.kiasoulevplus2.Data

/**
 * Величини, яких немає в кадрах напряму — їх рахує CalculationBlock.
 */
data class CalculatedData(
    val powerKw: Double = 0.0,          // Напруга * струм / 1000, від'ємна = розряд
    val minCellVoltage: Double = 0.0,   // Мін. комірка (В)
    val maxCellVoltage: Double = 0.0,   // Макс. комірка (В)
    val cellDeltaVolts: Double = 0.0,   // Розбаланс між комірками (В)

    /** Підсумок за всю поїздку. */
    val trip: WindowStats = WindowStats(),

    /** Підсумок за обраний діапазон: остання поїздка або останні N кілометрів. */
    val window: WindowStats = WindowStats(),

    /**
     * Наскільки годинник магнітоли розійшовся з телефоном, хвилини.
     * Додатне — авто попереду. null, поки кадр 567 не приходив.
     *
     * Навіщо: коли РЕБ зсуває час по GPS, магнітола перестає пускати Android Auto,
     * і зі сторони це виглядає як «застосунок відвалився». Це число показує причину.
     */
    val clockDriftMinutes: Int? = null,
)

/**
 * Енергія, час і пробіг на одному відрізку поїздки.
 *
 * Похідні величини навмисно є властивостями, а не полями: так вони не можуть
 * розійтися з тим, з чого рахуються. Null означає «поділити нема на що» —
 * авто стоїть або пробіг ще не зчитано.
 */
data class WindowStats(
    val distanceKm: Double = 0.0,
    val durationMs: Long = 0,
    val consumedKwh: Double = 0.0,
    val recoveredKwh: Double = 0.0,

    /** Чи набралося потрібного пробігу; неповний діапазон варто позначити в UI. */
    val isComplete: Boolean = false,
) {
    val hasData: Boolean get() = durationMs > 0

    /** Чисто витрачено: віддано батареєю за вирахуванням поверненого. */
    val netKwh: Double get() = consumedKwh - recoveredKwh

    /** Витрата, Вт·год/км. Null, поки немає пробігу. */
    val whPerKm: Double? get() = if (distanceKm > 0.0) netKwh * 1000.0 / distanceKm else null

    /** Витрата, кВт·год/100 км — звичніший для водія вигляд тієї самої величини. */
    val kwhPer100Km: Double? get() = whPerKm?.let { it / 10.0 }

    val averageSpeedKmh: Double?
        get() = if (durationMs > 0 && distanceKm > 0.0) distanceKm / (durationMs / MS_PER_HOUR) else null

    /** Середня потужність за відрізок, кВт. */
    val averagePowerKw: Double?
        get() = if (durationMs > 0) netKwh / (durationMs / MS_PER_HOUR) else null

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
    }
}
