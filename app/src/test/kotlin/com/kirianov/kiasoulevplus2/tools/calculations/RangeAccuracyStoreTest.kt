package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.RangeAccuracy
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
 * «Чи стримав прогноз обіцянку» набирається кілометрами: до трьох відсоток нічого
 * не означає. Тому відлік мусить пережити перезапуск — інакше кожне оновлення
 * застосунку починає перевірку з нуля, і результату не побачити ніколи.
 */
class RangeAccuracyStoreTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private class MemoryStore(var saved: RangeAccuracy? = null) : RangeAccuracyStore {
        var car = ""
        override fun useCar(vin: String) {
            car = vin
        }

        override fun load(): RangeAccuracy? = saved
        override fun save(accuracy: RangeAccuracy) {
            saved = accuracy
        }
    }

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    /** Збережений відлік піднімається на старті, а не починається з нуля. */
    @Test
    fun `a saved run comes back after a restart`() {
        val store = MemoryStore(
            saved = RangeAccuracy(
                startRangeKm = 200.0,
                currentRangeKm = 160.0,
                drivenKm = 35.0,
                startOdometerKm = 189_000.0,
                started = true,
            ),
        )
        CalculationBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)

        GeneralData.updateGarage { it.copy(activeVin = "MINE") }

        val accuracy = GeneralData.state.value.rangeAccuracy
        assertTrue(accuracy.started)
        assertEquals(35.0, accuracy.drivenKm, 0.001)
        assertEquals("MINE", store.car)
    }

    /**
     * РЕГРЕСІЯ: піднятий із файлу відлік стирався першим же читанням після запуску.
     *
     * Скидання завʼязане на «зʼявилася нова завершена зарядка», а лічильник
     * побаченого починався з нуля. Журнал зарядок при цьому піднімається з файлу
     * з НЕнульовим часом останньої сесії — і перше читання виглядало як «поки ми
     * не дивилися, авто зарядилося». Доти відлік жив лише в памʼяті й стирати було
     * нічого; щойно його навчили переживати оновлення, помилка зʼїла 141.8 км
     * спостережень за один запуск.
     */
    @Test
    fun `the first reading after a restart does not wipe the run`() {
        val store = MemoryStore(
            saved = RangeAccuracy(
                startRangeKm = 200.0,
                currentRangeKm = 108.0,
                drivenKm = 141.8,
                startOdometerKm = 189_000.0,
                started = true,
            ),
        )
        CalculationBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.updateGarage { it.copy(activeVin = "MINE") }

        // Журнал зарядок з файлу: остання сесія скінчилася вчора ввечері.
        GeneralData.updateChargeLog(ChargeLog(lastSessionEndedAtMs = 1_725_000_000_000L))

        val accuracy = GeneralData.state.value.rangeAccuracy
        assertTrue("Відлік мав пережити запуск", accuracy.started)
        assertEquals(141.8, accuracy.drivenKm, 0.001)
    }

    /** А ось СПРАВЖНЯ нова зарядка відлік скидає: після неї обіцянка вже інша. */
    @Test
    fun `a charge that happened while we were away still resets the run`() {
        val store = MemoryStore(
            saved = RangeAccuracy(
                startRangeKm = 200.0,
                currentRangeKm = 108.0,
                drivenKm = 141.8,
                startOdometerKm = 189_000.0,
                started = true,
            ),
        )
        CalculationBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.updateGarage { it.copy(activeVin = "MINE") }

        GeneralData.updateChargeLog(ChargeLog(lastSessionEndedAtMs = 1_725_000_000_000L))
        // Друга сесія, вже за нашої присутності.
        GeneralData.updateChargeLog(ChargeLog(lastSessionEndedAtMs = 1_725_003_600_000L))

        assertTrue(GeneralData.state.value.rangeAccuracy.drivenKm == 0.0)
    }

    /** Кожна зміна відліку одразу лягає у файл: перезапуск може статися будь-коли. */
    @Test
    fun `every change is written to the store`() {
        val store = MemoryStore()
        CalculationBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)
        GeneralData.updateGarage { it.copy(activeVin = "MINE") }

        GeneralData.updateRangeAccuracy(
            RangeAccuracy(startRangeKm = 100.0, startOdometerKm = 1.0, started = true),
        )

        assertTrue(store.saved!!.started)
        assertEquals(100.0, store.saved!!.startRangeKm, 0.001)
    }

    /** Дані двох авто не змішуються: одометри в них різняться на десятки тисяч. */
    @Test
    fun `two cars keep separate runs`() {
        val dir = File.createTempFile("range", "").apply { delete(); mkdirs() }
        try {
            val store = FileRangeAccuracyStore(dir)
            store.useCar("FIRST")
            store.save(RangeAccuracy(drivenKm = 10.0, startOdometerKm = 189_000.0, started = true))
            store.useCar("SECOND")
            store.save(RangeAccuracy(drivenKm = 4.0, startOdometerKm = 113_000.0, started = true))

            store.useCar("FIRST")
            assertEquals(189_000.0, store.load()!!.startOdometerKm, 0.001)
            store.useCar("SECOND")
            assertEquals(113_000.0, store.load()!!.startOdometerKm, 0.001)
        } finally {
            dir.deleteRecursively()
        }
    }
}
