// ====================================================================================
// ЗБІРКА ВІДРІЗКІВ (SegmentBuilder)
//
// Модель не вчиться на кожному опитуванні: одне значення струму нічого не каже про
// витрату. Знімки накопичуються у **відрізок** — щонайменше три кілометри і п'ять
// хвилин, — і вже цілий відрізок стає одним рядком навчання.
//
// Три речі тут зумовлені реальним темпом опитування, а не зручністю:
//
//  1. Енергія — інтеграл U·I за часом, а не різниця лічильників BMS: у тих
//     роздільність 0.1 кВт·год, це майже кілометр ходу.
//
//  2. Пробіг приходить раз на ~10 секунд і кроком 0.1 км. Тому кінці відрізка
//     прив'язані до моментів, коли лічильник **змінився**, і енергія рахується
//     рівно між тими самими моментами. Різниця двох показів точна, а похибка
//     переїжджає з відстані в час, де вона куди дешевша: десять секунд на п'ятьох
//     хвилинах — це відсотки, а 0.1 км на трьох кілометрах — теж відсотки, але
//     в головній ознаці, де вони не шумлять, а зміщують коефіцієнти.
//
//  3. Опитування раз на ~1 с перебивається вікном монітора на ~1 с кожні ~5 с,
//     тож приблизно п'ятої частини часу струму не видно. Відрізок рахує своє
//     покриття, а дірки обрізає, щоб пауза не стала «безкоштовним» ходом.
//
// Стоянка з увімкненим кліматом — теж відрізок, просто з нульовим пробігом. Саме
// такі відрізки найчистіше показують, скільки їсть сам клімат, і викидати їх було б
// втратою найкращих даних, які взагалі бувають.
//
// Клас накопичує стан, але нічого не знає ні про Android, ні про сховище.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import kotlin.math.abs

/**
 * Один зчитаний момент: усе, що блок прогнозу бачить за одне опитування.
 *
 * [elapsedMs] — монотонний час, а не годинник: переведення часу не має
 * зробити з двох секунд годину.
 *
 * [powerKw] — за домовленістю всього застосунку від'ємна означає розряд.
 *
 * [socPercent] — точний SOC із BMS, [displaySocPercent] — як на панелі.
 */
data class MlSample(
    val elapsedMs: Long,
    val wallClockMs: Long,
    val powerKw: Double,
    val odometerKm: Double? = null,
    val speedKmh: Double? = null,
    val ambientTempC: Double? = null,
    val batteryTempC: Double? = null,
    /** Частка витрати, яку з'їдає клімат: кадр 200 каже це прямо. */
    val climateShare: Double? = null,
    val socPercent: Double? = null,
    val displaySocPercent: Double? = null,
    val charging: Boolean = false,
)

