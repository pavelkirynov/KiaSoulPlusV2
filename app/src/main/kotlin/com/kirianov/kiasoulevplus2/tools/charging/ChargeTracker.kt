// ====================================================================================
// ОБЛІК ЗАРЯДОК ЗА ПОЖИТТЄВИМ ЛІЧИЛЬНИКОМ (ChargeTracker)
//
// Витрата й рекуперація лишаються на інтегралі миттєвої потужності — там крок
// лічильника 0.1 кВт·год завеликий. Зарядка ж триває годинами, тож лічильник BMS
// для неї точніший за будь-яке інтегрування.
//
// ГОЛОВНА ПАСТКА, НА ЯКІЙ ЦЕЙ КОД УЖЕ ОДНОГО РАЗУ ЗЛАМАВСЯ. Лічильник прийнятої
// енергії росте від УСЬОГО, що входить у батарею, — у тому числі від рекуперації.
// Тому «лічильник виріс» не означає «зарядка»: у місті він росте на кожному
// гальмуванні, і спуск з гори виглядав як зарядка на кілька кВт·год.
//
// Через це нараховуємо ЛИШЕ поки авто саме каже, що заряджається (кадр 581), і на
// вході в зарядку беремо новий базовий показ. Друге не менш важливе за перше: без
// нього перша ж різниця затягнула б у сесію всю рекуперацію, набігшу за поїздку
// до неї.
//
// ЗАРЯДКА БЕЗ ТЕЛЕФОНА. Найчастіше авто заряджається вночі, коли телефона в ньому
// немає, — і цього ознака 581 не бачить узагалі. Раніше така зарядка просто
// зникала: на ранок застосунок бачив вирослий лічильник, тягнув за ним базовий
// показ і нараховував тільки той хвостик, який застав живцем. Нічні 38 кВт·год
// перетворювалися на 0.7.
//
// Розрізнити зарядку й рекуперацію за час, коли ми не дивилися, дозволяє ОДОМЕТР.
// Рекуперація можлива тільки на ходу: щоб віддати енергію в батарею, авто мусить
// рухатися. Тож якщо між двома спостереженнями минули години, одометр не зрушив,
// а лічильник виріс — це зарядка, іншого джерела просто немає. Якщо ж одометр
// зрушив, розділити зарядку й рекуперацію нічим, і тоді ми чесно не зараховуємо
// нічого: недорахувати краще, ніж приписати спуск з гори до зарядки.
//
// Чистий об'єкт без стану: увесь стан приходить аргументом і повертається назад.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog

object ChargeTracker {

    /**
     * Пауза, після якої зарядка вважається завершеною, а наступний прирост —
     * новою сесією. Годину взято тому, що зарядка від побутової розетки інколи
     * пригальмовує на десятки хвилин, і рвати сесію на кожній паузі не варто.
     */
    const val SESSION_GAP_MS = 60 * 60 * 1000L

    /**
     * Більший прирост за одне читання неправдоподібний: це або підміна BMS, або
     * ми прочитали не ті байти. Найшвидша зарядка Soul EV — близько 70 кВт, тобто
     * навіть за годину простою зв'язку більше цього не набігає.
     */
    const val MAX_PLAUSIBLE_STEP_KWH = 100.0

    /**
     * Скільки щонайменше має набігти за час нашої відсутності, щоб вважати це
     * зарядкою. Пів кіловат-години — це вже пів години від побутової розетки;
     * менше може набігти від службових потреб самої батареї.
     */
    const val MIN_MISSED_KWH = 0.5

    /**
     * Скільки має тривати пауза в спостереженнях, щоб узагалі шукати пропущену
     * зарядку. Поки телефон дивиться, лічильник іде дрібними кроками, і кожен з
     * них ми бачимо; стрибок можливий лише тоді, коли ми не дивилися.
     */
    const val MISSED_GAP_MS = 10 * 60 * 1000L

    /** Один крок одометра. Менше — авто стояло. */
    const val MOVED_TOLERANCE_KM = 0.15

    /**
     * @param counterKwh пожиттєвий лічильник прийнятої енергії, кВт·год.
     * @param odometerKm показ одометра; 0 або менше означає «ще не знаємо».
     * @param isCharging чи бачимо зарядку прямо зараз (кадр 581).
     * @param dayKey «рррр-мм-дд» за годинником телефона.
     */
    fun observe(
        log: ChargeLog,
        counterKwh: Double,
        odometerKm: Double,
        isCharging: Boolean,
        nowMs: Long,
        dayKey: String,
    ): ChargeLog {
        if (counterKwh <= 0.0) return log

        // Перше читання: лише запам'ятовуємо, з чим порівнювати. Порахувати різницю
        // з нуля означало б записати всю історію батареї як одну зарядку.
        if (!log.hasBaseline) {
            return log.copy(
                counterBaselineKwh = counterKwh,
                odometerBaselineKm = odometerKm,
                lastSeenAtMs = nowMs,
                hasBaseline = true,
                charging = isCharging,
            )
        }

        val rolled = rollDay(log, dayKey)
        val step = counterKwh - rolled.counterBaselineKwh

        if (!isCharging) {
            val missed = missedCharge(rolled, step, odometerKm, nowMs)
            if (missed == null && awaitingOdometer(rolled, step, odometerKm, nowMs)) {
                // Лічильник виріс за час нашої відсутності, а одометра ще немає:
                // кадр 4F0 приходить не одразу. Вирішувати зарано, і головне —
                // не можна тягнути базовий показ, бо разом з ним поїде й уся
                // пропущена зарядка. Чекаємо наступного читання.
                return log
            }
            return notCharging(rolled, counterKwh, odometerKm, nowMs, missed ?: 0.0, dayKey)
        }

        // Щойно почалася зарядка: беремо новий базовий показ і НЕ нараховуємо цю
        // різницю — у ній сидить рекуперація за поїздку до зарядки.
        if (!rolled.charging) return started(rolled, counterKwh, odometerKm, nowMs)

        // Лічильник не може зменшитись. Якщо зменшився — перед нами інша батарея
        // або хибне читання: беремо новий базовий показ, нічого не нараховуючи.
        if (step < 0.0 || step > MAX_PLAUSIBLE_STEP_KWH) {
            return rolled.copy(counterBaselineKwh = counterKwh, odometerBaselineKm = odometerKm, lastSeenAtMs = nowMs)
        }
        if (step == 0.0) return rolled.copy(lastSeenAtMs = nowMs)

        return rolled.copy(
            counterBaselineKwh = counterKwh,
            odometerBaselineKm = odometerKm,
            lastSeenAtMs = nowMs,
            sessionKwh = rolled.sessionKwh + step,
            todayKwh = rolled.todayKwh + step,
            dayKey = dayKey,
        )
    }

