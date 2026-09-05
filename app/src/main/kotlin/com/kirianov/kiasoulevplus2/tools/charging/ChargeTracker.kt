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
// КІНЕЦЬ ЗАРЯДКИ — ЦЕ СТАН, А НЕ МИТЬ. Момент, коли авто зняли з зарядки, ніхто
// не спостерігає: телефон у цей час у кишені господаря. Зате наступного разу авто
// вмикають — і ось цей СТАН ми бачимо напевно. Для відкритої сесії ввімкнене авто
// й означає «зарядка скінчилась», а вся різниця лічильника від її початку
// належить їй: поки авто стояло, лічильник прийнятої міг рости лише від зарядки.
//
// І остання лінія оборони — кнопка «кінець зарядки». Автоматика колись та й
// проґавить, а людина зарядку бачила.
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
     * Скільки авто їсть із ТЯГОВОЇ батареї, просто стоячи на зарядці, кВт.
     *
     * Це не нуль, і саме на цьому вимір зривався. Поки авто на зарядці, воно
     * живить свою ж електроніку від тягового пакета: борткомп'ютер, BMS,
     * охолодження зарядного. У журналі підзарядка на 40 хвилин дала 0.3 кВт·год
     * по лічильнику ВІДДАНОЇ енергії — і абсолютний поріг у 0.2 її відкинув.
     *
     * Тому поріг не абсолютний, а на годину. За сорок хвилин це пів
     * кіловат-години, за ніч — кілька, і обидва випадки правильні. Число близьке
     * до постійного відбору, який застосунок вивчив сам (близько 0.63 кВт).
     */
    const val PARASITIC_DRAW_KW = 0.8

    /**
     * Мінімальна поблажливість, кВт·год: на коротких паузах працює крок самого
     * лічильника, а не споживання.
     */
    const val MIN_DISCHARGE_ALLOWANCE_KWH = 0.3

    /**
     * Скільки дозволяємо віддати за паузу НЕВІДОМОЇ довжини, кВт·год.
     *
     * Невідома вона рівно в одному випадку: файл обліку старий і часу останнього
     * погляду в ньому немає. Пропорційний до часу поріг тут порахувати нема з
     * чого, а відмовитись від виміру — означає втратити зарядку. Три кВт·год —
     * це більше за будь-який нічний простій і менше за найкоротшу поїздку
     * (51 км у журналі коштували 12 кВт·год), тож поїздку воно все одно відсіє.
     */
    const val UNKNOWN_GAP_ALLOWANCE_KWH = 3.0

    /**
     * Струм, вище якого авто вважається УВІМКНЕНИМ, А.
     *
     * Прямої ознаки запалювання на шині немає, тож беремо єдину, яка є в тому
     * самому кадрі. Коли авто просто стоїть, тяговий струм у журналі не виходить
     * за ±3 А; щойно його вмикають — DC-DC, клімат і привід дають десятки. Шість
     * ампер лежить із запасом між цими двома світами.
     */
    const val IGNITION_CURRENT_A = 6.0

    /**
     * На скільки заряд може просісти за час, поки авто стояло після зарядки, %.
     *
     * Потрібно, щоб відрізнити «зарядка скінчилась, авто ночувало» від «авто
     * поїхало». Простій з'їдає частки відсотка на годину, поїздка — десятки.
     */
    const val MAX_STANDBY_SOC_DROP = 3.0

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
        /** Чи авто зараз увімкнене. Для сесії, що триває, це і є кінець зарядки. */
        ignitionOn: Boolean = false,
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
            val verdict = if (rolled.charging && ignitionOn) {
                ignitionEnded(rolled, step, dischargedKwh, socPercent)
            } else {
                missedCharge(rolled, step, dischargedKwh, socPercent, nowMs)
            }
            return notCharging(
                log = if (verdict.reason.isEmpty()) rolled else rolled.copy(lastDecision = verdict.reason),
                counterKwh = counterKwh,
                dischargedKwh = dischargedKwh,
                socPercent = socPercent,
                nowMs = nowMs,
                missedKwh = verdict.kwh ?: 0.0,
                dayKey = dayKey,
            )
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
     * помітно, заряд ВИРІС, а лічильник відданої не виріс більше, ніж авто могло
     * з'їсти, просто стоячи на зарядці.
     */
    private fun missedCharge(
        log: ChargeLog,
        step: Double,
        dischargedKwh: Double,
        socPercent: Double,
        nowMs: Long,
    ): Verdict {
        // Звичайний хід опитування, а не рішення: писати про нього причину немає
        // сенсу — вона б затерла справжню.
        if (step < MIN_MISSED_KWH) return Verdict(null, "")

        // А ось відмова через надто свіжий останній погляд — уже рішення, і
        // мовчати про неї не можна. Саме вона з'їла нічні 23 кВт·год і не лишила
        // в журналі ані рядка, бо вважалась «не рішенням».
        if (!gapPassed(log, nowMs)) {
            return Verdict(null, "приріст ${round(step)} кВт·год без паузи в спостереженні")
        }

        if (step > MAX_PLAUSIBLE_STEP_KWH) {
            return Verdict(null, "приріст ${round(step)} кВт·год неправдоподібний")
        }

        val rise = socPercent - log.socBaselinePercent
        if (rise < MIN_MISSED_SOC_RISE) {
            return Verdict(null, "заряд не піднявся: ${round(rise)} %")
        }

        val out = dischargedKwh - log.dischargedBaselineKwh
        val allowance = allowedDischarge(log, nowMs)
        if (out > allowance) {
            return Verdict(null, "віддано ${round(out)} при дозволених ${round(allowance)} кВт·год")
        }

        return Verdict(step, "зараховано ${round(step)} кВт·год за паузу")
    }

    /**
     * Зарядку завершило ввімкнення авто.
     *
     * Це той самий випадок, який просив користувач: ловити треба не мить пуску, а
     * СТАН — авто ввімкнене, отже, воно вже не заряджається. І тут ми знаємо
     * більше, ніж у будь-якій іншій паузі: базовий показ узято на початку
     * зарядки, авто відтоді стояло, і зрости лічильник прийнятої міг лише від
     * зарядки — рекуперації без руху не буває.
     *
     * Тому вимоги до зросту заряду тут немає: зарядка могла скінчитись увечері, а
     * авто простояти до ранку й трохи просісти. Лишається одна перевірка — та, що
     * відрізняє простій від поїздки.
     */
    private fun ignitionEnded(
        log: ChargeLog,
        step: Double,
        dischargedKwh: Double,
        socPercent: Double,
    ): Verdict {
        if (step <= 0.0) return Verdict(null, "")
        if (step > MAX_PLAUSIBLE_STEP_KWH) {
            return Verdict(null, "приріст ${round(step)} кВт·год неправдоподібний")
        }

        val drop = log.socBaselinePercent - socPercent
        if (drop > MAX_STANDBY_SOC_DROP) {
            return Verdict(null, "заряд просів на ${round(drop)} % — це поїздка, не зарядка")
        }

        return Verdict(step, "зарядку закрито запалюванням: ${round(step)} кВт·год")
    }

    /**
     * Ручне «кінець зарядки»: користувач сам каже, що зарядка скінчилася.
     *
     * Автоматика все одно інколи проґавить кінець — телефон може не опинитися в
     * авто ні на початку, ні в кінці. Тоді різницю пожиттєвого лічильника з
     * початком зарядки рахуємо без жодних порогів: людина бачила зарядку на
     * власні очі, і це надійніше за будь-яку нашу перевірку.
     */
    fun finishManually(
        log: ChargeLog,
        counterKwh: Double,
        dischargedKwh: Double,
        socPercent: Double,
        nowMs: Long,
        dayKey: String,
    ): ChargeLog {
        if (counterKwh <= 0.0 || !log.hasBaseline) return log
        val rolled = rollDay(log, dayKey)
        val step = (counterKwh - rolled.counterBaselineKwh).coerceAtLeast(0.0)
        val taken = if (step > MAX_PLAUSIBLE_STEP_KWH) 0.0 else step
        val total = rolled.sessionKwh + taken
        if (total <= 0.0) {
            return rolled.copy(lastDecision = "вручну: рахувати нема чого")
        }
        return rolled.copy(
            charging = false,
            lastSessionKwh = total,
            lastSessionEndedAtMs = nowMs,
            todayKwh = rolled.todayKwh + taken,
            dayKey = dayKey,
            sessionKwh = 0.0,
            sessionStartedAtMs = 0L,
            counterBaselineKwh = counterKwh,
            dischargedBaselineKwh = dischargedKwh,
            socBaselinePercent = socPercent,
            lastSeenAtMs = nowMs,
            lastDecision = "вручну зараховано ${round(total)} кВт·год",
        )
    }

    /** Рішення про паузу: скільки зарахувати і чому саме так. */
    private data class Verdict(val kwh: Double?, val reason: String)

    private fun round(value: Double): String = (kotlin.math.round(value * 10.0) / 10.0).toString()

    /**
     * Скільки лічильник відданої енергії міг чесно вирости за паузу, поки авто
     * стояло на зарядці. Пропорційно часу: за сорок хвилин це пів кіловат-години,
     * за ніч — кілька.
     */
    private fun allowedDischarge(log: ChargeLog, nowMs: Long): Double {
        if (log.lastSeenAtMs <= 0L) return UNKNOWN_GAP_ALLOWANCE_KWH
        val hours = (nowMs - log.lastSeenAtMs).coerceAtLeast(0L) / MS_PER_HOUR
        return maxOf(MIN_DISCHARGE_ALLOWANCE_KWH, PARASITIC_DRAW_KW * hours)
    }

    private const val MS_PER_HOUR = 3_600_000.0

    /**
     * Чи була пауза, за яку могла пройти зарядка.
     *
     * Нуль у часі останнього погляду означає не «щойно дивилися», а «не знаємо».
     * Раніше він читався саме як перше, і відмова була мовчазною: у файлі обліку
     * цього поля просто не зберігали, тож після кожного перезапуску воно було
     * нулем — і зарядка, яка пройшла без телефона, гарантовано не зараховувалась.
     * Не знати, коли ми дивилися востаннє, можна лише тоді, коли відтоді
     * застосунок перезапускався: це вже пауза.
     */
    private fun gapPassed(log: ChargeLog, nowMs: Long): Boolean =
        if (log.lastSeenAtMs <= 0L) log.hasBaseline else nowMs - log.lastSeenAtMs >= MISSED_GAP_MS

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
