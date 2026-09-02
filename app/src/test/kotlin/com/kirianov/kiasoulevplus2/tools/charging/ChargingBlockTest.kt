package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ChargingState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChargingBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private var now = 0L

    private class MemoryStore(var saved: ChargeLog? = null) : ChargeStore {
        var saves = 0
        override fun load(): ChargeLog? = saved
        override fun save(log: ChargeLog) {
            saved = log
            saves++
        }
    }

    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        GeneralData.reset()
        now = 0L
        store = MemoryStore()
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    private fun start(saved: ChargeLog? = null) {
        store.saved = saved
        ChargingBlock(store, nowMs = { now }, dayKey = { "2026-09-01" }).start(scope)
    }

    private fun publish(counterKwh: Double, charging: Boolean) {
        GeneralData.updateBms(BmsData(displaySoc = 80.0, cumulativeEnergyChargedKwh = counterKwh))
        GeneralData.updateVehicle(VehicleData(charging = ChargingState(isCharging = charging)))
    }

    @Test
    fun `charging is tracked from the counter into the hub`() {
        start()
        publish(100.0, charging = false)
        now = 60_000
        publish(104.5, charging = true)

        val charge = GeneralData.state.value.charge
        assertTrue(charge.charging)
        assertEquals(4.5, charge.sessionKwh, 0.001)
        assertEquals(4.5, charge.todayKwh, 0.001)
    }

    /**
     * Головне про перезапуск: базовий показ лічильника мусить приїхати зі сховища.
     * Інакше перше ж читання після перезапуску нарахувало б усю історію батареї.
     */
    @Test
    fun `a saved baseline survives a restart`() {
        start(
            saved = ChargeLog(
                counterBaselineKwh = 26_900.0,
                hasBaseline = true,
                todayKwh = 7.0,
                dayKey = "2026-09-01",
            ),
        )

        publish(26_902.5, charging = true)

        val charge = GeneralData.state.value.charge
        assertEquals("Нараховано мало бути лише прирост", 9.5, charge.todayKwh, 0.001)
        assertEquals(2.5, charge.sessionKwh, 0.001)
    }

    /** Перезапуск наступного дня: добовий підсумок починається заново. */
    @Test
    fun `a restart on a new day starts the daily total over`() {
        start(
            saved = ChargeLog(
                counterBaselineKwh = 26_900.0,
                hasBaseline = true,
                todayKwh = 7.0,
                dayKey = "2026-08-31",
            ),
        )

        publish(26_902.5, charging = true)

        assertEquals(2.5, GeneralData.state.value.charge.todayKwh, 0.001)
    }

    /**
     * Підсумок за добу з невідомої доби нараховувати не можна: збережений лог без
     * дати міг лежати місяцями.
     */
    @Test
    fun `a saved total without a day is not carried over`() {
        start(saved = ChargeLog(counterBaselineKwh = 100.0, hasBaseline = true, todayKwh = 7.0))

        publish(102.5, charging = true)

        assertEquals(2.5, GeneralData.state.value.charge.todayKwh, 0.001)
    }

    @Test
    fun `the saved log is published before any reading arrives`() {
        start(saved = ChargeLog(lastSessionKwh = 31.4, hasBaseline = true, counterBaselineKwh = 100.0))

        assertEquals(31.4, GeneralData.state.value.charge.lastSessionKwh, 0.001)
    }

    @Test
    fun `every change is written to the store`() {
        start()
        publish(100.0, charging = false)
        now = 60_000
        publish(103.0, charging = true)

        assertEquals(103.0, store.saved!!.counterBaselineKwh, 0.001)
        assertEquals(3.0, store.saved!!.sessionKwh, 0.001)
    }

    /** Однакові читання не мусять молотити диск: опитування йде раз на 800 мс. */
    @Test
    fun `an unchanged reading is not written again`() {
        start()
        publish(100.0, charging = false)
        val after = store.saves

        publish(100.0, charging = false)
        publish(100.0, charging = false)

        assertEquals(after, store.saves)
    }

    @Test
    fun `a finished session shows up as the last one`() {
        start()
        publish(100.0, charging = false)
        now = 60_000
        publish(110.0, charging = true)
        now = 120_000
        publish(110.0, charging = false)

        val charge = GeneralData.state.value.charge
        assertFalse(charge.charging)
        assertEquals(10.0, charge.lastSessionKwh, 0.001)
        assertTrue(charge.hasLastSession)
    }
}
