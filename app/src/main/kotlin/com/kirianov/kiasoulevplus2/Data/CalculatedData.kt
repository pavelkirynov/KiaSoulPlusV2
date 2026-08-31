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
     * Що відомо про годинник магнітоли порівняно з телефоном.
     *
     * Навіщо: «час в авто злітає» — це три різні несправності з різним ремонтом,
     * і відрізняються вони формою розходження. Див. ClockDriftHistory.
     */
    val clock: ClockStatus = ClockStatus(),
)

/**
 * Підсумок спостереження за годинником магнітоли.
 *
 * [rateSecondsPerHour] — наскільки годинник авто біжить швидше за телефон.
 * Саме це число відрізняє несправний кварц від підміни часу по GPS: кварц дає
 * рівномірний хід, підміна — стрибки.
 */
data class ClockStatus(
    val driftSeconds: Int? = null,
    val rateSecondsPerHour: Double? = null,
    val jumpCount: Int = 0,
    val observedMs: Long = 0,
) {
    val hasDrift: Boolean get() = driftSeconds != null

    /** Розходження, помітне на око: менше — це округлення секунд у кадрі. */
    val isDrifted: Boolean get() = (driftSeconds ?: 0).let { it > DRIFT_WARNING_SECONDS || it < -DRIFT_WARNING_SECONDS }

    /** Хід, помітно відмінний від нормального: справний кварц не набігає стільки. */
    val isRunningOff: Boolean
        get() = rateSecondsPerHour?.let { it > RATE_WARNING_SECONDS_PER_HOUR || it < -RATE_WARNING_SECONDS_PER_HOUR } == true

    /**
     * Найімовірніша причина, зведена з форми розходження. Саме те, що потрібно, щоб
     * не шукати далі там, де причини немає.
     */
    val diagnosis: ClockDiagnosis
        get() = when {
            driftSeconds == null -> ClockDiagnosis.Unknown
            isRunningOff -> ClockDiagnosis.RateFault
            jumpCount > 0 -> ClockDiagnosis.TimeJumps
            isDrifted -> ClockDiagnosis.SetWrong
            else -> ClockDiagnosis.Fine
        }

    private companion object {
        const val DRIFT_WARNING_SECONDS = 120
        const val RATE_WARNING_SECONDS_PER_HOUR = 20.0
    }
}

enum class ClockDiagnosis {
    /** Кадр 567 ще не приходив. */
    Unknown,

    /** Годинник збігається з телефоном. */
    Fine,

    /** Годинник просто виставлений неправильно, але йде нормально. */
    SetWrong,

    /** Годинник переставляли під час спостереження: підміна часу або перезавантаження. */
    TimeJumps,

    /** Годинник іде з іншою швидкістю: кварц RTC у магнітолі. GPS тут ні до чого. */
    RateFault,
}

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
