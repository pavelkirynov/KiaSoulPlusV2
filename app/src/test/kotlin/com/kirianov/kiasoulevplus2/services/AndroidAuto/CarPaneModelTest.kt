package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.State
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class CarPaneModelTest {

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.GERMANY)

    @After
    fun tearDown() = Locale.setDefault(originalLocale)

    private fun connectedState() = State(
        bms = BmsData(
            displaySoc = 80.0,
            batteryVoltage = 366.0,
            batteryCurrent = -50.0,
            batteryTempC = 25.0,
        ),
        calculated = CalculatedData(powerKw = -18.3),
        connection = ConnectionState.Connected,
    )

    @Test
    fun `renders every metric once data has arrived`() {
        val model = CarPaneModel.from(connectedState())

        assertEquals("Kia Soul EV", model.title)
        assertEquals("80.0 %", model.soc)
        assertEquals("366.0 В | -50.0 А", model.voltageAndCurrent)
        assertEquals("-18.30 кВт", model.power)
        assertEquals("25.0 °C", model.temperature)
    }

    @Test
    fun `shows placeholders instead of a misleading minus one before the first read`() {
        val model = CarPaneModel.from(State())

        assertEquals(CarPaneModel.NO_DATA_TEXT, model.soc)
        assertEquals(CarPaneModel.NO_DATA_TEXT, model.voltageAndCurrent)
        assertEquals(CarPaneModel.NO_DATA_TEXT, model.power)
        assertEquals(CarPaneModel.NO_DATA_TEXT, model.temperature)
    }

    @Test
    fun `says in the title when there is no link to the adapter`() {
        val model = CarPaneModel.from(State())
        assertEquals("Kia Soul EV — немає зв'язку", model.title)
    }

    /**
     * Екран перемальовується лише на зміну цієї моделі, тож рівність за однакових
     * даних — те, що утримує кількість invalidate() у межах ліміту хоста.
     */
    @Test
    fun `equal states produce an equal model so the screen is not redrawn`() {
        assertEquals(CarPaneModel.from(connectedState()), CarPaneModel.from(connectedState()))
    }

    @Test
    fun `a changed measurement produces a different model`() {
        val changed = connectedState().let { it.copy(bms = it.bms.copy(displaySoc = 79.5)) }
        assertNotEquals(CarPaneModel.from(connectedState()), CarPaneModel.from(changed))
    }
}
