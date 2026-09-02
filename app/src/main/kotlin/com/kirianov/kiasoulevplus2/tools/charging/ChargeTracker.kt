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
     * @param counterKwh пожиттєвий лічильник прийнятої енергії, кВт·год.
     * @param isCharging чи бачимо зарядку прямо зараз (кадр 581).
     * @param dayKey «рррр-мм-дд» за годинником телефона.
     */
    fun observe(
        log: ChargeLog,
        counterKwh: Double,
        isCharging: Boolean,
        nowMs: Long,
        dayKey: String,
    ): ChargeLog {
        if (counterKwh <= 0.0) return log

        // Перше читання: лише запам'ятовуємо, з чим порівнювати. Порахувати різницю
        // з нуля означало б записати всю історію батареї як одну зарядку.
        if (!log.hasBaseline) {
            return log.copy(counterBaselineKwh = counterKwh, hasBaseline = true, charging = isCharging)
        }

        val rolled = rollDay(log, dayKey)

        // Авто не заряджається: лічильник усе одно міг вирости від рекуперації.
        // Базовий показ веземо за ним, але в сесію не нараховуємо нічого.
        if (!isCharging) return notCharging(rolled, counterKwh, nowMs)

        // Щойно почалася зарядка: беремо новий базовий показ і НЕ нараховуємо цю
        // різницю — у ній сидить рекуперація за поїздку до зарядки.
        if (!rolled.charging) return started(rolled, counterKwh, nowMs)

        val step = counterKwh - rolled.counterBaselineKwh

        // Лічильник не може зменшитись. Якщо зменшився — перед нами інша батарея
        // або хибне читання: беремо новий базовий показ, нічого не нараховуючи.
        if (step < 0.0 || step > MAX_PLAUSIBLE_STEP_KWH) {
            return rolled.copy(counterBaselineKwh = counterKwh)
        }
        if (step == 0.0) return rolled

        return rolled.copy(
            counterBaselineKwh = counterKwh,
            sessionKwh = rolled.sessionKwh + step,
            todayKwh = rolled.todayKwh + step,
            dayKey = dayKey,
        )
    }

    /**
     * Зарядка почалася. Базовий показ переїжджає на поточний: усе, що набігло до
     * цього моменту, до зарядки не належить.
     *
     * Якщо телефон під'єднався посеред зарядки, у сесію потрапить лише та частина,
     * яку ми бачили. Це свідома недооцінка: приписати сесії рекуперацію попередньої
     * поїздки було б гірше за недорахований кілловат-годину.
     */
    private fun started(log: ChargeLog, counterKwh: Double, nowMs: Long): ChargeLog {
        val continuing = nowMs - log.lastSessionEndedAtMs < SESSION_GAP_MS && log.lastSessionEndedAtMs > 0L
        return log.copy(
            counterBaselineKwh = counterKwh,
            charging = true,
            sessionKwh = if (continuing) log.sessionKwh else 0.0,
            sessionStartedAtMs = if (continuing && log.sessionStartedAtMs > 0L) log.sessionStartedAtMs else nowMs,
        )
    }

    /**
     * Авто не заряджається. Лічильник тут росте від рекуперації, тож базовий показ
     * тягнемо за ним — інакше вся рекуперація за поїздку впала б у наступну сесію.
     */
    private fun notCharging(log: ChargeLog, counterKwh: Double, nowMs: Long): ChargeLog {
        val closed = if (log.charging) {
            log.copy(
                charging = false,
                lastSessionKwh = log.sessionKwh,
                lastSessionEndedAtMs = nowMs,
                sessionKwh = 0.0,
                sessionStartedAtMs = 0L,
            )
        } else {
            log
        }

        return if (counterKwh == closed.counterBaselineKwh) closed else closed.copy(counterBaselineKwh = counterKwh)
    }

    /** Нова доба — новий добовий підсумок. Решта лічильників доби не знає. */
    private fun rollDay(log: ChargeLog, dayKey: String): ChargeLog =
        if (log.dayKey == dayKey) log else log.copy(todayKwh = 0.0, dayKey = dayKey)
}
