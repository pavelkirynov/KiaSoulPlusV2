package com.kirianov.kiasoulevplus2.Data

/**
 * Яким роз'ємом ішла зарядка.
 *
 * Джерело — прапорці кадру 21 01 (байт 11): [BmsData.j1772Plugged] для Type 1 і
 * [BmsData.chademoPlugged] для CHAdeMO. Читаються щосекунди й працюють однаково на
 * змінному та постійному струмі. Тип збирається ЗА ВСЮ СЕСІЮ, а не в мить кінця:
 * бувало, що заряд почали на швидкій, а докінчували на повільній — тоді за сесію
 * засвітилися обидва, і це [BOTH].
 *
 * [UNKNOWN] — зарядку зарахували за приростом заряду без телефона, роз'єму так і не
 * побачили: чесніше сказати «невідомо», ніж вгадати.
 */
enum class ChargeConnector {
    UNKNOWN, TYPE1, CHADEMO, BOTH;

    val label: String
        get() = when (this) {
            TYPE1 -> "Type 1"
            CHADEMO -> "CHAdeMO"
            BOTH -> "Type 1 / CHAdeMO"
            UNKNOWN -> "невідомо"
        }

    companion object {
        fun of(sawType1: Boolean, sawChademo: Boolean): ChargeConnector = when {
            sawType1 && sawChademo -> BOTH
            sawChademo -> CHADEMO
            sawType1 -> TYPE1
            else -> UNKNOWN
        }
    }
}

/**
 * Одна ЗАВЕРШЕНА зарядка в журналі.
 *
 * Раніше застосунок пам'ятав лише «останню» й «за добу»: варто було статися двом
 * зарядкам поспіль — і перша зникала без сліду. Журнал тримає їх поряд, щоб було
 * видно історію: коли, скільки, як довго й чим закрилась.
 *
 * [kwh] — прийнято за пожиттєвим лічильником BMS. [socRise] — приріст заряду у
 * відсоткових пунктах: на цій машині лічильник занижує вдвічі, тож справжні
 * кВт·год рахуються приростом заряду через ємність профілю (див. [energyKwh] і
 * шапку [ChargeLog]).
 *
 * [startedAtMs] нуль означає «початку не бачили»: зарядка, що пройшла без телефона
 * цілком, має тільки кінець. [cause] — чим сесію закрито: щоб через тиждень було
 * ясно, звідки взялося число.
 */
data class ChargeSession(
    val kwh: Double,
    val socRise: Double,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val cause: String,
    val connector: ChargeConnector = ChargeConnector.UNKNOWN,
    /**
     * Ціна цієї сесії, грн/кВт·год — записана в момент закриття (дефолт за
     * роз'ємом або ручне коригування «зараз») і далі змінна лише руками з
     * журналу через [withPrice]. 0.0 означає «ціна невідома», а не «безкоштовно».
     */
    val pricePerKwh: Double = 0.0,
) {
    /** Чи знаємо, коли зарядка почалася: без цього тривалість і середню не порахувати. */
    val hasStart: Boolean get() = startedAtMs > 0L && endedAtMs > startedAtMs

    /** Тривалість зарядки, мс, або 0 — коли початку не бачили. */
    val durationMs: Long get() = if (hasStart) endedAtMs - startedAtMs else 0L

    /** Скільки це кВт·год за тією самою міркою, якою рахується запас ходу. */
    fun energyKwh(capacityKwh: Double): Double =
        if (socRise > 0.0 && capacityKwh > 0.0) socRise / 100.0 * capacityKwh else kwh

    /** Вартість сесії, грн, або 0.0 — коли ціна невідома. */
    fun costUah(capacityKwh: Double): Double =
        if (pricePerKwh > 0.0) energyKwh(capacityKwh) * pricePerKwh else 0.0

    /** Ручна правка ціни з журналу — єдиний спосіб змінити її заднім числом. */
    fun withPrice(newPricePerKwh: Double): ChargeSession = copy(pricePerKwh = newPricePerKwh)

    /**
     * Середня швидкість зарядки, кВт, або null — коли часу початку немає (зарядку
     * побачили лише зранку) і рахувати нема від чого.
     */
    fun averageKw(capacityKwh: Double): Double? {
        if (!hasStart) return null
        val hours = durationMs / 3_600_000.0
        if (hours <= 0.0) return null
        return energyKwh(capacityKwh) / hours
    }
}

