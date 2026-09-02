package com.kirianov.kiasoulevplus2.Data

/**
 * Чи стримав прогноз обіцянку.
 *
 * ЩО САМЕ ТУТ МІРЯЄТЬСЯ. На початку поїздки застосунок сказав «200 км». Проїхали
 * 50, а він каже вже «135». Отже 50 км дороги з'їли 65 км запасу — початкова
 * оцінка була оптимістична на 30 %. Якби прогноз був точний, запас падав би
 * рівно на стільько, скільки проїхано.
 *
 * ЧОМУ ЦЕ, А НЕ ВЛАСНИЙ ПІДРАХУНОК ШЛЯХУ. Спершу тут стояв інтеграл швидкості
 * проти одометра. Але швидкість і одометр приходять ОДНИМ кадром 4F0: якщо є
 * швидкість, є й одометр, тож підмінити його інтегралом ніколи не доведеться.
 * Той підрахунок міряв точність арифметики, яка нікому не потрібна. Це — міряє
 * помилку самої моделі, тобто те, заради чого застосунок і зроблений.
 */
data class RangeAccuracy(
    /** Що обіцяв прогноз на початку відліку, км. */
    val startRangeKm: Double = 0.0,

    /** Що обіцяє зараз, км. */
    val currentRangeKm: Double = 0.0,

    /** Скільки проїхано за одометром від початку відліку, км. */
    val drivenKm: Double = 0.0,

    private val startOdometerKm: Double = 0.0,
    val started: Boolean = false,
) {
    /** На скільки впав обіцяний запас. */
    val predictedDropKm: Double get() = startRangeKm - currentRangeKm

    /**
     * Наскільки запас падав швидше за дорогу. Додатне означає оптимістичний
     * прогноз: обіцяного не проїдеш.
     */
    val errorKm: Double get() = predictedDropKm - drivenKm

    /** Та сама помилка у відсотках від пройденого. */
    val errorPercent: Double?
        get() = if (hasEnoughDistance) errorKm / drivenKm * 100.0 else null

    /**
     * Поки не набралося кілометрів, відсоток нічого не означає: на першому
     * кілометрі похибка одометра в 0.1 км дає вже 10 %.
     */
    val hasEnoughDistance: Boolean get() = started && drivenKm >= MIN_DISTANCE_KM

    /**
     * Починає відлік. Потрібні обидва числа одразу: прогноз без одометра нема з
     * чим порівнювати, одометр без прогнозу — нема що перевіряти.
     */
    fun startedAt(rangeKm: Double, odometerKm: Double): RangeAccuracy =
        if (rangeKm <= 0.0 || odometerKm <= 0.0) {
            this
        } else {
            RangeAccuracy(
                startRangeKm = rangeKm,
                currentRangeKm = rangeKm,
                startOdometerKm = odometerKm,
                started = true,
            )
        }

    fun observe(rangeKm: Double, odometerKm: Double): RangeAccuracy {
        if (!started) return startedAt(rangeKm, odometerKm)
        if (rangeKm <= 0.0 || odometerKm <= 0.0) return this

        return copy(
            currentRangeKm = rangeKm,
            // Одометр не може йти назад; якщо пішов — це хибне читання, не від'ємний шлях.
            drivenKm = (odometerKm - startOdometerKm).coerceAtLeast(0.0),
        )
    }

    companion object {
        /** Менше цього відсоток помилки — це шум одометра, а не якість прогнозу. */
        const val MIN_DISTANCE_KM = 3.0
    }
}
