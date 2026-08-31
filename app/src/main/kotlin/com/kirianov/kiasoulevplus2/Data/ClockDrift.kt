package com.kirianov.kiasoulevplus2.Data

/**
 * Знімок розходження годинника магнітоли з годинником телефона.
 *
 * [elapsedMs] — монотонний час від початку спостереження.
 * [driftSeconds] — на скільки авто попереду телефона; від'ємне означає «відстає».
 */
data class ClockDriftSample(
    val elapsedMs: Long,
    val driftSeconds: Int,
)

/**
 * Історія розходження годинників — і головне, що з неї видно.
 *
 * НАВІЩО ЦЕ ПОТРІБНО. «Час в авто злітає» — це три різні несправності з різним
 * ремонтом, і відрізняються вони саме формою розходження:
 *
 *  - **стрибок** (розходження змінилося різко, за секунди) — годинник хтось
 *    переставив: або GPS віддав підмінений час, або магнітола перезавантажилась;
 *  - **рівномірний хід** (розходження росте лінійно) — кварц RTC у магнітолі йде
 *    не з тією швидкістю; GPS тут ні до чого, і вимикати його безглуздо;
 *  - **скидання після стоянки** — магнітола втрачає постійне живлення (запобіжник
 *    або живлення пам'яті), і час стартує з нуля.
 *
 * Тому історія рахує окремо кількість стрибків і швидкість ходу між ними.
 */
data class ClockDriftHistory(
    val samples: List<ClockDriftSample> = emptyList(),

    /** Скільки разів годинник переставили за час спостереження. */
    val jumpCount: Int = 0,
) {
    /**
     * Додає знімок. Стрибок обриває серію: швидкість ходу, порахована через
     * переставлений годинник, — це не швидкість ходу, а величина стрибка.
     */
    fun plus(sample: ClockDriftSample): ClockDriftHistory {
        val last = samples.lastOrNull() ?: return copy(samples = listOf(sample))

        val elapsed = sample.elapsedMs - last.elapsedMs
        val change = sample.driftSeconds - last.driftSeconds
        val isJump = elapsed in 0..JUMP_WINDOW_MS && kotlin.math.abs(change) >= JUMP_THRESHOLD_SECONDS

        return if (isJump) {
            // Серія починається заново від нового знімка.
            ClockDriftHistory(samples = listOf(sample), jumpCount = jumpCount + 1)
        } else {
            copy(samples = (samples + sample).takeLast(MAX_SAMPLES))
        }
    }

    /** Поточне розходження, секунди. */
    val driftSeconds: Int? get() = samples.lastOrNull()?.driftSeconds

    /**
     * Наскільки годинник авто біжить швидше за телефон, секунд на годину.
     * Додатне — спішить. Null, поки серія коротша за [MIN_SPAN_MS].
     */
    val rateSecondsPerHour: Double?
        get() {
            val first = samples.firstOrNull() ?: return null
            val last = samples.lastOrNull() ?: return null
            val span = last.elapsedMs - first.elapsedMs
            if (span < MIN_SPAN_MS) return null
            return (last.driftSeconds - first.driftSeconds) * MS_PER_HOUR / span
        }

    /** Скільки вже триває поточна серія без стрибків. */
    val spanMs: Long
        get() {
            val first = samples.firstOrNull() ?: return 0
            val last = samples.lastOrNull() ?: return 0
            return last.elapsedMs - first.elapsedMs
        }

    companion object {
        /**
         * Зміна розходження більша за це за короткий час — це переставлений годинник,
         * а не хід кварцу: навіть дуже поганий кварц не набігає пів хвилини за хвилину.
         */
        const val JUMP_THRESHOLD_SECONDS = 30

        /** У межах цього часу різка зміна вважається стрибком. */
        const val JUMP_WINDOW_MS = 60_000L

        /** Коротша серія дає надто грубу оцінку швидкості ходу. */
        const val MIN_SPAN_MS = 120_000L

        const val MAX_SAMPLES = 2000

        private const val MS_PER_HOUR = 3_600_000.0
    }
}
