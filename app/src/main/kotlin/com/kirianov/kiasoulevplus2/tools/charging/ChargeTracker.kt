// ====================================================================================
// ОБЛІК ЗАРЯДОК ЗА ПОЖИТТЄВИМ ЛІЧИЛЬНИКОМ (ChargeTracker)
//
// Витрата й рекуперація лишаються на інтегралі миттєвої потужності — там крок
// лічильника 0.1 кВт·год завеликий. Зарядка ж триває годинами, тож лічильник BMS
// для неї точніший за будь-яке інтегрування і, головне, не залежить від того, чи
// був телефон під'єднаний: різниця з останнім побаченим показом враховує й те,
// що сталося без нас.
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
        val step = counterKwh - rolled.counterBaselineKwh

        // Лічильник не може зменшитись. Якщо зменшився — перед нами інша батарея
        // або хибне читання: беремо новий базовий показ, нічого не нараховуючи.
        if (step < 0.0 || step > MAX_PLAUSIBLE_STEP_KWH) {
            return rolled.copy(counterBaselineKwh = counterKwh, charging = isCharging)
        }

        if (step == 0.0) return idle(rolled, isCharging, nowMs)

        // Прирост є, отже зарядка. Якщо попередня скінчилася давно — це вже нова.
        val continuing = rolled.charging || nowMs - rolled.lastSessionEndedAtMs < SESSION_GAP_MS
        val startedAt = if (continuing && rolled.sessionStartedAtMs > 0L) rolled.sessionStartedAtMs else nowMs

        return rolled.copy(
            counterBaselineKwh = counterKwh,
            charging = true,
            sessionKwh = if (continuing) rolled.sessionKwh + step else step,
            sessionStartedAtMs = startedAt,
            todayKwh = rolled.todayKwh + step,
            dayKey = dayKey,
        )
    }

    /**
     * Приросту немає. Це або пауза посеред зарядки, або вона вже скінчилася —
     * розрізняє їх ознака заряджання з шини та час від останнього приросту.
     */
    private fun idle(log: ChargeLog, isCharging: Boolean, nowMs: Long): ChargeLog {
        if (!log.charging) return log.copy(charging = isCharging)
        if (isCharging) return log

        // Зарядка щойно скінчилася: переносимо її в «останню» і чистимо поточну.
        return log.copy(
            charging = false,
            lastSessionKwh = log.sessionKwh,
            lastSessionEndedAtMs = nowMs,
            sessionKwh = 0.0,
            sessionStartedAtMs = 0L,
        )
    }

    /** Нова доба — новий добовий підсумок. Решта лічильників доби не знає. */
    private fun rollDay(log: ChargeLog, dayKey: String): ChargeLog =
        if (log.dayKey == dayKey) log else log.copy(todayKwh = 0.0, dayKey = dayKey)
}