/**
 * Що відомо про зарядки.
 *
 * ЧОМУ ЛІЧИЛЬНИК, А НЕ ІНТЕГРАЛ. Витрата й рекуперація рахуються інтегралом
 * миттєвої потужності: на п'ятихвилинному відрізку крок лічильника 0.1 кВт·год
 * дав би до ±47 % помилки, і вчитися на цьому неможливо. Із зарядкою все навпаки:
 * вона триває годинами, тож крок 0.1 кВт·год — це вже соті частки відсотка, а
 * лічильник BMS не залежить ні від частоти опитування, ні від того, чи був
 * телефон узагалі під'єднаний. Заряджання найчастіше й проходить без телефона.
 *
 * [counterBaselineKwh] — останній побачений показ лічильника. Різниця з ним і є
 * прийнята енергія, у тому числі за час, поки застосунок не був підключений.
 *
 * АЛЕ САМ ЛІЧИЛЬНИК НА ЦІЙ МАШИНІ ЗАНИЖУЄ, І ЦЕ ЗМІРЯНО. Нічна зарядка від
 * настінника: 37.87 кВт·год на розетці, +22.3 кВт·год за лічильником BMS, +61.0
 * А·год, заряд виріс на 77.8 %. Числа BMS між собою узгоджені (22.3 / 61.0 = 366 В,
 * рівно напруга пакета), але прив'язані до РІДНОГО пакета: 61 А·год на 77.8 %
 * шкали — це 78 А·год на повну шкалу, тобто паспортні 75 А·год, які ми бачимо вже
 * вчетверте. Перепакований пакет удвічі більший, і лічильник цього не знає.
 *
 * Тому в сесії зберігається ДВА числа: [sessionKwh] за лічильником — як було,
 * і [sessionSocRise], приріст заряду у відсоткових пунктах. Друге переводиться в
 * кВт·год тією самою ємністю, якою рахується запас ходу ([energyOf]), і саме воно
 * зійшлося з настінником: 77.8 % × 44 кВт·год = 34.2, а на розетці 37.87 — різниця
 * рівно на втрати зарядного.
 */
