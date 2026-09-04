package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTrackerTest {

    private val day = "2026-09-01"

    private fun observe(
        log: ChargeLog,
        counter: Double,
        charging: Boolean = true,
        nowMs: Long = 0L,
        dayKey: String = day,
        dischargedKwh: Double = 20_000.0,
        socPercent: Double = 50.0,
    ) = ChargeTracker.observe(log, counter, dischargedKwh, socPercent, charging, nowMs, dayKey)

    /**
     * Найважливіше про пожиттєвий лічильник: перше читання не має стати зарядкою.
     * Різниця з нулем — це вся історія батареї, тобто десятки тисяч кВт·год.
     */
    @Test
    fun `the first reading only records a baseline`() {
        val log = observe(ChargeLog(), counter = 26_937.9, charging = false)

        assertTrue(log.hasBaseline)
        assertEquals(26_937.9, log.counterBaselineKwh, 0.001)
        assertEquals(0.0, log.todayKwh, 0.001)
        assertFalse(log.hasLastSession)
    }

    /**
     * Головна пастка: лічильник росте від УСЬОГО, що входить у батарею, а отже й
     * від рекуперації. Місто з гальмуваннями не має виглядати як зарядка.
     */
    @Test
    fun `regen while driving is not charging`() {
        var log = observe(ChargeLog(), counter = 26_937.9, charging = false)

        // Поїздка з рекуперацією: лічильник виріс на 3 кВт·год, зарядки не було.
        log = observe(log, counter = 26_939.0, charging = false, nowMs = 60_000)
        log = observe(log, counter = 26_940.9, charging = false, nowMs = 120_000)

        assertFalse(log.charging)
        assertEquals("Рекуперацію не мало нарахувати", 0.0, log.todayKwh, 0.001)
        assertEquals(0.0, log.sessionKwh, 0.001)
        // Базовий показ мусить їхати за лічильником, інакше все це впаде в наступну сесію.
        assertEquals(26_940.9, log.counterBaselineKwh, 0.001)
    }

    @Test
    fun `growth while the car reports charging is counted`() {
        var log = observe(ChargeLog(), counter = 26_937.9, charging = false)
        log = observe(log, counter = 26_937.9, charging = true, nowMs = 60_000)
        log = observe(log, counter = 26_940.4, charging = true, nowMs = 120_000)

        assertTrue(log.charging)
        assertEquals(2.5, log.sessionKwh, 0.001)
        assertEquals(2.5, log.todayKwh, 0.001)
    }

    /**
     * Найважливіше після самої пастки: рекуперація, набігла ДО зарядки, не має
     * потрапити в сесію. Тому на вході в зарядку базовий показ переїжджає.
     */
    @Test
    fun `regen before a session does not leak into it`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)

        // Поїздка: рекуперацією набігло 4 кВт·год.
        log = observe(log, counter = 104.0, charging = false, nowMs = 60_000)

        // Приїхали, увімкнули зарядку і взяли 10 кВт·год.
        log = observe(log, counter = 104.0, charging = true, nowMs = 120_000)
        log = observe(log, counter = 114.0, charging = true, nowMs = 180_000)

        assertEquals("У сесію мали потрапити лише 10, а не 14", 10.0, log.sessionKwh, 0.001)
        assertEquals(10.0, log.todayKwh, 0.001)
    }

    @Test
    fun `a session closes when charging stops and becomes the last one`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 100.0, charging = true, nowMs = 30_000)
        log = observe(log, counter = 104.0, nowMs = 60_000)
        log = observe(log, counter = 108.0, nowMs = 120_000)

        // Шина каже, що зарядка скінчилася.
        log = observe(log, counter = 108.0, charging = false, nowMs = 180_000)

        assertFalse(log.charging)
        assertEquals(8.0, log.lastSessionKwh, 0.001)
        assertEquals(180_000L, log.lastSessionEndedAtMs)
        assertEquals(0.0, log.sessionKwh, 0.001)
        assertEquals(8.0, log.todayKwh, 0.001)
    }

    /** Пауза посеред зарядки не має рвати сесію: побутова розетка так і робить. */
    @Test
    fun `a pause while still charging keeps the session open`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 100.0, charging = true, nowMs = 30_000)
        log = observe(log, counter = 104.0, nowMs = 60_000)
        log = observe(log, counter = 104.0, charging = true, nowMs = 120_000)
        log = observe(log, counter = 106.0, charging = true, nowMs = 180_000)

        assertTrue(log.charging)
        assertEquals(6.0, log.sessionKwh, 0.001)
        assertFalse("Сесію не мало закрити", log.hasLastSession)
    }

    /** Після довгої паузи прирост — це вже наступна зарядка, а не продовження. */
    @Test
    fun `after a long gap the growth starts a new session`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 100.0, charging = true, nowMs = 30_000)
        log = observe(log, counter = 108.0, nowMs = 60_000)
        log = observe(log, counter = 108.0, charging = false, nowMs = 120_000)
        assertEquals(8.0, log.lastSessionKwh, 0.001)

        val later = 120_000L + ChargeTracker.SESSION_GAP_MS + 1
        log = observe(log, counter = 108.0, charging = true, nowMs = later)
        log = observe(log, counter = 111.0, charging = true, nowMs = later + 60_000)

        assertEquals("Нова сесія мусить починатися з нуля", 3.0, log.sessionKwh, 0.001)
        assertEquals(later, log.sessionStartedAtMs)
        assertEquals(11.0, log.todayKwh, 0.001)
    }

    @Test
    fun `a new day resets the daily total but not the last session`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 100.0, charging = true, nowMs = 30_000)
        log = observe(log, counter = 108.0, nowMs = 60_000)
        log = observe(log, counter = 108.0, charging = false, nowMs = 120_000)

        log = observe(log, counter = 108.0, charging = true, nowMs = 190_000, dayKey = "2026-09-02")
        log = observe(log, counter = 110.0, nowMs = 200_000, dayKey = "2026-09-02")

        assertEquals("Добовий підсумок мусить початися заново", 2.0, log.todayKwh, 0.001)
        assertEquals("2026-09-02", log.dayKey)
        assertEquals("Остання зарядка добі не належить", 8.0, log.lastSessionKwh, 0.001)
    }

    /**
     * Свідома ціна за правильність: зарядку, що пройшла без телефона, нарахувати
     * не можна. Різницю за час відсутності не відрізнити від рекуперації за
     * поїздку, а приписати поїздку зарядці гірше, ніж не порахувати зарядку.
     */
    @Test
    fun `growth seen after an absence is not attributed to charging`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)

        // Наступне читання — за кілька годин, лічильник виріс на 30 кВт·год.
        log = observe(log, counter = 130.0, charging = false, nowMs = 5 * 60 * 60 * 1000L)

        assertEquals(0.0, log.todayKwh, 0.001)
        assertEquals(130.0, log.counterBaselineKwh, 0.001)
    }

    /**
     * Телефон під'єднався посеред зарядки: рахуємо лише побачену частину.
     * Недооцінка тут краща за приписану чужу енергію.
     */
    @Test
    fun `joining a session late counts only what was witnessed`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = true)
        log = observe(log, counter = 140.0, charging = true, nowMs = 60_000)

        assertEquals(40.0, log.sessionKwh, 0.001)
    }

    /** Лічильник не може зменшитись: це інша батарея або хибне читання. */
    @Test
    fun `a counter going backwards is re-baselined, not counted`() {
        var log = observe(ChargeLog(), counter = 26_937.9, charging = false)
        log = observe(log, counter = 26_937.9, charging = true, nowMs = 30_000)
        log = observe(log, counter = 12_000.0, nowMs = 60_000)

        assertEquals(12_000.0, log.counterBaselineKwh, 0.001)
        assertEquals(0.0, log.todayKwh, 0.001)
    }

    /**
     * Неправдоподібно великий прирост — хибне читання, а не зарядка: енергію не
     * нараховуємо. Ознака заряджання при цьому лишається такою, як на шині:
     * її ми не вигадуємо, а лише повторюємо.
     */
    @Test
    fun `an impossible jump is refused`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 100.0, charging = true, nowMs = 30_000)
        val absurd = 100.0 + ChargeTracker.MAX_PLAUSIBLE_STEP_KWH + 1
        log = observe(log, counter = absurd, nowMs = 60_000)

        assertEquals("Енергію нараховувати не мало", 0.0, log.todayKwh, 0.001)
        assertEquals("Сесію нараховувати не мало", 0.0, log.sessionKwh, 0.001)
        assertEquals("Базовий показ мусить переїхати", absurd, log.counterBaselineKwh, 0.001)
    }

    @Test
    fun `a counter of zero is ignored entirely`() {
        val log = observe(ChargeLog(), counter = 0.0, charging = false)

        assertFalse("Без лічильника базового показу бути не може", log.hasBaseline)
    }

    /**
     * Заради чого це все й переписувалося: авто заряджалося вночі, телефона в
     * ньому не було. На ранок лічильник прийнятої виріс на 38 кВт·год, заряд
     * піднявся з 20 до 95 %, а віддано за ніч не було нічого.
     */
    @Test
    fun `a charge that happened while we were away is counted`() {
        val evening = observe(
            ChargeLog(),
            counter = 27_000.0,
            charging = false,
            nowMs = HOUR,
            dischargedKwh = 26_000.0,
            socPercent = 20.0,
        )

        val morning = observe(
            evening,
            counter = 27_038.0,
            charging = false,
            nowMs = HOUR + 10 * HOUR,
            dischargedKwh = 26_000.0,
            socPercent = 95.0,
        )

        assertEquals(38.0, morning.lastSessionKwh, 0.001)
        assertEquals(38.0, morning.todayKwh, 0.001)
    }

    /**
     * РЕГРЕСІЯ НА ЗАРЯДКУ, ЯКУ ЗАСТОСУНОК БАЧИВ І ВСЕ ОДНО НЕ ПОРАХУВАВ.
     *
     * Найзвичайніший сценарій із життя, і саме він не працював. Числа — з журналу:
     *
     *     00:11  приїхав, поставив на зарядку. Застосунок бачить chg=1,
     *            socD=15 %, лічильник 27061.3 і встигає записати 0.1 кВт·год
     *     00:12  адаптер відвалився (OBD-порт гасне, коли авто йде в зарядку)
     *     08:09  під'єднався зранку: socD=95.2 %, лічильник 27098.4
     *
     * За ніч у батарею зайшло 37 кВт·год, а на екрані лишалося 0.1 — рівно те,
     * що встигли побачити живцем. Причина була в одному рядку: пошук пропущеної
     * зарядки починався з «якщо сесія відкрита — не рахувати нічого». Хоча саме
     * у відкритій сесії ми знаємо найбільше: зарядка почалася на наших очах.
     */
    @Test
    fun `a charge that finished while we were away lands in its session`() {
        val evening = observe(
            ChargeLog(),
            counter = 27_061.3,
            charging = true,
            nowMs = HOUR,
            dischargedKwh = 26_034.8,
            socPercent = 15.0,
        )
        // Устигли побачити перші 0.1 кВт·год і на цьому зв'язок обірвався.
        val seen = observe(
            evening,
            counter = 27_061.4,
            charging = true,
            nowMs = HOUR + 60_000L,
            dischargedKwh = 26_034.8,
            socPercent = 15.0,
        )
        assertEquals(0.1, seen.sessionKwh, 0.001)

        val morning = observe(
            seen,
            counter = 27_098.4,
            charging = false,
            nowMs = HOUR + 8 * HOUR,
            dischargedKwh = 26_034.8,
            socPercent = 95.2,
        )

        assertEquals("Ніч зникла з сесії", 37.1, morning.lastSessionKwh, 0.01)
        assertEquals(37.1, morning.todayKwh, 0.01)
        assertFalse(morning.charging)
    }

    /**
     * РЕГРЕСІЯ НА ПІДЗАРЯДКУ, ЯКУ ВІДКИНУВ ЗАНАДТО ТІСНИЙ ПОРІГ. Числа з журналу:
     *
     *     12:35:58  chg=1  socD=88.5  kWhIn=27084.9  kWhOut=26037.9
     *     13:15:02         socD=95    kWhIn=27086.9  kWhOut=26038.2
     *
     * Прийнято 2 кВт·год, заряд піднявся на 6.5 % — обидві головні перевірки
     * пройшли. А відкинула третя: лічильник ВІДДАНОЇ виріс на 0.3 кВт·год, тоді
     * як дозволено було 0.2. Авто сорок хвилин стояло на зарядці й живило власну
     * електроніку від тягового пакета — це нормальна фізика, а не поїздка.
     */
    @Test
    fun `the car feeding itself on the charger does not cancel the charge`() {
        val plugged = observe(
            ChargeLog(),
            counter = 27_084.9,
            charging = true,
            nowMs = HOUR,
            dischargedKwh = 26_037.9,
            socPercent = 88.5,
        )

        val back = observe(
            plugged,
            counter = 27_086.9,
            charging = false,
            nowMs = HOUR + 37 * MINUTE,
            dischargedKwh = 26_038.2,
            socPercent = 95.0,
        )

        assertEquals(2.0, back.lastSessionKwh, 0.01)
        assertTrue("Рішення не записане: «${back.lastDecision}»", back.lastDecision.contains("зараховано"))
    }

    /** І коли паузу не зарахували, у журналі мусить бути видно, який поріг спрацював. */
    @Test
    fun `a refused pause says which threshold stopped it`() {
        val before = observe(
            ChargeLog(),
            counter = 27_000.0,
            charging = false,
            nowMs = HOUR,
            dischargedKwh = 26_000.0,
            socPercent = 43.0,
        )

        val after = observe(
            before,
            counter = 27_006.7,
            charging = false,
            nowMs = HOUR + HOUR,
            dischargedKwh = 26_012.0,
            socPercent = 23.0,
        )

        assertFalse(after.hasLastSession)
        assertTrue("Причина не записана: «${after.lastDecision}»", after.lastDecision.contains("заряд не піднявся"))
    }

    /**
     * Поблажливість пропорційна часу, і за ніч вона більша. Інакше саме ті
     * зарядки, заради яких усе робиться, відкидалися б найохочіше.
     */
    @Test
    fun `an overnight charge tolerates a whole night of standby draw`() {
        val plugged = observe(
            ChargeLog(),
            counter = 27_000.0,
            charging = true,
            nowMs = HOUR,
            dischargedKwh = 26_000.0,
            socPercent = 15.0,
        )

        val morning = observe(
            plugged,
            counter = 27_038.0,
            charging = false,
            nowMs = HOUR + 8 * HOUR,
            // За вісім годин стоянки на зарядці набігло 2.5 кВт·год відбору.
            dischargedKwh = 26_002.5,
            socPercent = 95.0,
        )

        assertEquals(38.0, morning.lastSessionKwh, 0.01)
    }

    /**
     * А якщо після зарядки авто ще й поїхало, розділити зарядку й рекуперацію
     * нічим: лічильник відданої виріс. Тоді в сесію йде лише побачене — краще
     * недорахувати, ніж вигадати.
     */
    @Test
    fun `a drive after an unseen charge leaves only what was seen`() {
        val evening = observe(
            ChargeLog(),
            counter = 27_061.3,
            charging = true,
            nowMs = HOUR,
            dischargedKwh = 26_034.8,
            socPercent = 15.0,
        )

        val later = observe(
            evening,
            counter = 27_098.4,
            charging = false,
            nowMs = HOUR + 8 * HOUR,
            dischargedKwh = 26_050.0,
            socPercent = 60.0,
        )

        assertEquals(0.0, later.lastSessionKwh, 0.001)
    }

    /**
     * Регресія на зарядку, якої не було.
     *
     * Авто за годину проїхало 51 км, застосунок повернувся й побачив «лічильник
     * прийнятої +6.7 кВт·год». Це рекуперація за поїздку. Одометр у той момент
     * ще показував старе значення — він приходить іншим кадром, — і саме на
     * цьому застосунок записав зарядку на 6.7 кВт·год. Але SOC упав із 43 % до
     * 23 %, а лічильник відданої виріс на 12 кВт·год: обидва числа з того самого
     * кадру кажуть «це поїздка».
     */
    @Test
    fun `a drive across a gap is never called a charge`() {
        val before = observe(
            ChargeLog(),
            counter = 27_052.2,
            charging = false,
            nowMs = HOUR,
            dischargedKwh = 26_018.2,
            socPercent = 43.0,
        )

        val after = observe(
            before,
            counter = 27_058.9,
            charging = false,
            nowMs = HOUR + HOUR,
            dischargedKwh = 26_030.2,
            socPercent = 23.0,
        )

        assertFalse("Поїздку записано як зарядку", after.hasLastSession)
        assertEquals(0.0, after.todayKwh, 0.001)
    }

    /**
     * І окремо: заряд виріс, але авто щось віддавало — отже воно їхало, а не
     * стояло на зарядці. Такий проміжок розділити нічим, тож не рахуємо.
     */
    @Test
    fun `a gap with discharge in it is not counted`() {
        val before = observe(
            ChargeLog(),
            counter = 27_000.0,
            charging = false,
            nowMs = HOUR,
            dischargedKwh = 26_000.0,
            socPercent = 20.0,
        )

        val after = observe(
            before,
            counter = 27_038.0,
            charging = false,
            nowMs = HOUR + 10 * HOUR,
            dischargedKwh = 26_009.0,
            socPercent = 60.0,
        )

        assertFalse(after.hasLastSession)
    }

    /** Дрібний приріст за паузу — не зарядка: стільки набігає й від службових потреб. */
    @Test
    fun `a tiny gain across a gap is not called a charge`() {
        val before = observe(
            ChargeLog(),
            counter = 27_000.0,
            charging = false,
            nowMs = HOUR,
        )

        val after = observe(
            before,
            counter = 27_000.2,
            charging = false,
            nowMs = HOUR + 10 * HOUR,
            socPercent = 55.0,
        )

        assertFalse(after.hasLastSession)
    }

    /**
     * Коротка пауза — це не «зарядка без телефона», а звичайний крок опитування.
     * Стоячи на місці з увімкненим кліматом лічильник теж інколи ворушиться.
     */
    @Test
    fun `a short pause is not treated as a missed charge`() {
        val before = observe(
            ChargeLog(),
            counter = 27_000.0,
            charging = false,
            nowMs = HOUR,
        )

        val after = observe(
            before,
            counter = 27_001.0,
            charging = false,
            nowMs = HOUR + 60_000L,
            socPercent = 55.0,
        )

        assertFalse(after.hasLastSession)
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * MINUTE
    }
}
