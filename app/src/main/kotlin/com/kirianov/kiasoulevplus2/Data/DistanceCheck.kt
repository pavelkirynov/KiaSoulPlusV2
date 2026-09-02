package com.kirianov.kiasoulevplus2.Data

/**
 * Пройдений шлях, порахований самотужки з швидкості, поруч із тим, що показав
 * одометр. Різниця між ними — це і є чесна міра того, наскільки застосунок
 * узагалі здатний рахувати відстань зі своїх даних.
 *
 * НАВІЩО. Швидкість приходить кадром 4F0 приблизно раз на шість секунд: у черзі
 * фільтрів монітора 4F0 стоїть через один, а вікно знімається раз на чотири цикли
 * опитування. За шість секунд між зразками машина встигає і розігнатися, і
 * загальмувати, тож інтеграл трапеціями мусить накопичувати помилку. Питання не
 * в тому, чи вона є, а яка вона — і відповідає на це саме порівняння з одометром.
 */
data class DistanceCheck(
    /** Інтеграл швидкості за часом, км. */
    val computedKm: Double = 0.0,

    /** Скільки за той самий час намотав одометр, км. */
    val odometerKm: Double = 0.0,

    /** Скільки зразків швидкості увійшло в інтеграл. */
    val samples: Int = 0,

    private val lastSpeedKmh: Double? = null,
    private val lastAtMs: Long = 0L,
    private val odometerStartKm: Double? = null,
) {
    val hasComputed: Boolean get() = samples >= MIN_SAMPLES && computedKm > 0.0
    val hasOdometer: Boolean get() = odometerKm > 0.0

    /** Додатна різниця означає, що застосунок нарахував більше, ніж одометр. */
    val differenceKm: Double get() = computedKm - odometerKm

    /** Помилка відносно одометра, %. Null, поки одометр нічого не намотав. */
    val errorPercent: Double?
        get() = if (hasOdometer && hasComputed) differenceKm / odometerKm * 100.0 else null

    /**
     * Додає зразок швидкості. Трапеціями по реальних мітках часу, як і енергія:
     * рахувати по номеру зразка не можна, бо між зразками бувають дірки.
     */
    fun plus(speedKmh: Double, atMs: Long, odometerKm: Double?): DistanceCheck {
        val withOdometer = withOdometer(odometerKm)
        val previousSpeed = lastSpeedKmh
        val previousAt = lastAtMs

        if (previousSpeed == null) {
            return withOdometer.copy(lastSpeedKmh = speedKmh, lastAtMs = atMs, samples = samples + 1)
        }

        val dtMs = atMs - previousAt
        // Дірку обрізаємо: за нею машина їхала, але скільки — невідомо, і чесніше
        // недорахувати, ніж вигадати кілометри, яких не було.
        val countedMs = dtMs.coerceIn(0L, MAX_STEP_MS)
        val hours = countedMs / MS_PER_HOUR
        val meanSpeed = (previousSpeed + speedKmh) / 2.0

        return withOdometer.copy(
            computedKm = computedKm + meanSpeed * hours,
            samples = samples + 1,
            lastSpeedKmh = speedKmh,
            lastAtMs = atMs,
        )
    }

    /**
     * Одометр веде власний відлік від першого побаченого показу. Саме різниця, а не
     * абсолютне число: порівнювати інтеграл із 188459 км сенсу немає.
     */
    private fun withOdometer(reading: Double?): DistanceCheck {
        if (reading == null || reading <= 0.0) return this
        val start = odometerStartKm ?: return copy(odometerStartKm = reading)
        return copy(odometerKm = (reading - start).coerceAtLeast(0.0))
    }

    companion object {
        /** Скільки чекати між зразками швидкості, перш ніж вважати це діркою. */
        const val MAX_STEP_MS = 15_000L

        /** З одного зразка інтеграла не буває: потрібні хоча б два кінці трапеції. */
        const val MIN_SAMPLES = 2

        private const val MS_PER_HOUR = 3_600_000.0
    }
}
