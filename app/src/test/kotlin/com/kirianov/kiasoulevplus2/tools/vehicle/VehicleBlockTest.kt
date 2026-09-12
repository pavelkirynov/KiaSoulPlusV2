package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
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

    private var now = 0L

    @Before
    fun setUp() {
        GeneralData.reset()
        VehicleBlock(nowMs = { now }).start(scope)
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

    // --- Зарядка, про яку кадр 581 не оголошує ------------------------------------

    /**
     * Швидка зарядка постійним струмом: бортове зарядне в ній не бере участі, тож
     * кадр 581 мовчить усю сесію. Саме так одна зарядка на 8 кВт і пройшла повз
     * облік. Лишається струм: довге рівне приймання на місці — це зарядка.
     */
    @Test
    fun `a long steady current into a parked car reads as charging`() {
        connect()
        stopped()

        readCurrent(22.0)
        assertFalse("Три хвилини ще не минули", charging())

        now = 200_000
        readCurrent(22.0)

        assertTrue(charging())
    }

    /** Рекуперація — це секунди, і вона не має права виглядати як зарядка. */
    @Test
    fun `a burst of regen is not charging`() {
        connect()
        moving(60.0)

        readCurrent(60.0)
        now = 200_000
        readCurrent(60.0)

        assertFalse(charging())
    }

    /** Рух знімає ознаку негайно: авто, що їде, точно не стоїть на зарядці. */
    @Test
    fun `moving off cancels the sensed charge`() {
        connect()
        stopped()
        readCurrent(22.0)
        now = 200_000
        readCurrent(22.0)
        assertTrue(charging())

        moving(30.0)
        now = 210_000
        readCurrent(22.0)

        assertFalse(charging())
    }

    /**
     * Розрив зв'язку знімає ознаку заряджання цілком — і ту, що з кадру 581, теж.
     *
     * Інакше виходило так: телефон від'єднався на зарядці, авто поїхало, телефон
     * під'єднався вже на ходу — і приріст лічильника, який за поїздку набігав від
     * рекуперації, лягав у ту саму, досі відкриту сесію зарядки.
     */
    @Test
    fun `losing the link clears the charging flag`() {
        connect()
        GeneralData.publishMonitorLines(listOf("581 00 00 00 01 00 0D 00 10"), "581")
        assertTrue(charging())

        GeneralData.updateConnection(ConnectionState.Disconnected, "обрив")

        assertFalse(charging())
    }

    private fun connect() = GeneralData.updateConnection(ConnectionState.Connected, "тест")

    private fun charging(): Boolean = GeneralData.state.value.vehicle.charging.isCharging

    private fun stopped() = GeneralData.publishMonitorLines(listOf("4F0 00 00 00 00 00 B3 C1 1C"), "4F0")

    private fun moving(kmh: Double) {
        val raw = (kmh * 2).toInt()
        GeneralData.publishMonitorLines(
            listOf("4F0 00 %02X 00 00 00 B3 C1 1C".format(raw and 0xFF)),
            "4F0",
        )
    }

    private var sequence = 0L

    private fun readCurrent(amps: Double) {
        // Струм у домовленості застосунку: додатний — у батарею.
        GeneralData.publishBatteryFrames(listOf("2101"), listOf("resp${sequence++}"))
        GeneralData.updateBms(BmsData(displaySoc = 50.0, batteryCurrent = amps))
    }
}