class SegmentBuilder(
    private val minDistanceKm: Double = MIN_DISTANCE_KM,
    private val minDurationMs: Long = MIN_DURATION_MS,
    private val maxDurationMs: Long = MAX_DURATION_MS,
) {

    private var open: Accumulator? = null

    val hasOpenSegment: Boolean get() = open != null

    /**
     * Приймає черговий знімок. Повертає відрізок у той момент, коли він назбирався
     * і пройшов перевірки якості, і null у решті випадків.
     *
     * Відрізок, який охопив би провал у даних (адаптер відвалився, телефон заснув),
     * викидається цілком: пробіг за цей час зріс, а спожита енергія — ні, і такий
     * рядок навчив би модель, що машина їздить задарма.
     */
    /**
     * Скільки відрізків не дожило до кінця і чому. Не для краси: вимогу «3 км і
     * п'ять хвилин без розриву» на нестабільному Bluetooth виконати важко, і без
     * цього числа «модель не вчиться» виглядає загадкою замість діагнозу.
     */
    var aborted: Int = 0
        private set

    var lastAbortReason: String = ""
        private set

    fun accept(sample: MlSample): MlSegment? {
        val current = open
        if (current == null) {
            open = Accumulator(sample)
            return null
        }

        val dtMs = sample.elapsedMs - current.lastElapsedMs
        // Той самий знімок ще раз: сховище оновилося з іншої причини.
        if (dtMs <= 0L) return null

        // Довга дірка або зміна режиму «їдемо / заряджаємось»: починаємо заново.
        if (dtMs > ABORT_GAP_MS) {
            abort("дірка ${dtMs / 1000} с у даних")
            open = Accumulator(sample)
            return null
        }
        if (sample.charging != current.charging) {
            abort("перемикання їзда/зарядка")
            open = Accumulator(sample)
            return null
        }

        current.add(sample, dtMs)

        if (!current.isFull(minDistanceKm, minDurationMs, maxDurationMs)) return null

        val segment = current.build()
        // Наступний відрізок починається там, де скінчився цей, без розриву.
        open = Accumulator(sample)

        if (segment == null) {
            abort("відрізок без пройденої відстані")
            return null
        }
        if (!isUsable(segment)) {
            abort(unusableReason(segment))
            return null
        }
        return segment
    }

    private fun abort(reason: String) {
        aborted++
        lastAbortReason = reason
    }

    /** Чому саме відрізок не годиться: назва причини потрібна на екрані. */
    private fun unusableReason(segment: MlSegment): String = when {
        segment.durationMs <= 0L -> "нульова тривалість"
        segment.coverage < MIN_COVERAGE ->
            "покриття ${(segment.coverage * 100).toInt()} % замість ${(MIN_COVERAGE * 100).toInt()}"
        !segment.energyKwh.isFinite() -> "енергія не число"
        isDownhill(segment) -> "спуск: за ${fmt(segment.distanceKm)} км батарея поповнилась"
        else -> "мало зразків швидкості: ${segment.speedSamples} з $MIN_SPEED_SAMPLES"
    }

    private fun fmt(value: Double): String = (kotlin.math.round(value * 10.0) / 10.0).toString()

    /**
     * Відрізок, на якому авто їхало, а батарея поповнилась. Довгий спуск.
     *
     * ЦЕ ЧЕСНІ ДАНІ, І ВСЕ ОДНО ЇХ НЕ БЕРЕМО — рішення непросте, тож ось міркування
     * цілком. Модель передбачає середню потужність за швидкістю; висоти серед її
     * ознак немає й бути не може — рельєфу на шині ніхто не передає. Тому спуск для
     * неї не «спуск», а «на такій швидкості авто віддає мінус кіловат»: твердження,
     * якого вона не вміє обмежити нічим, і яке тягне всю пряму вниз.
     *
     * Ціна питання виміряна, а не уявна. У журналі за три дні набралося 93 км, і
     * ОДИН такий відрізок — 6.1 км, за які прийшло 0.98 кВт·год, — опускав середню
     * витрату з 15.6 до 13.5 кВт·год/100 км, тобто запас ходу з 320 до 370 км. Одна
     * точка з сімнадцяти рухала відповідь на 15 %.
     *
     * Чим за це платимо, теж треба назвати: підйоми лишаються, спуски зникають, і
     * оцінка стає трохи песимістичнішою за правду. На довгому маршруті висота
     * повертається до початкової, тож на повних поїздках зсуву не буде; він
     * можливий лише там, де людина щодня спускається з гори й піднімається іншою
     * дорогою. Песимізм у запасі ходу коштує дешевше за оптимізм.
     */
    private fun isDownhill(segment: MlSegment): Boolean = Companion.isDownhill(segment)

    /**
     * Забути незакритий відрізок. Викликається на розриві з'єднання: далі знімки
     * підуть із діркою в часі, а відрізок через дірку рахувати не можна.
     */
    /** @return чи був незакритий відрізок, який довелося відкинути. */
    fun reset(): Boolean {
        val had = open != null
        if (had) abort("обрив зв'язку")
        open = null
        return had
    }

    /** Чи годиться відрізок для навчання. */
    private fun isUsable(segment: MlSegment): Boolean {
        if (segment.durationMs <= 0L) return false
        if (segment.coverage < MIN_COVERAGE) return false
        if (!segment.energyKwh.isFinite()) return false
        if (segment.charging) return true
        if (isDownhill(segment)) return false
        return segment.speedSamples >= MIN_SPEED_SAMPLES
    }

    /**
     * Накопичувач одного відрізка.
     *
     * Суми живуть у двох кошиках. У `pending` падає все підряд; у `committed`
     * воно переїжджає лише в момент, коли зрушив лічильник пробігу. Так енергія
     * і моменти швидкості охоплюють **рівно той самий** проміжок, що й пройдена
     * відстань, — інакше чисельник і знаменник витрати рахувалися б за різні
     * інтервали, і модель училася б на зсунутих даних.
     */
    private class Accumulator(first: MlSample) {
        val charging = first.charging
        val startedAtMs = first.wallClockMs

        var lastElapsedMs = first.elapsedMs
            private set

        private val firstElapsedMs = first.elapsedMs
        private var lastSample = first

        private val committed = Bucket()
        private val pending = Bucket()

        private var tickOdometerStart: Double? = null
        private var tickOdometerEnd: Double? = null
        private var tickElapsedStart = 0L
        private var tickElapsedEnd = 0L
        private var lastSeenOdometer: Double? = first.odometerKm

        private var socStart: Double? = first.socPercent
        private var displaySocStart: Double? = first.displaySocPercent
        private var socEnd: Double? = first.socPercent
        private var displaySocEnd: Double? = first.displaySocPercent

        fun add(sample: MlSample, dtMs: Long) {
            // Дірку в даних обрізаємо: за нею машина їхала, але скільки — невідомо,
            // і чесніше зарахувати коротку паузу, ніж вигадати довгу витрату.
            val countedMs = dtMs.coerceAtMost(MAX_STEP_MS)
            pending.add(
                meanPowerKw = (lastSample.powerKw + sample.powerKw) / 2.0,
                speedMps = meanSpeedMps(sample),
                ambientTempC = sample.ambientTempC,
                batteryTempC = sample.batteryTempC,
                climateShare = sample.climateShare,
                countedMs = countedMs,
                elapsedMs = dtMs,
            )

            val odometer = sample.odometerKm
            if (odometer != null && odometerMoved(odometer)) {
                if (tickOdometerStart == null) {
                    // До першого тику відрізка ще не було: усе, що встигло набігти,
                    // належить попередньому проміжку, а не цьому.
                    pending.clear()
                    tickOdometerStart = odometer
                    tickElapsedStart = sample.elapsedMs
                    socStart = sample.socPercent ?: socStart
                    displaySocStart = sample.displaySocPercent ?: displaySocStart
                } else {
                    committed.absorb(pending)
                    // Спорожнити обов'язково: інакше те саме накопичене переїжджало б
                    // у підсумок на кожному наступному тику ще раз.
                    pending.clear()
                    socEnd = sample.socPercent ?: socEnd
                    displaySocEnd = sample.displaySocPercent ?: displaySocEnd
                }
                tickOdometerEnd = odometer
                tickElapsedEnd = sample.elapsedMs
                lastSeenOdometer = odometer
            }

            lastElapsedMs = sample.elapsedMs
            lastSample = sample
        }

        fun isFull(minDistanceKm: Double, minDurationMs: Long, maxDurationMs: Long): Boolean {
            if (elapsedSpanMs() >= maxDurationMs) return true
            return distanceKm() >= minDistanceKm && tickDurationMs() >= minDurationMs
        }

        /**
         * Відрізок із пробігом збирається з `committed` — рівно між тиками лічильника.
         * Відрізок без пробігу (затор, стоянка, заряджання) бере весь свій час:
         * вирівнювати там нема з чим, а сам він і є найчистішим виміром відбору.
         */
        fun build(): MlSegment? {
            val moving = distanceKm() > 0.0 && committed.elapsedMs > 0L
            val bucket = if (moving) committed else Bucket().apply {
                absorb(committed)
                absorb(pending)
            }
            val durationMs = if (moving) tickDurationMs() else elapsedSpanMs()
            if (durationMs <= 0L) return null

            val meanSpeed = bucket.meanSpeed()
            return MlSegment(
                startedAtMs = startedAtMs,
                distanceKm = if (moving) distanceKm() else 0.0,
                durationMs = durationMs,
                energyKwh = bucket.tractionKwh - bucket.regenKwh,
                regenKwh = bucket.regenKwh,
                tractionKwh = bucket.tractionKwh,
                meanSpeedMps = meanSpeed,
                meanSpeedCubedMps = bucket.meanSpeedCubed(),
                speedVarianceMps = (bucket.meanSpeedSquared() - meanSpeed * meanSpeed).coerceAtLeast(0.0),
                speedSamples = bucket.speedSamples,
                stoppedFraction = bucket.stoppedFraction(),
                coverage = bucket.coverage(),
                ambientTempC = bucket.meanAmbient(),
                batteryTempC = bucket.meanBattery(),
                climateShare = bucket.meanClimateShare(),
                socStartPercent = socStart,
                socEndPercent = if (moving) socEnd else lastSample.socPercent,
                displaySocStartPercent = displaySocStart,
                displaySocEndPercent = if (moving) displaySocEnd else lastSample.displaySocPercent,
                charging = charging,
            )
        }

        /**
         * Пробіг лічильника не може зменшуватися: від'ємна різниця — це збій читання,
         * а не задній хід, і краще віддати нуль, ніж від'ємну відстань.
         */
        private fun distanceKm(): Double {
            val from = tickOdometerStart ?: return 0.0
            val to = tickOdometerEnd ?: return 0.0
            return (to - from).coerceAtLeast(0.0)
        }

        /** Час між першим і останнім тиком — рівно той, за який пройдено distanceKm. */
        private fun tickDurationMs(): Long = (tickElapsedEnd - tickElapsedStart).coerceAtLeast(0L)

        private fun elapsedSpanMs(): Long = (lastElapsedMs - firstElapsedMs).coerceAtLeast(0L)

        private fun odometerMoved(odometer: Double): Boolean {
            val previous = lastSeenOdometer ?: return true
            return abs(odometer - previous) > ODOMETER_TICK_KM / 2.0
        }

        private fun meanSpeedMps(sample: MlSample): Double? {
            val before = lastSample.speedKmh
            val now = sample.speedKmh
            val mean = when {
                before != null && now != null -> (before + now) / 2.0
                else -> now ?: before ?: return null
            }
            return mean / KMH_TO_MPS
        }
    }

    /** Суми за проміжок. Усі середні зважені за часом: знімки приходять нерівно. */
    private class Bucket {
        var tractionKwh = 0.0; private set
        var regenKwh = 0.0; private set
        var speedSamples = 0; private set
        var elapsedMs = 0L; private set

        private var measuredMs = 0L
        private var speedMs = 0L
        private var stoppedMs = 0L
        private var sumSpeed = 0.0
        private var sumSpeedSquared = 0.0
        private var sumSpeedCubed = 0.0
        private var sumAmbient = 0.0
        private var ambientMs = 0L
        private var sumBattery = 0.0
        private var batteryMs = 0L
        private var sumClimate = 0.0
        private var climateMs = 0L

        fun add(
            meanPowerKw: Double,
            speedMps: Double?,
            ambientTempC: Double?,
            batteryTempC: Double?,
            climateShare: Double?,
            countedMs: Long,
            elapsedMs: Long,
        ) {
            val dtHours = countedMs / MS_PER_HOUR
            // Розряд у застосунку від'ємний, а витрата має бути додатною.
            val spent = -meanPowerKw * dtHours
            if (spent >= 0.0) tractionKwh += spent else regenKwh += -spent

            if (speedMps != null) {
                sumSpeed += speedMps * countedMs
                sumSpeedSquared += speedMps * speedMps * countedMs
                sumSpeedCubed += speedMps * speedMps * speedMps * countedMs
                speedMs += countedMs
                speedSamples++
                if (speedMps < STOPPED_SPEED_MPS) stoppedMs += countedMs
            }

            ambientTempC?.let {
                sumAmbient += it * countedMs
                ambientMs += countedMs
            }
            batteryTempC?.let {
                sumBattery += it * countedMs
                batteryMs += countedMs
            }
            climateShare?.let {
                sumClimate += it * countedMs
                climateMs += countedMs
            }

            measuredMs += countedMs
            this.elapsedMs += elapsedMs
        }

        fun absorb(other: Bucket) {
            tractionKwh += other.tractionKwh
            regenKwh += other.regenKwh
            speedSamples += other.speedSamples
            elapsedMs += other.elapsedMs
            measuredMs += other.measuredMs
            speedMs += other.speedMs
            stoppedMs += other.stoppedMs
            sumSpeed += other.sumSpeed
            sumSpeedSquared += other.sumSpeedSquared
            sumSpeedCubed += other.sumSpeedCubed
            sumAmbient += other.sumAmbient
            ambientMs += other.ambientMs
            sumBattery += other.sumBattery
            batteryMs += other.batteryMs
            sumClimate += other.sumClimate
            climateMs += other.climateMs
        }

        fun clear() {
            tractionKwh = 0.0
            regenKwh = 0.0
            speedSamples = 0
            elapsedMs = 0L
            measuredMs = 0L
            speedMs = 0L
            stoppedMs = 0L
            sumSpeed = 0.0
            sumSpeedSquared = 0.0
            sumSpeedCubed = 0.0
            sumAmbient = 0.0
            ambientMs = 0L
            sumBattery = 0.0
            batteryMs = 0L
            sumClimate = 0.0
            climateMs = 0L
        }

        private val speedWeight: Double get() = speedMs.toDouble().coerceAtLeast(1.0)

        fun meanSpeed(): Double = sumSpeed / speedWeight

        fun meanSpeedSquared(): Double = sumSpeedSquared / speedWeight

        fun meanSpeedCubed(): Double = sumSpeedCubed / speedWeight

        fun stoppedFraction(): Double = stoppedMs.toDouble() / speedWeight

        fun coverage(): Double = if (elapsedMs > 0L) measuredMs.toDouble() / elapsedMs else 1.0

        fun meanAmbient(): Double? = if (ambientMs > 0L) sumAmbient / ambientMs else null

        fun meanBattery(): Double? = if (batteryMs > 0L) sumBattery / batteryMs else null

        fun meanClimateShare(): Double? = if (climateMs > 0L) sumClimate / climateMs else null
    }

    companion object {

        /**
         * Чи відрізок годиться в науку — перевірка, яка потрібна ДВІЧІ.
         *
         * Один раз тут, коли відрізок щойно зібрався. І другий — коли модель
         * піднімається з журналу: у файлі лежать відрізки, записані ще старими
         * правилами, і перебудова мусить судити їх новими. Без цього нове правило
         * діяло б лише на майбутнє, а вже завчена дурниця лишалася б назавжди.
         *
         * Тут тільки те, що можна сказати про відрізок сам по собі. Покриття й
         * зразки швидкості перевіряються на місці збірки: у журналі вони вже
         * пройшли ту перевірку.
         */
        fun isLearnable(segment: MlSegment): Boolean = !isDownhill(segment)

        internal fun isDownhill(segment: MlSegment): Boolean =
            segment.distanceKm > 0.0 && segment.energyKwh <= 0.0

        /**
         * Три кілометри. Пробіг приходить кроком 0.1 км раз на ~10 с: на коротшому
         * відрізку невизначеність краю з'їдає корисний сигнал, а саме пробіг —
         * головна ознака, і похибка в ній зміщує коефіцієнти, а не просто шумить.
         */
        const val MIN_DISTANCE_KM = 3.0

        /** П'ять хвилин: за такий час невизначеність краю в ±10 с важить кілька відсотків. */
        const val MIN_DURATION_MS = 300_000L

        /** П'ятнадцять хвилин: стільки чекаємо, поки набереться пробіг, і закриваємо як є. */
        const val MAX_DURATION_MS = 900_000L

        /**
         * Пауза, довша за це, — уже не крок опитування, а провал: далі відрізок
         * починається заново. Вікно монітора займає близько секунди, ISO-TP кадр —
         * до третини, тож звичайний крок не перевищує кількох секунд.
         */
        const val ABORT_GAP_MS = 8_000L

        /** Довший крок зараховуємо як цей: дірку не можна інтегрувати чесно. */
        const val MAX_STEP_MS = 3_000L

        /** Менше покриття замірами — і енергія відрізка вже вгадана, а не виміряна. */
        const val MIN_COVERAGE = 0.8

        /** Крок лічильника пробігу, км. */
        const val ODOMETER_TICK_KM = 0.1

        /** Нижче цієї швидкості вважаємо, що стоїмо. */
        const val STOPPED_SPEED_MPS = 0.3

        /** Менше замірів швидкості — і моменти v³ вже нічого не описують. */
        const val MIN_SPEED_SAMPLES = 8

        private const val MS_PER_HOUR = 3_600_000.0
        private const val KMH_TO_MPS = 3.6
    }
}
