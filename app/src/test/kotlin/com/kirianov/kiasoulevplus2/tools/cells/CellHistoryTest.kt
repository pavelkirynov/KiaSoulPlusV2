package com.kirianov.kiasoulevplus2.tools.cells

import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.CellRecord
import com.kirianov.kiasoulevplus2.Data.CellSweep
import com.kirianov.kiasoulevplus2.Data.CellTestRequest
import com.kirianov.kiasoulevplus2.Data.GeneralData
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
 * Замір комірок має сенс лише в порівнянні: одна табличка напруг не каже нічого.
 * Тому історія — не прикраса, а те, заради чого тест узагалі роблять двічі.
 */
class CellHistoryTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private class MemoryStore : CellHistoryStore {
        var records: List<CellRecord> = emptyList()
        var car = ""
        override fun useCar(vin: String) {
            car = vin
        }

        override fun load(): List<CellRecord> = records
        override fun save(records: List<CellRecord>) {
            this.records = records
        }
    }

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    /** Закінчений тест лягає в історію сам: питати про це — значить втрачати заміри. */
    @Test
    fun `a finished load test is saved without asking`() {
        val store = MemoryStore()
        CellTestBlock(store, nowMs = { 1_000L }, ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.updateGarage { it.copy(activeVin = "MINE") }

        GeneralData.requestCellTest(CellTestRequest.Start)
        repeat(6) { step ->
            GeneralData.publishDecodedSweep(
                CellSweep(
                    voltages = listOf(3.60, 3.58, 3.62),
                    currentBeforeA = -20.0 * step,
                    currentAfterA = -20.0 * step,
                    packVolts = 360.0,
                    atMs = step * 1_000L,
                ),
            )
        }
        GeneralData.requestCellTest(CellTestRequest.Stop)

        assertEquals(1, store.records.size)
        assertEquals(3, store.records.first().restVolts.size)
        assertTrue("Запис із тесту мусить знати про проходи", store.records.first().fromLoadTest)
    }

    /** Простий замір напруг зберігається кнопкою — і з умовами, у яких знятий. */
    @Test
    fun `a snapshot keeps the conditions it was taken in`() {
        val store = MemoryStore()
        CellTestBlock(store, nowMs = { 5_000L }, ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.updateGarage { it.copy(activeVin = "MINE") }

        GeneralData.updateCells(CellData(cellVoltages = listOf(3.7, 3.7)))
        GeneralData.updateBms(
            com.kirianov.kiasoulevplus2.Data.BmsData(displaySoc = 80.0, batteryTempC = 21.0),
        )
        GeneralData.updateVehicle(
            com.kirianov.kiasoulevplus2.Data.VehicleData(odometerKm = 189_420.0),
        )

        GeneralData.requestCellSnapshot()

        val record = store.records.single()
        assertEquals(5_000L, record.atMs)
        assertEquals(80.0, record.socPercent, 0.001)
        assertEquals(21.0, record.batteryTempC, 0.001)
        assertEquals(189_420.0, record.odometerKm, 0.001)
        assertEquals(0, record.sweeps)
    }

    /** Змінилося авто — історія й тест від нього. Комірки в машин різні. */
    @Test
    fun `switching cars switches the history`() {
        val store = MemoryStore()
        store.records = listOf(record(atMs = 100L))
        CellTestBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)

        GeneralData.updateGarage { it.copy(activeVin = "OTHER") }

        assertEquals("OTHER", store.car)
        assertEquals(1, GeneralData.state.value.cellHistory.records.size)
        assertTrue(GeneralData.state.value.cellHistory.loaded)
    }

    // --- Файл ----------------------------------------------------------------------

    /** Запис переживає перезапис файлу цілком, разом з умовами заміру. */
    @Test
    fun `a record survives a round trip through the file`() {
        val dir = File.createTempFile("cells", "").apply { delete(); mkdirs() }
        try {
            val store = FileCellHistoryStore(dir)
            store.useCar("KNDJX3AE5F7001234")
            store.save(listOf(record(atMs = 7L)))

            val back = FileCellHistoryStore(dir).apply { useCar("KNDJX3AE5F7001234") }.load()

            assertEquals(listOf(record(atMs = 7L)), back)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Дані двох авто не змішуються: у кожного своя тека. */
    @Test
    fun `two cars keep separate histories`() {
        val dir = File.createTempFile("cells", "").apply { delete(); mkdirs() }
        try {
            val store = FileCellHistoryStore(dir)
            store.useCar("FIRST")
            store.save(listOf(record(atMs = 1L)))
            store.useCar("SECOND")
            store.save(listOf(record(atMs = 2L), record(atMs = 3L)))

            store.useCar("FIRST")
            assertEquals(1, store.load().size)
            store.useCar("SECOND")
            assertEquals(2, store.load().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Заміри з другого телефона додаються, а не замінюють: обидва бачили ту саму
     * машину в різні дні. Той самий замір упізнається за часом і не подвоюється.
     */
    @Test
    fun `merging adds what is missing and skips what is known`() {
        val mine = File.createTempFile("mine", "").apply { delete(); mkdirs() }
        val theirs = File.createTempFile("theirs", "").apply { delete(); mkdirs() }
        try {
            val store = FileCellHistoryStore(mine).apply { useCar("CAR") }
            store.save(listOf(record(atMs = 1L), record(atMs = 2L)))

            val other = FileCellHistoryStore(theirs).apply { useCar("CAR") }
            other.save(listOf(record(atMs = 2L), record(atMs = 3L)))
            other.exportTo(File(theirs, "bundle"))

            val note = store.mergeFrom(File(theirs, "bundle"))

            assertTrue(note.contains("1"))
            assertEquals(listOf(3L, 2L, 1L), store.load().map { it.atMs })
        } finally {
            mine.deleteRecursively()
            theirs.deleteRecursively()
        }
    }

    private fun record(atMs: Long) = CellRecord(
        atMs = atMs,
        odometerKm = 189_420.7,
        socPercent = 47.5,
        batteryTempC = 29.0,
        restVolts = listOf(3.61, 3.60, 3.62),
        minVolts = listOf(3.51, 3.48, 3.52),
        excessMilliOhm = listOf(0.0, 0.4, 0.1),
        sweeps = 42,
        currentSpreadA = 88.0,
    )
}
