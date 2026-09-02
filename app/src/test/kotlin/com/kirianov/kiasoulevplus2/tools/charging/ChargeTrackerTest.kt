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
    ) = ChargeTracker.observe(log, counter, charging, nowMs, dayKey)

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
}