data class ChargeLog(
    /** Завершена зарядка: скільки прийнято за лічильником і коли закінчилася. */
    val lastSessionKwh: Double = 0.0,
    val lastSessionEndedAtMs: Long = 0L,

    /** Скільки заряду вона додала, відсоткових пунктів. */
    val lastSessionSocRise: Double = 0.0,

    /** Зарядка, яка триває зараз: за лічильником і за приростом заряду. */
    val sessionKwh: Double = 0.0,
    val sessionSocRise: Double = 0.0,
    val sessionStartedAtMs: Long = 0L,
    val charging: Boolean = false,

    /** За добу: за лічильником і за приростом заряду. [dayKey] — «рррр-мм-дд». */
    val todayKwh: Double = 0.0,
    val todaySocRise: Double = 0.0,
    val dayKey: String = "",

    val counterBaselineKwh: Double = 0.0,

    /**
     * Лічильник ВІДДАНОЇ енергії, заряд і час у момент, коли брали базовий показ.
     *
     * Разом вони дають змогу зарахувати зарядку, яка пройшла БЕЗ ТЕЛЕФОНА, і не
     * сплутати її з поїздкою: після паузи лічильник прийнятої виріс — але від
     * чого? Зарядка піднімає заряд і нічого не віддає; поїздка навпаки. Обидві
     * перевірки читаються з ТОГО САМОГО кадру, що й лічильник прийнятої, тож
     * їхній вік однаковий.
     *
     * Одометр тут ТЕПЕР Є, і обережність із ним нікуди не поділася. Він приходить
     * іншим кадром і після перепідключення ще показує старе значення — на цьому
     * застосунок один раз уже записав зарядку на 6.7 кВт·год замість поїздки на
     * 51 км. Тому він додається як ТРЕТІЙ свідок, а не замість двох інших: якщо
     * пробіг зріс — це поїздка й ніякі інші умови вже не врятують; якщо не зріс —
     * авто стояло, і вимога «заряд мусить помітно вирости» стає зайвою.
     *
     * Нуль означає «не знаємо»: пробіг у цієї машини завжди більший за нуль.
     */
    val dischargedBaselineKwh: Double = 0.0,
    val socBaselinePercent: Double = 0.0,
    val odometerBaselineKm: Double = 0.0,
    val lastSeenAtMs: Long = 0L,

    /**
     * Які роз'єми засвітилися за ПОТОЧНУ сесію. Збираються, поки зарядка триває, і
     * скидаються на її закритті: тип сесії — це те, що бачили за весь її час, а не
     * в останню мить (заряд могли почати на CHAdeMO, а докінчити на Type 1).
     */
    val sessionSawType1: Boolean = false,
    val sessionSawChademo: Boolean = false,

    /**
     * Чому останню паузу зарахували або не зарахували як зарядку.
     *
     * Не окраса. Двічі підряд зарядку не показувало через різні порогові умови, і
     * обидва рази це виявлялося лише через журнал і зворотний зв'язок. Тепер
     * причина пишеться сама, і наступного разу видно, який саме поріг спрацював.
     */
    val lastDecision: String = "",

    /**
     * Поки базового показу немає, різницю рахувати не з чим. Без цієї познаки
     * перше ж читання дало б «зарядку на 73437 кВт·год»: різницю з нулем.
     */
    val hasBaseline: Boolean = false,

    /**
     * Журнал завершених зарядок, найновіша перша. Обмежений [MAX_SESSIONS]: цікава
     * недавня історія, а не весь життєвий цикл батареї.
     */
    val sessions: List<ChargeSession> = emptyList(),

    /**
     * Ручне коригування ціни ЗАРАЗ ТРИВАЮЧОЇ зарядки, грн/кВт·год; null —
     * дефолтна ціна за роз'єму зі [ChargingPrices] лишається чинною.
     *
     * Живе тут, а не в [ChargeRequest], бо це не одноразова команда, а значення,
     * яке має дожити до закриття сесії — можливо, через кілька читань. Скидається
     * на null щойно сесія закривається (див. [ChargeTracker]) і на початку
     * СПРАВЖНЬОЇ нової сесії, щоб не протекти в наступну зарядку.
     */
    val sessionPriceOverride: Double? = null,

    /**
     * Прохання поправити ціну ВЖЕ ЗАВЕРШЕНОЇ зарядки з журналу; null — прохання
     * немає. Окремо від [request]: те прохання одноразове й без параметрів, а тут
     * потрібні і яку сесію шукати ([PriceEdit.sessionEndedAtMs]), і нову ціну.
     */
    val priceEditRequest: PriceEdit? = null,

    /** Прохання від екрана. Не зберігається: живе рівно до наступного читання. */
    val request: ChargeRequest = ChargeRequest.None,
) {
    val hasLastSession: Boolean get() = lastSessionKwh > 0.0 || lastSessionSocRise > 0.0
    val hasToday: Boolean get() = todayKwh > 0.0 || todaySocRise > 0.0

    /**
     * Додати завершену зарядку в журнал. Кількість не обмежуємо — обмежуємо ВІК:
     * тримаємо зарядки за останні [RETENTION_MS] (≈3 місяці) від найновішої. Так
     * підсумки «за місяць» і трохи ширше завжди мають на чому рахуватися, а файл не
     * росте без кінця. [MAX_SESSIONS] лишається лише страховкою від навали через
     * збиті мітки часу.
     */
    fun withSession(session: ChargeSession): ChargeLog {
        val kept = (listOf(session) + sessions)
            .filter { session.endedAtMs - it.endedAtMs <= RETENTION_MS }
            .take(MAX_SESSIONS)
        return copy(sessions = kept)
    }

    /**
     * Ручна правка ціни ЗАВЕРШЕНОЇ зарядки з журналу.
     *
     * Шукаємо за [ChargeSession.endedAtMs]: у записів немає окремого id, а час
     * закінчення в межах збереженої історії унікальний — дві зарядки не можуть
     * скінчитися в ту саму мілісекунду.
     */
    fun withSessionPrice(endedAtMs: Long, newPricePerKwh: Double): ChargeLog =
        copy(sessions = sessions.map { if (it.endedAtMs == endedAtMs) it.withPrice(newPricePerKwh) else it })

    /**
     * Скільки це кВт·год за тією самою міркою, якою рахується запас ходу.
     *
     * [socRise] — приріст заряду у відсоткових пунктах, [capacityKwh] — корисна
     * ємність пакета з профілю авто. Якщо приросту заряду немає (стара запись або
     * зарядка, за якої шкала не зрушила), лишається [fallbackKwh] з лічильника:
     * заниження краще за прочерк.
     */
    fun energyOf(socRise: Double, capacityKwh: Double, fallbackKwh: Double): Double =
        if (socRise > 0.0 && capacityKwh > 0.0) socRise / 100.0 * capacityKwh else fallbackKwh

    fun lastSessionEnergyKwh(capacityKwh: Double): Double =
        energyOf(lastSessionSocRise, capacityKwh, lastSessionKwh)

    fun sessionEnergyKwh(capacityKwh: Double): Double =
        energyOf(sessionSocRise, capacityKwh, sessionKwh)

    fun todayEnergyKwh(capacityKwh: Double): Double =
        energyOf(todaySocRise, capacityKwh, todayKwh)

    companion object {
        /** Скільки часу тримати зарядку в журналі: ≈3 місяці від найновішої. */
        const val RETENTION_MS = 92L * 24 * 60 * 60 * 1000

        /** Страховка від навали записів через збиті мітки часу; у нормі не діє. */
        const val MAX_SESSIONS = 2_000
    }
}

/**
 * Що екран просить зробити з обліком зарядок.
 *
 * [FinishSession] — «кінець зарядки» вручну: порахувати різницю пожиттєвого
 * лічильника від початку зарядки й закрити сесію. Потрібне тому, що автоматичне
 * визначення кінця тримається на тому, чи опинився телефон в авто вчасно, а
 * зарядка тривала й реальна незалежно від цього.
 */
enum class ChargeRequest { None, FinishSession }

/** Прохання «зміни ціну цій сесії журналу» з екрана. Див. [ChargeLog.priceEditRequest]. */
data class PriceEdit(val sessionEndedAtMs: Long, val newPricePerKwh: Double)
