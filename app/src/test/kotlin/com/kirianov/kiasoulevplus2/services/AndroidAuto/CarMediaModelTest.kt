package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.MlPrediction
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.Data.WindowStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /**
     * Розділ «на весь екран» стоїть окремо від решти: його елементи — не папки, а
     * «треки». Дотик по такому елементу вмикає екран плеера з нашою обкладинкою, і
     * це єдина поверхня, де графік читається в машині.
     */
    @Test
    fun `the full screen section offers pictures, not folders`() {
        val items = CarMediaModel.childrenOf(CarMediaModel.SCREEN_ID, connected())

        assertEquals(CarChartModel.PICTURES.size, items.size)
        assertTrue("це мають бути треки, а не папки", items.none { it.browsable })
    }

    @Test
    fun `the root lists every section`() {
        val root = CarMediaModel.childrenOf(CarMediaModel.ROOT_ID, connected())

        assertEquals(
            listOf(
                CarMediaModel.BATTERY_ID,
                CarMediaModel.PERFORMANCE_ID,
                CarMediaModel.ENERGY_ID,
                CarMediaModel.TRIP_ID,
                CarMediaModel.SCREEN_ID,
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

    @Test
    fun `the energy section carries the charge figures`() {
        val state = connected().copy(
            charge = ChargeLog(lastSessionKwh = 31.4, todayKwh = 33.9, hasBaseline = true),
        )

        val rows = CarMediaModel.childrenOf(CarMediaModel.ENERGY_ID, state)
            .associate { it.title to it.subtitle }

        assertEquals("31.4 кВт·год", rows["Остання зарядка"])
        assertEquals("33.9 кВт·год", rows["Заряджено за добу"])
    }

    /** Поки зарядок не було, рядки лишаються з познакою, а не з нулем. */
    @Test
    fun `charge rows without a charge read as missing`() {
        val rows = CarMediaModel.childrenOf(CarMediaModel.ENERGY_ID, connected())
            .associate { it.title to it.subtitle }

        assertEquals(CarMediaModel.NO_DATA_TEXT, rows["Остання зарядка"])
        assertEquals(CarMediaModel.NO_DATA_TEXT, rows["Заряджено за добу"])
    }

    // --- Прилади на іконках -------------------------------------------------------

    /**
     * Дуга заряду мусить показувати РЕАЛЬНИЙ відсоток, коли прогноз його знає.
     * У цьому й сенс застосунку: панель бреше, а на іконці має бути правда.
     */
    @Test
    fun `the charge gauge prefers the real percent over the panel`() {
        val state = connected().let { base ->
            base.copy(
                ml = base.ml.copy(
                    prediction = MlPrediction(
                        rangeKm = 180.0,
                        rangeFromKm = 150.0,
                        rangeToKm = 210.0,
                        realPercent = 62.0,
                        usableEnergyRemainingKwh = 30.0,
                        whPerKm = 165.0,
                    ),
                ),
            )
        }

        val gauge = CarMediaModel.gaugeFor(CarMediaModel.BATTERY_ID, state)

        assertEquals(CarGauge.Kind.Arc, gauge.kind)
        assertEquals(0.62, gauge.fill, 0.001)
        assertEquals("62 %", gauge.label)
        assertEquals("180 км", gauge.caption)
    }

    /** Поки прогнозу немає, беремо панельний — але кажемо, що він панельний. */
    @Test
    fun `without a prediction the gauge falls back to the panel`() {
        val gauge = CarMediaModel.gaugeFor(CarMediaModel.BATTERY_ID, connected())

        assertEquals("за панеллю", gauge.caption)
    }

    /**
     * Смуга потужності від центру: розряд праворуч, рекуперація ліворуч.
     * Домовленість застосунку — від'ємна потужність означає розряд.
     */
    @Test
    fun `the power gauge puts spending to the right and regen to the left`() {
        val base = connected()
        val spending = base.copy(calculated = base.calculated.copy(powerKw = -40.0))
        val regen = base.copy(calculated = base.calculated.copy(powerKw = 20.0))

        val out = CarMediaModel.gaugeFor(CarMediaModel.PERFORMANCE_ID, spending)
        val back = CarMediaModel.gaugeFor(CarMediaModel.PERFORMANCE_ID, regen)

        assertTrue("Розряд мусить бути праворуч: ${out.fill}", out.fill > 0.0)
        assertEquals(0.5, out.fill, 0.001)
        assertEquals("віддає", out.caption)
        assertTrue("Рекуперація мусить бути ліворуч: ${back.fill}", back.fill < 0.0)
        assertEquals("приймає", back.caption)
    }

    /** За межі шкали смуга не вилазить: інакше вона намалювалася б повз іконку. */
    @Test
    fun `the power gauge is clamped to its scale`() {
        val base = connected()
        val huge = base.copy(calculated = base.calculated.copy(powerKw = -300.0))

        assertEquals(1.0, CarMediaModel.gaugeFor(CarMediaModel.PERFORMANCE_ID, huge).fill, 0.001)
    }

    /**
     * Шкали немає там, де вона нічого не означає: у пробігу немає «повного бака»,
     * у зарядці в кВт·год — краю. Але КАРТИНКА є всюди, і це не примха.
     *
     * У сітці хост підставляє замість відсутньої іконки власну заглушку —
     * трикутник зі знаком оклику. На машинному екрані водій читає його як
     * несправність авто, якої немає. Тому там, де шкала безглузда, малюємо просто
     * число.
     */
    @Test
    fun `sections without a natural scale still get a picture`() {
        val energy = CarMediaModel.gaugeFor(CarMediaModel.ENERGY_ID, connected())
        val trip = CarMediaModel.gaugeFor(CarMediaModel.TRIP_ID, connected())

        assertEquals(CarGauge.Kind.Plain, energy.kind)
        assertEquals(CarGauge.Kind.Plain, trip.kind)
    }

    /**
     * Без даних із шини теж малюємо своє: прочерк, сказаний нами, кращий за
     * трикутник, сказаний хостом.
     */
    @Test
    fun `no data still means our own picture, not the host placeholder`() {
        val battery = CarMediaModel.gaugeFor(CarMediaModel.BATTERY_ID, State())
        val power = CarMediaModel.gaugeFor(CarMediaModel.PERFORMANCE_ID, State())

        assertEquals(CarGauge.Kind.Plain, battery.kind)
        assertEquals(CarMediaModel.NO_DATA_TEXT, battery.label)
        assertEquals(CarGauge.Kind.Plain, power.kind)
        assertEquals(CarMediaModel.NO_DATA_TEXT, power.label)
    }
}
