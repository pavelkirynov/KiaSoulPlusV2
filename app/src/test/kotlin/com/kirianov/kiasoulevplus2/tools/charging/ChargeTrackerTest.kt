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

    @Test
    fun `growth of the counter is charging`() {
        var log = observe(ChargeLog(), counter = 26_937.9, charging = false)
        log = observe(log, counter = 26_940.4, nowMs = 60_000)

        assertTrue(log.charging)
        assertEquals(2.5, log.sessionKwh, 0.001)
        assertEquals(2.5, log.todayKwh, 0.001)
    }

    @Test
    fun `a session closes when charging stops and becomes the last one`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 104.0, nowMs = 60_000)
        log = observe(log, counter = 108.0, nowMs = 120_000)

        // Приросту більше немає і шина каже, що зарядка скінчилася.
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
        log = observe(log, counter = 108.0, nowMs = 60_000)
        log = observe(log, counter = 108.0, charging = false, nowMs = 120_000)
        assertEquals(8.0, log.lastSessionKwh, 0.001)

        val later = 120_000L + ChargeTracker.SESSION_GAP_MS + 1
        log = observe(log, counter = 111.0, nowMs = later)

        assertEquals("Нова сесія мусить починатися з нуля", 3.0, log.sessionKwh, 0.001)
        assertEquals(later, log.sessionStartedAtMs)
        assertEquals(11.0, log.todayKwh, 0.001)
    }

    @Test
    fun `a new day resets the daily total but not the last session`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)
        log = observe(log, counter = 108.0, nowMs = 60_000)
        log = observe(log, counter = 108.0, charging = false, nowMs = 120_000)

        log = observe(log, counter = 110.0, nowMs = 200_000, dayKey = "2026-09-02")

        assertEquals("Добовий підсумок мусить початися заново", 2.0, log.todayKwh, 0.001)
        assertEquals("2026-09-02", log.dayKey)
        assertEquals("Остання зарядка добі не належить", 8.0, log.lastSessionKwh, 0.001)
    }

    /**
     * Заряджання найчастіше відбувається без телефона. Різниця з останнім
     * побаченим показом мусить врахувати те, що сталося за час відсутності.
     */
    @Test
    fun `energy taken while the app was away still counts`() {
        var log = observe(ChargeLog(), counter = 100.0, charging = false)

        // Наступне читання — за кілька годин, лічильник виріс на 30 кВт·год.
        log = observe(log, counter = 130.0, charging = false, nowMs = 5 * 60 * 60 * 1000L)

        assertEquals(30.0, log.todayKwh, 0.001)
        assertEquals(30.0, log.sessionKwh, 0.001)
    }

    /** Лічильник не може зменшитись: це інша батарея або хибне читання. */
    @Test
    fun `a counter going backwards is re-baselined, not counted`() {
        var log = observe(ChargeLog(), counter = 26_937.9, charging = false)
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
