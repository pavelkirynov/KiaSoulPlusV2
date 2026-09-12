package com.kirianov.kiasoulevplus2.Data

/**
 * Один знімок лічильників у момент часу.
 *
 * [elapsedMs] — монотонний час від початку поїздки, а не годинник: переведення
 * часу або зміна часового поясу не мають зіпсувати розрахунок.
 *
 * [odometerKm] — null, поки пробіг невідомий. Саме null, а не нуль: лічильники BMS
 * приходять раніше за перше вікно монітора, і знімок із нулем робив пройдену
 * відстань рівною всьому пробігу авто — звідси й «середня швидкість 3899209 км/год».
 */
data class TripSample(
    val elapsedMs: Long,
    val odometerKm: Double?,
    val dischargedKwh: Double,
    val chargedKwh: Double,
)

/**
 * Знімки за поїздку. Саме вони прив'язують енергію до часу й пробігу: маючи два
 * знімки, можна порахувати витрату на будь-якому відрізку між ними.
 */
data class TripHistory(
    val samples: List<TripSample> = emptyList(),
) {
    val isEmpty: Boolean get() = samples.isEmpty()

    /**
     * Додає знімок. Знімки, у яких не змінилося нічого, крім часу, відкидаються:
     * інакше стоянка з'їдала б усю історію і вікно «останні 20 км» не дотягувалося б
     * до потрібної відмітки.
     */
    fun plus(sample: TripSample): TripHistory {
        val last = samples.lastOrNull()
        val nothingChanged = last != null &&
            last.odometerKm == sample.odometerKm &&
            last.dischargedKwh == sample.dischargedKwh &&
            last.chargedKwh == sample.chargedKwh

        val updated = when {
            // Останній знімок лише подовжуємо в часі, щоб тривалість не завмирала.
            nothingChanged -> samples.dropLast(1) + sample
            else -> samples + sample
        }

        return TripHistory(updated.takeLast(MAX_SAMPLES))
    }

    /** Найраніший знімок діапазону, або null, якщо історії ще немає. */
    fun startOf(window: ConsumptionWindow): TripSample? {
        if (samples.isEmpty()) return null
        val distance = window.distanceKm ?: return samples.first()

        val latest = lastKnownOdometer() ?: return samples.first()
        val from = latest - distance
        // Останній знімок, зроблений до потрібної відмітки пробігу.
        return samples.lastOrNull { it.odometerKm != null && it.odometerKm <= from }
            ?: samples.first()
    }

    /** Чи набралося в історії достатньо пробігу, щоб діапазон був повним. */
    fun covers(window: ConsumptionWindow): Boolean {
        val distance = window.distanceKm ?: return samples.isNotEmpty()
        val travelled = travelledKm() ?: return false
        return travelled >= distance
    }

    /**
     * Пройдена відстань за наявними знімками: між першим і останнім, де пробіг відомий.
     * Знімки без пробігу в розрахунок відстані не входять взагалі.
     */
    fun travelledKm(from: TripSample? = null): Double? {
        val range = if (from == null) samples else samples.dropWhile { it !== from }
        val known = range.mapNotNull { it.odometerKm }
        if (known.size < 2) return null
        return (known.last() - known.first()).coerceAtLeast(0.0)
    }

    private fun lastKnownOdometer(): Double? = samples.lastOrNull { it.odometerKm != null }?.odometerKm

    companion object {
        /**
         * Опитування йде раз на 800 мс, але знімки з незмінними даними не додаються,
         * тому цього запасу вистачає на кілька годин руху.
         */
        const val MAX_SAMPLES = 5000
    }
}
