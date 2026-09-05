package com.kirianov.kiasoulevplus2.tools.journal

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ChargingState
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.MlData
import com.kirianov.kiasoulevplus2.Data.MlModelInfo
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Журнал — свідок, а не суддя. Тому перевіряється тут одне: чи потрапляє у файл
 * саме те, що сталося, і чи не потрапляє те, чого не було.
 */
class JournalTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private class MemoryStore : JournalStore {
        val lines = mutableListOf<String>()
        var cleared = 0

        override fun append(lines: List<String>): Long {
            this.lines += lines
            return this.lines.sumOf { it.length + 1 }.toLong()
        }

        override fun sizeBytes(): Long = lines.sumOf { it.length + 1 }.toLong()

        override fun clear() {
            lines.clear()
            cleared++
        }

        override fun path(): String = "/tmp/journal.txt"
    }

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    // --- Формат ------------------------------------------------------------------

    /**
     * Головне поле зрізу — номер зчитування. Саме воно відрізняє «застосунок
     * показує старі числа, бо опитування стоїть» від «числа просто не змінилися».
     */
    @Test
    fun `a snapshot carries the reading number and the odometer`() {
        GeneralData.publishBatteryFrames(listOf("21 01"), listOf("61 01"))
        GeneralData.updateVehicle(VehicleData(odometerKm = 188_894.3, speedKmh = 42.0))
        GeneralData.updateBms(BmsData(displaySoc = 95.2, batteryVoltage = 366.8, batteryCurrent = -12.5))

        val line = JournalFormat.snapshot(GeneralData.state.value, 0L)

        assertTrue(line, line.contains("seq=1"))
        assertTrue(line, line.contains("odo=188894.3"))
        assertTrue(line, line.contains("socD=95.2"))
        assertTrue(line, line.contains("I=-12.5"))
    }

    /** Невідоме значення пишеться прочерком, а не нулем: це різні речі. */
    @Test
    fun `an unknown value is a dash, not a zero`() {
        val line = JournalFormat.snapshot(State(), 0L)

        assertTrue(line, line.contains("odo=-"))
        assertTrue(line, line.contains("v=-"))
    }

    @Test
    fun `nothing changed means nothing written`() {
        val state = State()

        assertEquals(emptyList<String>(), JournalFormat.events(state, state, 0L))
    }

    @Test
    fun `a lost link is written down with its reason`() {
        val before = State(connection = ConnectionState.Connected)
        val after = State(connection = ConnectionState.Disconnected, debugInfo = "Адаптер не відповів за 28 с")

        val lines = JournalFormat.events(before, after, 0L)

        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("Disconnected"))
        assertTrue(lines[0], lines[0].contains("Адаптер не відповів"))
    }

    /** Відбраковка відрізка — саме те, заради чого журнал і заводився. */
    @Test
    fun `an aborted segment is written with its reason`() {
        val before = State(ml = MlData(model = MlModelInfo(abortedSegments = 5)))
        val after = State(
            ml = MlData(model = MlModelInfo(abortedSegments = 6, lastAbortReason = "обрив зв'язку")),
        )

        val lines = JournalFormat.events(before, after, 0L)

        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("abort n=6"))
        assertTrue(lines[0], lines[0].contains("обрив зв'язку"))
    }

    /** Поява ознаки заряджання пишеться разом із лічильником: інакше її нічим звірити. */
    @Test
    fun `the charging flag is written together with the counter`() {
        val before = State()
        val after = State(
            vehicle = VehicleData(charging = ChargingState(isCharging = true)),
            bms = BmsData(cumulativeEnergyChargedKwh = 27_036.3),
        )

        val lines = JournalFormat.events(before, after, 0L)

        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("chg=1"))
        assertTrue(lines[0], lines[0].contains("kWhIn=27036.3"))
    }

    @Test
    fun `a finished charge session is written`() {
        val before = State()
        val after = State(charge = ChargeLog(lastSessionKwh = 38.0, todayKwh = 38.0))

        val lines = JournalFormat.events(before, after, 0L)

        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("last=38"))
    }

    // --- Файл --------------------------------------------------------------------

    @Test
    fun `lines land in the file and the oldest are dropped when it grows`() {
        val dir = File.createTempFile("journal", "dir").apply { delete(); mkdirs() }
        try {
            val store = FileJournalStore(dir, maxBytes = 100L)

            store.append(listOf("a".repeat(150)))
            val afterFirst = store.sizeBytes()
            store.append(listOf("b".repeat(150)))
            val afterSecond = store.sizeBytes()

            assertTrue("Перший запис не потрапив у файл", afterFirst > 100)
            // Файл переріс межу і почався заново: у ньому лише свіжий рядок.
            assertTrue("Файл не перевернувся: $afterSecond", afterSecond < afterFirst + 100)
            assertTrue(File(store.path()).readText().contains("b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Блок --------------------------------------------------------------------

    @Test
    fun `the block writes a snapshot while connected`() {
        val store = MemoryStore()
        var now = 0L
        JournalBlock(store, appVersion = "test", nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        GeneralData.updateConnection(ConnectionState.Connected, "")
        now += 10_000
        GeneralData.updateVehicle(VehicleData(odometerKm = 1.0))

        assertTrue(store.lines.any { it.contains(" snap ") })
    }

    /** Вимкнений журнал не пише нічого, крім того, що вже було записано до вимкнення. */
    @Test
    fun `the switch stops the writing`() {
        val store = MemoryStore()
        var now = 0L
        JournalBlock(store, appVersion = "test", nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.setJournalEnabled(false)
        val written = store.lines.size

        GeneralData.updateConnection(ConnectionState.Connected, "")
        now += 10_000
        GeneralData.updateVehicle(VehicleData(odometerKm = 1.0))

        assertEquals(written, store.lines.size)
    }

    /** Після очищення файл починається заново — з шапки, а не з порожнечі. */
    @Test
    fun `clearing empties the file`() {
        val store = MemoryStore()
        JournalBlock(store, appVersion = "test", ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.updateConnection(ConnectionState.Connected, "")

        GeneralData.requestJournalClear()

        assertEquals(1, store.cleared)
        // Файл починається наново: шапка, далі свіжий зріз — і жодного рядка,
        // записаного до очищення.
        assertTrue("Немає шапки: ${store.lines}", store.lines.first().contains(" open "))
        assertTrue("Лишилося старе: ${store.lines}", store.lines.none { it.contains(" link ") })
    }
}
