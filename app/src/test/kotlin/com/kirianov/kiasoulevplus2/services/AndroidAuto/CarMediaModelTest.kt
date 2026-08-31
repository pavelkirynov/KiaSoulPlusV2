package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Data.ClockStatus
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.Data.WindowStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarMediaModelTest {

    private fun connected() = State(
        connection = ConnectionState.Connected,
        bms = BmsData(
            displaySoc = 85.5,
            batteryVoltage = 375.0,
            batteryCurrent = -12.4,
            batteryTempC = 28.0,
            cumulativeEnergyChargedKwh = 73_445.6,
            cumulativeEnergyDischargedKwh = 73_261.8,
        ),
        vehicle = VehicleData(
            odometerKm = 188_459.6,
            speedKmh = 96.0,
            displaySocPercent = 85.5,
            rangeKm = 145,
            ambientTempC = 22.5,
        ),
        calculated = CalculatedData(
            powerKw = -4.65,
            minCellVoltage = 3.90,
            maxCellVoltage = 3.94,
            cellDeltaVolts = 0.04,
            trip = WindowStats(
                distanceKm = 65.4,
                durationMs = 48 * 60_000L,
                consumedKwh = 10.0,
                recoveredKwh = 0.32,
            ),
        ),
    )

    @Test
    fun `the root lists the four sections`() {
        val root = CarMediaModel.childrenOf(CarMediaModel.ROOT_ID, connected())

        assertEquals(
            listOf(
                CarMediaModel.BATTERY_ID,
                CarMediaModel.PERFORMANCE_ID,
                CarMediaModel.ENERGY_ID,
                CarMediaModel.TRIP_ID,
            ),
            root.map { it.id },
        )
    }

    /** Інакше водій не зрозуміє, чому всі значення раптом «--». */
    @Test
    fun `the root shows whether the adapter is connected`() {
        val offline = CarMediaModel.childrenOf(CarMediaModel.ROOT_ID, State())

        assertTrue(offline.first().subtitle!!.contains("Немає зв'язку"))
        assertTrue(
            CarMediaModel.childrenOf(CarMediaModel.ROOT_ID, connected()).first().subtitle!!
                .contains("Підключено"),
        )
    }

    /**
     * Playable-рядок відкрив би екран «зараз грає», якого тут немає й бути не може.
     * Тому все browsable.
     */
    @Test
    fun `no row is playable`() {
        val everything = listOf(
            CarMediaModel.ROOT_ID,
            CarMediaModel.BATTERY_ID,
            CarMediaModel.PERFORMANCE_ID,
            CarMediaModel.ENERGY_ID,
            CarMediaModel.TRIP_ID,
        ).flatMap { CarMediaModel.childrenOf(it, connected()) }

        assertTrue(everything.isNotEmpty())
        assertTrue(everything.all { it.browsable })
    }

    @Test
    fun `the battery section carries the readings`() {
        val rows = CarMediaModel.childrenOf(CarMediaModel.BATTERY_ID, connected())
            .associate { it.title to it.subtitle }

        assertEquals("85.5 %", rows["SOC панелі"])
        assertEquals("375.0 В", rows["Напруга"])
        assertEquals("28.0 °C", rows["Температура ВВБ"])
        assertEquals("145 км", rows["Запас ходу"])
    }

    @Test
    fun `the trip section carries consumption per hundred kilometres`() {
        val rows = CarMediaModel.childrenOf(CarMediaModel.TRIP_ID, connected())
            .associate { it.title to it.subtitle }

        // (10.0 - 0.32) кВт·год на 65.4 км -> 14.8 кВт·год/100 км
        assertEquals("14.8 кВт·год/100 км", rows["Витрата"])
        assertEquals("65.4 км", rows["Пройдено"])
        assertNotNull(rows["Час"])
    }

    /** Без даних рядки лишаються на місці з познакою «--», а не зникають. */
    @Test
    fun `an empty state keeps the rows and marks them as missing`() {
        val sections = listOf(
            CarMediaModel.BATTERY_ID,
            CarMediaModel.PERFORMANCE_ID,
            CarMediaModel.ENERGY_ID,
            CarMediaModel.TRIP_ID,
        )

        sections.forEach { section ->
            val rows = CarMediaModel.childrenOf(section, State())
            assertTrue("Розділ $section спорожнів", rows.isNotEmpty())
            assertTrue(
                "Розділ $section показав значення без даних",
                rows.all { it.subtitle == CarMediaModel.NO_DATA_TEXT },
            )
        }
    }

    /** Хост інколи питає збережений id, якого вже немає. */
    @Test
    fun `an unknown id falls back to the root`() {
        assertEquals(
            CarMediaModel.childrenOf(CarMediaModel.ROOT_ID, connected()),
            CarMediaModel.childrenOf("что-то-старое", connected()),
        )
    }

    /**
     * Збитий годинник магнітоли — найчастіша причина, чому Android Auto не
     * під'єднується під РЕБ. У списку хоста написати це можна лише в назві рядка.
     */
    @Test
    fun `a drifted car clock is called out in the row title`() {
        val state = connected().let {
            it.copy(
                vehicle = it.vehicle.copy(clockSecondsOfDay = 12 * 3600),
                calculated = it.calculated.copy(
                    clock = ClockStatus(driftSeconds = -37 * 60),
                ),
            )
        }

        val clockRow = CarMediaModel.childrenOf(CarMediaModel.PERFORMANCE_ID, state)
            .single { it.id == "clock" }

        assertEquals("12:00:00", clockRow.subtitle)
        assertTrue(clockRow.title, clockRow.title.contains("-37 хв"))
    }

    /** Кілька секунд різниці — це округлення, а не збитий годинник. */
    @Test
    fun `a clock within a few seconds is not called out`() {
        val state = connected().let {
            it.copy(
                vehicle = it.vehicle.copy(clockSecondsOfDay = 12 * 3600),
                calculated = it.calculated.copy(clock = ClockStatus(driftSeconds = 3)),
            )
        }

        val clockRow = CarMediaModel.childrenOf(CarMediaModel.PERFORMANCE_ID, state)
            .single { it.id == "clock" }

        assertEquals("Годинник авто", clockRow.title)
    }

    /**
     * Найважливіше з поля: якщо годинник іде з іншою швидкістю, це кварц магнітоли,
     * і вимикати GPS безглуздо. Рядок мусить казати саме про хід, а не про розбіжність.
     */
    @Test
    fun `a clock running at the wrong rate is reported as a rate fault`() {
        val state = connected().let {
            it.copy(
                vehicle = it.vehicle.copy(clockSecondsOfDay = 12 * 3600),
                calculated = it.calculated.copy(
                    clock = ClockStatus(driftSeconds = 300, rateSecondsPerHour = 145.0),
                ),
            )
        }

        val clockRow = CarMediaModel.childrenOf(CarMediaModel.PERFORMANCE_ID, state)
            .single { it.id == "clock" }

        assertTrue(clockRow.title, clockRow.title.contains("хід +145 с/год"))
    }
}
