package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VehicleBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        GeneralData.reset()
        VehicleBlock().start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    @Test
    fun `monitor lines become vehicle readings`() {
        GeneralData.publishMonitorLines(listOf("4F0 00 5A 00 00 00 B3 C1 1C"), "4F0")
        GeneralData.publishMonitorLines(listOf("653 00 00 00 00 00 64 00 00"), "653")

        val vehicle = GeneralData.state.value.vehicle
        assertEquals(188_459.5, vehicle.odometerKm, 0.001)
        assertEquals(45.0, vehicle.speedKmh, 0.001)
        assertEquals(10.0, vehicle.ambientTempC, 0.001)
    }

    /** Кадри від клонів з розбитим ID мають дійти так само. */
    @Test
    fun `a padded id from a cheap clone is still decoded`() {
        GeneralData.publishMonitorLines(listOf("00 00 06 53 00 00 00 00 00 64 00 00"), "653")

        assertEquals(10.0, GeneralData.state.value.vehicle.ambientTempC, 0.001)
    }

    @Test
    fun `service lines in the stream are skipped`() {
        GeneralData.publishMonitorLines(listOf("SEARCHING...", "STOPPED", "BUFFER FULL", ""), "4F0")

        assertFalse(GeneralData.state.value.vehicle.hasOdometer)
    }

    /** Наступні вікна доповнюють картину, а не стирають попередню. */
    @Test
    fun `a later window adds to what was already read`() {
        GeneralData.publishMonitorLines(listOf("4F0 00 5A 00 00 00 B3 C1 1C"), "4F0")
        GeneralData.publishMonitorLines(listOf("594 00 00 00 00 00 A0 03 00"), "594")

        val vehicle = GeneralData.state.value.vehicle
        assertTrue(vehicle.hasOdometer)
        assertEquals(80.3, vehicle.displaySocPercent, 0.001)
    }

    @Test
    fun `unknown ids in the stream change nothing`() {
        GeneralData.publishMonitorLines(listOf("7FF 01 02 03 04 05 06 07 08"), "7FF")

        assertFalse(GeneralData.state.value.vehicle.hasOdometer)
    }

    /**
     * Головне з поля: адаптер віддає кадр БЕЗ ID. Такий рядок належить тому,
     * на кого стояв фільтр — саме так пробіг і дістається з реальної шини.
     */
    @Test
    fun `a headerless line is attributed to the filtered id`() {
        GeneralData.publishMonitorLines(listOf("00 5A 00 00 00 B3 C1 1C"), "4F0")

        assertEquals(188_459.5, GeneralData.state.value.vehicle.odometerKm, 0.001)
    }

    /**
     * Фільтр «AT CRA» пропускає й сусідні ID: якщо адаптер назвав ID сам,
     * кадр беремо за його ID, а не за тим, що замовляли.
     */
    @Test
    fun `a line naming its own id is decoded under that id`() {
        GeneralData.publishMonitorLines(listOf("653 00 00 00 00 00 64 00 00"), "4F0")

        assertFalse(GeneralData.state.value.vehicle.hasOdometer)
        assertEquals(10.0, GeneralData.state.value.vehicle.ambientTempC, 0.001)
    }
}
