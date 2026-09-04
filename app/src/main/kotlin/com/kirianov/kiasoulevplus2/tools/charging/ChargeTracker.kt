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
// Розрізнити зарядку й поїздку за час, коли ми не дивилися, дозволяють два числа
// З ТОГО САМОГО КАДРУ, що й лічильник прийнятої енергії:
//
//   • SOC мусить ВИРОСТИ. Зарядка піднімає заряд — це і є її означення.
//   • Лічильник ВІДДАНОЇ енергії мусить стояти. Зарядка нічого не віддає.
//
// Одометра тут навмисно немає, і це виправлення дорогої помилки. Одометр приходить
// іншим кадром — широкомовним 4F0 через монітор, раз на кілька секунд, — і після
// перепідключення він ще деякий час показує СТАРЕ значення, поки лічильники вже
// нові. У журналі це виглядало так: авто за годину проїхало 51 км, застосунок
// повернувся, побачив «лічильник +6.7 кВт·год, одометр не зрушив» — і записав
// зарядку на 6.7 кВт·год, якої не було. SOC при цьому впав із 43 % до 23 %, а
// лічильник відданої виріс на 12 кВт·год: обидва числа кричали «це поїздка».
//
// Мораль загальна: порівнювати можна лише величини з одного кадру. Величини з
// різних кадрів після паузи мають різний вік, і різниця між ними означає не те,
// що здається.
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

    /**
     * На скільки має вирости SOC, щоб повірити в зарядку. Два відсотки — це вже
     * помітно більше за похибку читання, і водночас менше за найкоротшу
     * осмислену зарядку.
     */
    const val MIN_MISSED_SOC_RISE = 2.0

    /**
     * Скільки дозволено вирости лічильнику ВІДДАНОЇ енергії за паузу, щоб пауза
     * все ще вважалася зарядкою. Практично нуль: під час зарядки авто нічого не
     * віддає, а десята частка — це крок самого лічильника.
     */
    const val MAX_MISSED_DISCHARGE_KWH = 0.2

    /**
     * @param counterKwh пожиттєвий лічильник прийнятої енергії, кВт·год.
     * @param dischargedKwh пожиттєвий лічильник ВІДДАНОЇ енергії з того самого кадру.
     * @param socPercent заряд із того самого кадру, %.
     * @param isCharging чи бачимо зарядку прямо зараз (кадр 581).
     * @param dayKey «рррр-мм-дд» за годинником телефона.
     */
    fun observe(
        log: ChargeLog,
        counterKwh: Double,
        dischargedKwh: Double,
        socPercent: Double,
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
                dischargedBaselineKwh = dischargedKwh,
                socBaselinePercent = socPercent,
                lastSeenAtMs = nowMs,
                hasBaseline = true,
                charging = isCharging,
            )
        }

        val rolled = rollDay(log, dayKey)
        val step = counterKwh - rolled.counterBaselineKwh

        if (!isCharging) {
            val missed = missedCharge(rolled, step, dischargedKwh, socPercent, nowMs)
            return notCharging(rolled, counterKwh, dischargedKwh, socPercent, nowMs, missed ?: 0.0, dayKey)
        }

        // Щойно почалася зарядка: беремо новий базовий показ і НЕ нараховуємо цю
        // різницю — у ній сидить рекуперація за поїздку до зарядки.
        if (!rolled.charging) return started(rolled, counterKwh, dischargedKwh, socPercent, nowMs)

        // Лічильник не може зменшитись. Якщо зменшився — перед нами інша батарея
        // або хибне читання: беремо новий базовий показ, нічого не нараховуючи.
        if (step < 0.0 || step > MAX_PLAUSIBLE_STEP_KWH) {
            return rolled.copy(
                counterBaselineKwh = counterKwh,
                dischargedBaselineKwh = dischargedKwh,
                socBaselinePercent = socPercent,
                lastSeenAtMs = nowMs,
            )
        }
        if (step == 0.0) return rolled.copy(lastSeenAtMs = nowMs)

        return rolled.copy(
            counterBaselineKwh = counterKwh,
            dischargedBaselineKwh = dischargedKwh,
            socBaselinePercent = socPercent,
            lastSeenAtMs = nowMs,
            sessionKwh = rolled.sessionKwh + step,
            todayKwh = rolled.todayKwh + step,
            dayKey = dayKey,
        )
    }

    /**
     * Скільки прийшло за час, поки ми не дивилися, або null, якщо цього
     * стверджувати не можна.
     *
     * ЦЕ ПРАЦЮЄ В ОБОХ ВИПАДКАХ, і саме тут була дірка. Раніше перший рядок
     * методу казав «якщо ми лишили сесію відкритою — не рахувати нічого», і через
     * це найзвичайніший сценарій із життя не рахувався взагалі:
     *
     *     ввечері приїхав, поставив на зарядку, застосунок побачив початок і
     *     встиг записати 0.1 кВт·год — телефон пішов з машиною наодинці —
     *     вранці під'єднався, а там ті самі 0.1.
     *
     * Уся ніч зникала. Хоча саме тут ми знаємо БІЛЬШЕ, ніж будь-коли: зарядка
     * почалася на наших очах. Різниця лічильника за ніч належить тій сесії, і
     * рахувати її треба тим охочіше, а не менш охоче.
     *
     * Умови лишилися ті самі, і всі — з одного кадру: була пауза, за неї набігло
     * помітно, заряд ВИРІС, а лічильник відданої стояв.
     */
    private fun missedCharge(
        log: ChargeLog,
        step: Double,
        dischargedKwh: Double,
        socPercent: Double,
        nowMs: Long,
    ): Double? {
        if (!gapPassed(log, nowMs)) return null
        if (step < MIN_MISSED_KWH || step > MAX_PLAUSIBLE_STEP_KWH) return null
        if (socPercent - log.socBaselinePercent < MIN_MISSED_SOC_RISE) return null
        if (dischargedKwh - log.dischargedBaselineKwh > MAX_MISSED_DISCHARGE_KWH) return null
        return step
    }

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
    private fun started(
        log: ChargeLog,
        counterKwh: Double,
        dischargedKwh: Double,
        socPercent: Double,
        nowMs: Long,
    ): ChargeLog {
        val continuing = nowMs - log.lastSessionEndedAtMs < SESSION_GAP_MS && log.lastSessionEndedAtMs > 0L
        return log.copy(
            counterBaselineKwh = counterKwh,
            dischargedBaselineKwh = dischargedKwh,
            socBaselinePercent = socPercent,
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
        dischargedKwh: Double,
        socPercent: Double,
        nowMs: Long,
        missedKwh: Double,
        dayKey: String,
    ): ChargeLog {
        val closed = when {
            // Сесія була відкрита. Усе, що набігло за час нашої відсутності,
            // належить саме їй: зарядка почалася на наших очах, і лічильник
            // виріс, поки авто стояло. Без цього доданку в сесію потрапляло лише
            // те, що ми встигли побачити живцем, — а це перші секунди.
            log.charging -> log.copy(
                charging = false,
                lastSessionKwh = log.sessionKwh + missedKwh,
                lastSessionEndedAtMs = nowMs,
                todayKwh = log.todayKwh + missedKwh,
                dayKey = dayKey,
                sessionKwh = 0.0,
                sessionStartedAtMs = 0L,
            )
            // Зарядка пройшла без нас цілком: записуємо її як завершену. Часу
            // початку ми не знаємо — знаємо тільки, що вона скінчилася не пізніше,
            // ніж ми подивилися знову.
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
            dischargedBaselineKwh = dischargedKwh,
            socBaselinePercent = socPercent,
            lastSeenAtMs = nowMs,
        )
    }

    /** Нова доба — новий добовий підсумок. Решта лічильників доби не знає. */
    private fun rollDay(log: ChargeLog, dayKey: String): ChargeLog =
        if (log.dayKey == dayKey) log else log.copy(todayKwh = 0.0, dayKey = dayKey)
}
