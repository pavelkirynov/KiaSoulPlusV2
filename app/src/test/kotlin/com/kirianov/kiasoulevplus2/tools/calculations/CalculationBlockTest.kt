package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
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

class CalculationBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    /** Керований годинник: тест не має залежати від реального часу. */
    private var now = 0L

    @Before
    fun setUp() {
        GeneralData.reset()
        now = 0L
        CalculationBlock(elapsedMillis = { now }).start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    private fun reading(discharged: Double, km: Double = 0.0, charged: Double = 6123.4) {
        if (km > 0.0) GeneralData.updateVehicle(VehicleData(odometerKm = km))
        GeneralData.updateBms(
            BmsData(
                displaySoc = 80.0,
                cumulativeEnergyChargedKwh = charged,
                cumulativeEnergyDischargedKwh = discharged,
            ),
        )
    }

    @Test
    fun `power still follows the readings`() {
        GeneralData.updateBms(BmsData(displaySoc = 80.0, batteryVoltage = 366.0, batteryCurrent = -50.0))

        assertEquals(-18.3, GeneralData.state.value.calculated.powerKw, 0.0001)
    }

    @Test
    fun `cell spread follows the decoded cells`() {
        GeneralData.updateCells(CellData(cellVoltages = listOf(3.80, 3.95)))

        val calculated = GeneralData.state.value.calculated
        assertEquals(3.80, calculated.minCellVoltage, 0.0001)
        assertEquals(0.15, calculated.cellDeltaVolts, 0.0001)
    }

    @Test
    fun `trip energy grows from the first reading and is tied to time and distance`() {
        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")

        reading(discharged = 5987.6, km = 50_000.0, charged = 6123.4)
        now = 30 * 60_000
        // За пів години 40 км: віддано 6 кВт·год, рекуперацією повернуто 1.
        reading(discharged = 5993.6, km = 50_040.0, charged = 6124.4)

        val trip = GeneralData.state.value.calculated.trip
        assertEquals(6.0, trip.consumedKwh, 0.0001)
        assertEquals(1.0, trip.recoveredKwh, 0.0001)
        assertEquals(5.0, trip.netKwh, 0.0001)
        assertEquals(40.0, trip.distanceKm, 0.0001)
        assertEquals(30 * 60_000L, trip.durationMs)
        assertEquals(80.0, trip.averageSpeedKmh!!, 0.0001)
        assertEquals(12.5, trip.kwhPer100Km!!, 0.0001)
    }

    @Test
    fun `the selected window is recalculated when it changes`() {
        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")

        reading(discharged = 100.0, km = 1000.0)
        now = 10 * 60_000
        reading(discharged = 102.0, km = 1010.0)
        now = 20 * 60_000
        reading(discharged = 104.0, km = 1015.0)

        assertEquals(26.66, GeneralData.state.value.calculated.window.kwhPer100Km!!, 0.01)

        GeneralData.selectConsumptionWindow(ConsumptionWindow.Last5Km)

        val window = GeneralData.state.value.calculated.window
        assertEquals(5.0, window.distanceKm, 0.0001)
        assertEquals(2.0, window.consumedKwh, 0.0001)
    }

    /** Одне підключення — одна поїздка: після від'єднання відлік починається заново. */
    @Test
    fun `disconnecting clears the trip`() {
        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")
        reading(discharged = 5987.6, km = 50_000.0)
        assertFalse(GeneralData.state.value.tripHistory.isEmpty)

        GeneralData.updateConnection(ConnectionState.Disconnected, "Відключено")

        assertTrue(GeneralData.state.value.tripHistory.isEmpty)
        assertFalse(GeneralData.state.value.calculated.trip.hasData)
    }

    /** Кадр без лічильників не має потрапляти в історію. */
    @Test
    fun `readings without counters are not recorded`() {
        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")
        GeneralData.updateBms(BmsData(displaySoc = 80.0))

        assertTrue(GeneralData.state.value.tripHistory.isEmpty)
    }

    /** Поки з'єднання немає, історія не пишеться: це були б дані з нізвідки. */
    @Test
    fun `nothing is recorded while disconnected`() {
        reading(discharged = 5987.6, km = 50_000.0)

        assertTrue(GeneralData.state.value.tripHistory.isEmpty)
    }

    @Test
    fun `consumption per distance stays absent while the odometer is silent`() {
        GeneralData.updateConnection(ConnectionState.Connected, "Підключено")

        reading(discharged = 100.0)
        now = 15 * 60_000
        reading(discharged = 102.5)

        val trip = GeneralData.state.value.calculated.trip
        assertEquals(2.5, trip.consumedKwh, 0.0001)
        assertEquals(null, trip.kwhPer100Km)
        assertEquals(10.0, trip.averagePowerKw!!, 0.0001)
    }
}
