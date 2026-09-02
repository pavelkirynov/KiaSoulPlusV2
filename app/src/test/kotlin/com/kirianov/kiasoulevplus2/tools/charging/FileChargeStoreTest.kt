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
            hasBaseline = true,
        )

        store.save(log)

        assertEquals(log, store.load())
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
