package com.kirianov.kiasoulevplus2.tools.storage

import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StorageBlockTest {

    private class FakeStore(private var stored: Map<Int, Double> = emptyMap()) : ManualCellStore {
        val saves = mutableListOf<Map<Int, Double>>()
        override fun load(): Map<Int, Double> = stored
        override fun save(voltages: Map<Int, Double>) {
            stored = voltages
            saves += voltages
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    @Test
    fun `stored voltages are published on start`() {
        StorageBlock(FakeStore(mapOf(0 to 3.80, 5 to 3.90))).start(scope)

        val manual = GeneralData.state.value.manualCells
        assertEquals(3.80, manual.voltageAt(0), 0.0001)
        assertEquals(3.90, manual.voltageAt(5), 0.0001)
    }

    @Test
    fun `what the loaded values were is not written straight back`() {
        val store = FakeStore(mapOf(0 to 3.80))
        StorageBlock(store).start(scope)

        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `an entered voltage is persisted`() {
        val store = FakeStore()
        StorageBlock(store).start(scope)

        GeneralData.setManualCell(3, 3.85)

        assertEquals(1, store.saves.size)
        assertEquals(3.85, store.saves.last()[3]!!, 0.0001)
    }
}
