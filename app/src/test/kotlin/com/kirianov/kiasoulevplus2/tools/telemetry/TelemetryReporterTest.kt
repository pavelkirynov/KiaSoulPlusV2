package com.kirianov.kiasoulevplus2.tools.telemetry

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.Garage
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryReporterTest {

    private fun state(
        vin: String = "KNAJX81EFG7008018",
        soc: Double = 55.0,
        cells: List<Double> = emptyList(),
    ) = State(
        bms = BmsData(
            displaySoc = soc,
            batteryVoltage = 366.0,
            batteryCurrent = -12.0,
            moduleTempsC = listOf(21.0, 22.0),
            minCellVolts = 3.81,
            maxCellVolts = 3.86,
            minCellNumber = 42,
            maxCellNumber = 7,
            cumulativeEnergyChargedKwh = 27_100.0,
        ),
        cells = CellData(cellVoltages = cells),
        vehicle = VehicleData(odometerKm = 189_420.0),
        garage = Garage(activeVin = vin),
    )

    /** Без VIN рядок нікуди прив'язати — не шлемо нічого. */
    @Test
    fun `no vin means no row`() {
        assertNull(TelemetryReporter.buildRow(state(vin = ""), includeCells = false))
    }

    /** Поки BMS мовчить (SOC = NO_DATA), вивантажувати нема чого. */
    @Test
    fun `no bms data means no row`() {
        assertNull(TelemetryReporter.buildRow(state(soc = BmsData.NO_DATA), includeCells = false))
    }

    /** Готовий рядок несе VIN і головні цифри батареї. */
    @Test
    fun `a row carries the vin and headline numbers`() {
        val row = TelemetryReporter.buildRow(state(), includeCells = false)!!
        assertEquals("KNAJX81EFG7008018", row["vin"])
        assertEquals(55.0, row["soc"])
        assertEquals(366.0, row["pack_voltage"])
        assertEquals(-12.0, row["pack_current"])
        assertEquals(27_100.0, row["charged_kwh"])
        assertEquals(189_420.0, row["odometer_km"])
        // Без запиту покомірні напруги не додаються — вони важкі.
        assertFalse(row.containsKey("cell_voltages"))
    }

    /** На вимогу додаються 96 напруг комірок; без комірок ключ не з'являється. */
    @Test
    fun `cells are added only when asked and present`() {
        val ninetySix = List(96) { 3.8 }
        val withCells = TelemetryReporter.buildRow(state(cells = ninetySix), includeCells = true)!!
        assertEquals(ninetySix, withCells["cell_voltages"])

        val askedButEmpty = TelemetryReporter.buildRow(state(cells = emptyList()), includeCells = true)!!
        assertFalse(askedButEmpty.containsKey("cell_voltages"))
    }

    /** Покомірні напруги йдуть рідше: спершу так, потім пауза, за інтервал — знову. */
    @Test
    fun `cells cadence follows the interval`() {
        assertTrue("Першого разу — так", TelemetryReporter.cellsDue(0L, 1_000L))
        assertFalse("Одразу після — ні", TelemetryReporter.cellsDue(1_000L, 60_000L))
        assertTrue(
            "За інтервал — знову",
            TelemetryReporter.cellsDue(1_000L, 1_000L + TelemetryReporter.CELLS_INTERVAL_MS),
        )
    }

    /** Кілька рядків збираються в JSON-масив на один POST. */
    @Test
    fun `rows are packed into a json array`() {
        val a = TelemetryReporter.buildRow(state(soc = 50.0), includeCells = false)!!
        val b = TelemetryReporter.buildRow(state(soc = 51.0), includeCells = false)!!
        val json = TelemetryReporter.toJsonArray(listOf(a, b))
        assertTrue(json.startsWith("[{"))
        assertTrue(json.endsWith("}]"))
        assertTrue(json.contains("\"vin\":\"KNAJX81EFG7008018\""))
    }
}