    /**
     * Скільки прийшло із зарядки, якої ми не бачили, або null, якщо цього
     * стверджувати не можна.
     *
     * Три умови мусять справдитися разом: була пауза в спостереженнях, за неї
     * набігло помітно, і авто за цей час не зрушило з місця.
     */
    private fun missedCharge(log: ChargeLog, step: Double, odometerKm: Double, nowMs: Long): Double? {
        if (log.charging) return null
        if (!gapPassed(log, nowMs)) return null
        if (step < MIN_MISSED_KWH || step > MAX_PLAUSIBLE_STEP_KWH) return null
        if (odometerKm <= 0.0 || log.odometerBaselineKm <= 0.0) return null
        if (odometerKm - log.odometerBaselineKm >= MOVED_TOLERANCE_KM) return null
        return step
    }

    private fun awaitingOdometer(log: ChargeLog, step: Double, odometerKm: Double, nowMs: Long): Boolean =
        !log.charging &&
            gapPassed(log, nowMs) &&
            step >= MIN_MISSED_KWH &&
            step <= MAX_PLAUSIBLE_STEP_KWH &&
            (odometerKm <= 0.0 || log.odometerBaselineKm <= 0.0)

    private fun gapPassed(log: ChargeLog, nowMs: Long): Boolean =
        log.lastSeenAtMs > 0L && nowMs - log.lastSeenAtMs >= MISSED_GAP_MS

    /**
     * Зарядка почалася. Базовий показ переїжджає на поточний: усе, що набігло до
     * цього моменту, до зарядки не належить.
     *
     * Якщо телефон під'єднався посеред зарядки, у сесію потрапить лише та частина,
     * яку ми бачили. Це свідома недооцінка: приписати сесії рекуперацію попередньої
     * поїздки було б гірше за недорахований кілловат-годину.
     */
    private fun started(log: ChargeLog, counterKwh: Double, odometerKm: Double, nowMs: Long): ChargeLog {
        val continuing = nowMs - log.lastSessionEndedAtMs < SESSION_GAP_MS && log.lastSessionEndedAtMs > 0L
        return log.copy(
            counterBaselineKwh = counterKwh,
            odometerBaselineKm = odometerKm,
            lastSeenAtMs = nowMs,
            charging = true,
            sessionKwh = if (continuing) log.sessionKwh else 0.0,
            sessionStartedAtMs = if (continuing && log.sessionStartedAtMs > 0L) log.sessionStartedAtMs else nowMs,
        )
    }

    /**
     * Авто не заряджається. Лічильник тут росте від рекуперації, тож базовий показ
     * тягнемо за ним — інакше вся рекуперація за поїздку впала б у наступну сесію.
     */
    private fun notCharging(
        log: ChargeLog,
        counterKwh: Double,
        odometerKm: Double,
        nowMs: Long,
        missedKwh: Double,
        dayKey: String,
    ): ChargeLog {
        val closed = when {
            log.charging -> log.copy(
                charging = false,
                lastSessionKwh = log.sessionKwh,
                lastSessionEndedAtMs = nowMs,
                sessionKwh = 0.0,
                sessionStartedAtMs = 0L,
            )
            // Зарядка пройшла без нас: записуємо її як завершену. Часу початку ми
            // не знаємо — знаємо тільки, що вона скінчилася не пізніше, ніж ми
            // подивилися знову.
            missedKwh > 0.0 -> log.copy(
                lastSessionKwh = missedKwh,
                lastSessionEndedAtMs = nowMs,
                todayKwh = log.todayKwh + missedKwh,
                dayKey = dayKey,
            )
            else -> log
        }

        return closed.copy(
            counterBaselineKwh = counterKwh,
            odometerBaselineKm = odometerKm,
            lastSeenAtMs = nowMs,
        )
    }

    /** Нова доба — новий добовий підсумок. Решта лічильників доби не знає. */
    private fun rollDay(log: ChargeLog, dayKey: String): ChargeLog =
        if (log.dayKey == dayKey) log else log.copy(todayKwh = 0.0, dayKey = dayKey)
}
