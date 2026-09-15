package com.kirianov.kiasoulevplus2.car.screens

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CarSystems
import com.kirianov.kiasoulevplus2.Data.DriveState
import com.kirianov.kiasoulevplus2.Data.PackHealth
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.TireData
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.Data.WheelSpeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortedValuesTest {

    private fun stateOf(
        bms: BmsData = BmsData(),
        health: PackHealth = PackHealth(),
        systems: CarSystems = CarSystems(),
        vehicle: VehicleData = VehicleData(),
        tires: TireData = TireData(),
    ) = State(
        bms = bms,
        packHealth = health,
        carSystems = systems,
        vehicle = vehicle,
        tires = tires,
    )

    private fun valueOf(state: State, label: String): PortedValue =
        PortedValues.groups(state).flatMap { it.values }.first { it.label == label }

    /** Поки кадр не приходив, кожне поле мусить бути «немає», а не «підозріло». */
    @Test
    fun `an empty state has nothing working and nothing suspect`() {
        val all = PortedValues.groups(stateOf()).flatMap { it.values }

        assertTrue(all.isNotEmpty())
        assertEquals(emptyList<PortedValue>(), all.filter { it.state != PortedState.Missing })
    }

    /**
     * ЦЕ ТЕСТ НА ВИПРАВЛЕНУ ПОМИЛКУ, А НЕ НА ПРАВИЛО.
     *
     * Спершу рівність двох меж вважалася доказом не тих байтів: «брати 90,
     * вливати 90» виглядало неможливим. Жива зарядка показала інше — там той
     * самий байт дав 69 кВт на прийом, — тобто рівність була просто станом
     * спокою, коли обидві межі стоять на стелі. Отже, рівність не вирок, і
     * позначка «підозріло» тут була б неправдою.
     */
    @Test
    fun `equal power limits are the idle ceiling, not a fault`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, availableDischargeKw = 90.0, availableChargeKw = 90.0),
        )

        assertEquals(PortedState.Working, valueOf(state, "Дозволено брати").state)
        val into = valueOf(state, "Дозволено вливати")
        assertEquals(PortedState.Working, into.state)
        assertTrue(into.note, into.note.contains("спокій"))
    }

    /** А ось число, більше за все, на що здатен цей привід, лишається вироком. */
    @Test
    fun `an impossible power limit is still suspect`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, availableDischargeKw = 900.0, availableChargeKw = 45.0),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Дозволено брати").state)
    }

    @Test
    fun `power limits that differ are taken as working`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, availableDischargeKw = 92.0, availableChargeKw = 45.0),
        )

        assertEquals(PortedState.Working, valueOf(state, "Дозволено брати").state)
    }

    /** Нульовий знос на всіх комірках — не «як нова», а не те поле. */
    @Test
    fun `zero deterioration on every cell is suspect`() {
        val state = stateOf(
            health = PackHealth(
                known = true,
                maxTempC = 26.0,
                minTempC = 26.0,
                maxDeteriorationPercent = 0.0,
                maxDeteriorationCell = 1,
                minDeteriorationPercent = 0.0,
                minDeteriorationCell = 1,
            ),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Знос найгіршої").state)
        assertEquals(PortedState.Suspect, valueOf(state, "Знос найкращої").state)
    }

    @Test
    fun `real deterioration numbers are taken as working`() {
        val state = stateOf(
            health = PackHealth(
                known = true,
                maxTempC = 28.0,
                minTempC = 25.0,
                maxDeteriorationPercent = 96.5,
                maxDeteriorationCell = 12,
                minDeteriorationPercent = 99.8,
                minDeteriorationCell = 40,
            ),
        )

        assertEquals(PortedState.Working, valueOf(state, "Знос найгіршої").state)
    }

    /**
     * СПРАВЖНЯ СУПЕРЕЧНІСТЬ: пакет «рівний», хоч його ж модулі різняться.
     *
     * Саме це й показала машина — максимум 29 і мінімум 29 при модулях від 24 до
     * 28. Обидва числа не можуть бути межами того самого пакета.
     */
    @Test
    fun `a flat pack with unequal modules is suspect`() {
        val state = stateOf(
            bms = BmsData(
                displaySoc = 26.0,
                moduleTempsC = listOf(28.0, 25.0, 26.0, 27.0, 27.0, 26.0, 24.0, 26.0),
            ),
            health = PackHealth(known = true, maxTempC = 29.0, minTempC = 29.0, inletTempC = 30.0),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Максимум по пакету").state)
        // Самі модулі при цьому поза підозрою: у них є розкид, і він правдоподібний.
        assertEquals(PortedState.Working, valueOf(state, "Вісім модулів").state)
        // Як і вхід контуру: у журналі він ходив окремо від пари «максимум-мінімум».
        assertEquals(PortedState.Working, valueOf(state, "Вхід контуру").state)
    }

    /** А вирівняний пакет із вирівняними модулями — цілком можливий стан. */
    @Test
    fun `a flat pack with flat modules is not blamed`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, moduleTempsC = List(8) { 26.0 }),
            health = PackHealth(known = true, maxTempC = 26.0, minTempC = 26.0, inletTempC = 26.0),
        )

        assertEquals(PortedState.Working, valueOf(state, "Максимум по пакету").state)
    }

    @Test
    fun `module temperatures with a spread are taken as working`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, moduleTempsC = listOf(25.0, 26.0, 26.0, 27.0)),
            health = PackHealth(known = true, maxTempC = 27.0, minTempC = 25.0, inletTempC = 24.0),
        )

        assertEquals(PortedState.Working, valueOf(state, "Вісім модулів").state)
        assertEquals(PortedState.Working, valueOf(state, "Максимум по пакету").state)
    }

    /**
     * ГРУБИЙ перекіс — ознака хибного дільника, і його треба ловити.
     *
     * А от помірна розбіжність ознакою НЕ Є: два вікна монітора знімаються за
     * п'ять-десять секунд одне від одного, і за цей час швидкість на розгоні
     * справді змінюється. Живий журнал дав двадцять три пари з відношенням від
     * 0.56 до 1.61 при правильному дільнику — див. наступний тест.
     */
    @Test
    fun `wheel speeds that are wildly off frame 4F0 are suspect`() {
        val state = stateOf(
            systems = CarSystems(
                wheels = WheelSpeeds(
                    known = true,
                    frontLeftKmh = 300.0,
                    frontRightKmh = 300.0,
                    rearLeftKmh = 300.0,
                    rearRightKmh = 300.0,
                ),
            ),
            vehicle = VehicleData(speedKmh = 50.0),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Чотири колеса").state)
    }

    /** Розбіжність від часу зняття двох вікон вироком не є. */
    @Test
    fun `a moderate disagreement with frame 4F0 is not a verdict`() {
        val state = stateOf(
            systems = CarSystems(
                wheels = WheelSpeeds(
                    known = true,
                    frontLeftKmh = 82.0,
                    frontRightKmh = 82.5,
                    rearLeftKmh = 82.3,
                    rearRightKmh = 82.4,
                ),
            ),
            vehicle = VehicleData(speedKmh = 62.0),
        )

        assertEquals(PortedState.Working, valueOf(state, "Чотири колеса").state)
    }

    @Test
    fun `wheel speeds that agree with frame 4F0 are working`() {
        val state = stateOf(
            systems = CarSystems(
                wheels = WheelSpeeds(
                    known = true,
                    frontLeftKmh = 49.0,
                    frontRightKmh = 51.0,
                    rearLeftKmh = 49.5,
                    rearRightKmh = 50.5,
                ),
            ),
            vehicle = VehicleData(speedKmh = 50.0),
        )

        assertEquals(PortedState.Working, valueOf(state, "Чотири колеса").state)
    }

    /**
     * На місці звіряти нічого: без швидкості з кадру 4F0 вердикт мусить лишатися
     * «працює» з поясненням, а не ставати «підозріло».
     */
    @Test
    fun `speed of frame 4F2 is not blamed while the car stands still`() {
        val state = stateOf(
            systems = CarSystems(drive = DriveState(known = true, speedKmh = 0.0)),
        )

        val value = valueOf(state, "Швидкість із кадру 4F2")
        assertEquals(PortedState.Working, value.state)
        assertTrue(value.note.contains("лише на ходу"))
    }

    /** Тиску немає, поки блок 7A0 не відповів, — і це «немає», а не «підозріло». */
    @Test
    fun `tyre pressure is missing until the module answers`() {
        val value = valueOf(stateOf(), "Тиск, бар")

        assertEquals(PortedState.Missing, value.state)
        assertTrue(value.note, value.note.contains("журналі"))
    }

    @Test
    fun `tyre pressure that arrived is shown with its spread`() {
        val state = stateOf(
            tires = TireData(
                known = true,
                pressuresBar = listOf(2.3, 2.35, 2.25, 2.3),
                tempsC = listOf(24.0, 25.0, 24.0, 26.0),
            ),
        )

        val value = valueOf(state, "Тиск, бар")
        assertEquals(PortedState.Working, value.state)
        assertTrue(value.text, value.text.contains("2.30"))
        assertTrue(value.note, value.note.contains("0.10"))
    }

    /** Напруга комірки поза межами хімії — ознака не тих байтів. */
    @Test
    fun `impossible cell voltages are suspect`() {
        val state = stateOf(
            bms = BmsData(
                displaySoc = 26.0,
                maxCellVolts = 65.0,
                maxCellNumber = 3,
                minCellVolts = 60.0,
                minCellNumber = 44,
            ),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Найвища комірка").state)
    }

    @Test
    fun `plausible cell voltages are working`() {
        val state = stateOf(
            bms = BmsData(
                displaySoc = 26.0,
                maxCellVolts = 3.72,
                maxCellNumber = 3,
                minCellVolts = 3.66,
                minCellNumber = 44,
            ),
        )

        val value = valueOf(state, "Найнижча комірка")
        assertEquals(PortedState.Working, value.state)
        assertTrue(value.note.contains("60 мВ"))
    }
}
