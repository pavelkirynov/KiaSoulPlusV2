package com.kirianov.kiasoulevplus2.Data

/**
 * Один знімок лічильників у момент часу.
 *
 * [elapsedMs] — монотонний час від початку поїздки, а не годинник: переведення
 * часу або зміна часового поясу не мають зіпсувати розрахунок.
 */
data class TripSample(
    val elapsedMs: Long,
    val odometerKm: Double,
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
        val latest = samples.lastOrNull() ?: return null
        val distance = window.distanceKm ?: return samples.first()

        val from = latest.odometerKm - distance
        // Останній знімок, зроблений до потрібної відмітки пробігу.
        return samples.lastOrNull { it.odometerKm <= from } ?: samples.first()
    }

    /** Чи набралося в історії достатньо пробігу, щоб діапазон був повним. */
    fun covers(window: ConsumptionWindow): Boolean {
        val distance = window.distanceKm ?: return samples.isNotEmpty()
        val latest = samples.lastOrNull() ?: return false
        val earliest = samples.first()
        return latest.odometerKm - earliest.odometerKm >= distance
    }

    companion object {
        /**
         * Опитування йде раз на 800 мс, але знімки з незмінними даними не додаються,
         * тому цього запасу вистачає на кілька годин руху.
         */
        const val MAX_SAMPLES = 5000
    }
}
