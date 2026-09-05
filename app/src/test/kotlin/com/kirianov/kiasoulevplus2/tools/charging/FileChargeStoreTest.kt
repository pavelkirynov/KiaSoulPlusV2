package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileChargeStoreTest {

    private fun directory(): File =
        File(System.getProperty("java.io.tmpdir"), "charge-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `a saved log reads back exactly`() {
        val store = FileChargeStore(directory())
        val log = ChargeLog(
            lastSessionKwh = 31.4,
            lastSessionEndedAtMs = 1_788_000_000_000L,
            sessionKwh = 2.5,
            sessionStartedAtMs = 1_788_000_100_000L,
            charging = true,
            todayKwh = 33.9,
            dayKey = "2026-09-01",
            counterBaselineKwh = 26_937.9,
            dischargedBaselineKwh = 26_009.4,
            socBaselinePercent = 81.5,
            lastSeenAtMs = 1_788_000_090_000L,
            lastDecision = "зараховано 4.5 кВт·год за паузу",
            hasBaseline = true,
        )

        store.save(log)

        assertEquals(log, store.load())
    }

    /**
     * ЦЕ ТЕСТ НА ПОМИЛКУ, ЯКА КОШТУВАЛА ВСІХ НІЧНИХ ЗАРЯДОК.
     *
     * Три поля базового показу — лічильник відданої, заряд і час останнього
     * погляду — до сховища колись не дописали. Наслідок був не «трохи неточно», а
     * повна втрата: після кожного перезапуску час останнього погляду читався як
     * нуль, перевірка «чи була пауза» через це мовчки не спрацьовувала, а базовий
     * показ усе одно переїжджав на новий — разом із 23 кВт·год, які нікуди було
     * записати. У журналі про це не лишалося ані рядка.
     *
     * Тому кожне з трьох полів перевіряємо поіменно, а не через рівність цілого:
     * рівність цілого впаде на будь-якій зміні, а тут має бути видно, ЩО саме
     * загубилося.
     */
    @Test
    fun `the whole baseline survives a restart, not just the counter`() {
        val store = FileChargeStore(directory())
        store.save(
            ChargeLog(
                counterBaselineKwh = 27_089.5,
                dischargedBaselineKwh = 26_041.4,
                socBaselinePercent = 80.5,
                lastSeenAtMs = 1_788_213_063_490L,
                charging = true,
                sessionKwh = 0.3,
                hasBaseline = true,
            ),
        )

        val back = store.load()!!

        assertEquals(27_089.5, back.counterBaselineKwh, 0.001)
        assertEquals("Лічильник відданої: без нього поїздку не відрізнити від зарядки",
            26_041.4, back.dischargedBaselineKwh, 0.001)
        assertEquals("Заряд на момент базового показу", 80.5, back.socBaselinePercent, 0.001)
        assertEquals("Час останнього погляду: нуль тут означає «пауза не зараховується»",
            1_788_213_063_490L, back.lastSeenAtMs)
    }

    /**
     * Файл, збережений старою версією, читається далі. Нових полів у ньому немає —
     * і це не привід викинути весь облік разом із базовим показом лічильника.
     */
    @Test
    fun `a file without the new fields still loads`() {
        val dir = directory()
        File(dir, "charge-log.json").writeText(
            """{"lastSessionKwh":0.3,"lastSessionEndedAtMs":0.0,"sessionKwh":0.0,""" +
                """"sessionStartedAtMs":0.0,"charging":false,"todayKwh":0.4,""" +
                """"dayKey":"2026-09-04","counterBaselineKwh":27089.5,"hasBaseline":true}""",
        )

        val back = FileChargeStore(dir).load()!!

        assertEquals(27_089.5, back.counterBaselineKwh, 0.001)
        assertEquals(0.0, back.dischargedBaselineKwh, 0.001)
        assertEquals(0L, back.lastSeenAtMs)
    }

    /** Мілісекунди через Double: перевіряємо, що позначка часу не втратила точність. */
    @Test
    fun `a millisecond timestamp survives the round trip`() {
        val store = FileChargeStore(directory())
        val stamp = 1_788_213_063_490L

        store.save(ChargeLog(lastSessionEndedAtMs = stamp, counterBaselineKwh = 1.0, hasBaseline = true))

        assertEquals(stamp, store.load()!!.lastSessionEndedAtMs)
    }

    @Test
    fun `nothing saved reads as nothing`() {
        assertNull(FileChargeStore(directory()).load())
    }

    @Test
    fun `a damaged file reads as nothing instead of throwing`() {
        val dir = directory()
        File(dir, "charge-log.json").writeText("не json взагалі")

        assertNull(FileChargeStore(dir).load())
    }

    @Test
    fun `a missing directory is created on save`() {
        val dir = File(directory(), "ще-немає")
        val store = FileChargeStore(dir)

        store.save(ChargeLog(counterBaselineKwh = 5.0, hasBaseline = true))

        assertTrue(dir.isDirectory)
        assertEquals(5.0, store.load()!!.counterBaselineKwh, 0.001)
    }
}
