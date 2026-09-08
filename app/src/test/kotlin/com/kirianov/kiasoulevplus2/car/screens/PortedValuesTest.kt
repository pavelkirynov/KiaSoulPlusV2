package com.kirianov.kiasoulevplus2.car.screens

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CarSystems
import com.kirianov.kiasoulevplus2.Data.DriveState
import com.kirianov.kiasoulevplus2.Data.PackHealth
import com.kirianov.kiasoulevplus2.Data.State
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
    ) = State(bms = bms, packHealth = health, carSystems = systems, vehicle = vehicle)

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
     * Живий журнал показав «дозволено брати 90 кВт, дозволено вливати 90 кВт».
     * Дві межі різної природи не бувають рівні — це й є ознака не тих байтів.
     */
    @Test
    fun `equal power limits are suspect`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, availableDischargeKw = 90.0, availableChargeKw = 90.0),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Дозволено брати").state)
        assertEquals(PortedState.Suspect, valueOf(state, "Дозволено вливати").state)
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
     * Одинадцять однакових температур — вісім модулів і три межі пакета — це не
     * вирівняний пакет, а один байт, прочитаний одинадцять разів.
     */
    @Test
    fun `identical module and pack temperatures are suspect`() {
        val state = stateOf(
            bms = BmsData(displaySoc = 26.0, moduleTempsC = List(8) { 26.0 }),
            health = PackHealth(known = true, maxTempC = 26.0, minTempC = 26.0, inletTempC = 26.0),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Вісім модулів").state)
        assertEquals(PortedState.Suspect, valueOf(state, "Максимум по пакету").state)
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

    /** Швидкість приходить трьома шляхами, і всі три мусять сходитися. */
    @Test
    fun `wheel speeds that disagree with frame 4F0 are suspect`() {
        val state = stateOf(
            systems = CarSystems(
                wheels = WheelSpeeds(
                    known = true,
                    frontLeftKmh = 90.0,
                    frontRightKmh = 90.0,
                    rearLeftKmh = 90.0,
                    rearRightKmh = 90.0,
                ),
            ),
            vehicle = VehicleData(speedKmh = 50.0),
        )

        assertEquals(PortedState.Suspect, valueOf(state, "Чотири колеса").state)
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
            systems = CarSystems(drive = DriveState(known = true, speedKmh = 0.0, ignitionOn = true)),
        )

        val value = valueOf(state, "Швидкість із кадру 4F2")
        assertEquals(PortedState.Working, value.state)
        assertTrue(value.note.contains("лише на ходу"))
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
