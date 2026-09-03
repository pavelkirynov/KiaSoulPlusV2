package com.kirianov.kiasoulevplus2.tools.energy

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.ChargingState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.VehicleData
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Крива ємності міряється різницею пожиттєвих лічильників — і саме тому їй
 * байдуже і на обрив зв'язку, і на знак струму. Тут це й перевіряється.
 */
class EnergyCurveTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private class MemoryStore(var saved: LevelsSnapshot? = null) : EnergyStore {
        var cleared = 0
        override fun load(): LevelsSnapshot? = saved
        override fun save(snapshot: LevelsSnapshot) {
            saved = snapshot
        }

        override fun clear() {
            saved = null
            cleared++
        }
    }

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    // --- Модель ------------------------------------------------------------------

    /**
     * Основний випадок: шкала пройшла 10 %, з батареї пішло 5 кВт·год. Отже на
     * цій ділянці 0.5 кВт·год на відсоток, тобто повна батарея — 50 кВт·год.
     */
    @Test
    fun `one measurement sets the slope where it was measured`() {
        val levels = EnergyLevels()

        assertTrue(levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0))

        assertEquals(0.5, levels.rateAt(85.0)!!, 0.001)
        assertEquals(80.0, levels.measuredFromPercent!!, 0.001)
        assertEquals(90.0, levels.measuredToPercent!!, 0.001)
        assertEquals(10.0, levels.coveredPercent, 0.001)
    }

    /**
     * Те саме місце шкали, пройдене вдруге з іншим результатом, має усереднитися
     * з вагою пройденого — саме про це й просили: повторні значення в інший час
     * не замінюють попередні, а уточнюють їх.
     */
    @Test
    fun `a second pass over the same percent is averaged, not replaced`() {
        val levels = EnergyLevels()

        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0) // 0.5 на відсоток
        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 3.0) // 0.3 на відсоток

        assertEquals(0.4, levels.rateAt(85.0)!!, 0.001)
        assertEquals(2, levels.samples)
    }

    /** Довший прохід важить більше за короткий: у нього більше даних. */
    @Test
    fun `a longer pass weighs more than a short one`() {
        val levels = EnergyLevels()

        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)  // весь кошик 85, нахил 0.5
        levels.learn(fromPercent = 85.5, toPercent = 85.0, netKwh = 0.05) // пів кошика, нахил 0.1

        // Кошик 85 бачив 1 % при 0.5 і 0.5 % при 0.1 — з вагою це 0.367, а не
        // просте середнє 0.3: коротший прохід важить рівно вдвічі менше.
        val rate = levels.rateAt(85.2)!!
        assertEquals(0.367, rate, 0.005)
    }

    /** Замір розкладається по кошиках пропорційно пройденій у кожному частині. */
    @Test
    fun `a measurement spanning several percents is spread across them`() {
        val levels = EnergyLevels()

        levels.learn(fromPercent = 93.7, toPercent = 91.2, netKwh = 1.25)

        // 2.5 % шкали на 1.25 кВт·год — це 0.5 на відсоток у кожному з кошиків.
        assertEquals(0.5, levels.rateAt(92.0)!!, 0.001)
        assertEquals(0.5, levels.rateAt(93.0)!!, 0.001)
        assertNull("Кошик 90 не міряли", levels.rateAt(90.0))
    }

    /** Нефізичний нахил — це помилка читання, а не батарея. */
    @Test
    fun `an impossible slope is refused`() {
        val levels = EnergyLevels()

        // 10 % шкали на 30 кВт·год — це батарея на 300 кВт·год.
        assertFalse(levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 30.0))
        assertEquals(0, levels.samples)
    }

    /** Зарядка (шкала вгору) в криву не йде: тут міряється тільки розряд. */
    @Test
    fun `a rising scale is refused`() {
        val levels = EnergyLevels()

        assertFalse(levels.learn(fromPercent = 80.0, toPercent = 90.0, netKwh = 5.0))
    }

    /**
     * Крива мусить бути суцільною від 0 до 100 %, інакше її нема як нарисувати.
     * Але кожна точка знає, вимір це чи доведення середнім нахилом.
     */
    @Test
    fun `the curve is continuous and marks what was measured`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)

        val curve = levels.curve()

        assertEquals(101, curve.size)
        assertEquals(0.0, curve.first().energyKwh, 0.001)
        assertEquals(50.0, curve.last().energyKwh, 0.001)
        assertTrue("Виміряне не позначене", curve.any { it.socPercent == 85.0 && it.measured })
        assertTrue("Доведене позначене як вимір", curve.any { it.socPercent == 50.0 && !it.measured })
    }

    @Test
    fun `what was learned survives a restart`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)

        val restored = EnergyLevels().apply { restore(levels.snapshot()) }

        assertEquals(0.5, restored.rateAt(85.0)!!, 0.001)
        assertEquals(1, restored.samples)
    }

    @Test
    fun `the curve can be written and read back`() {
        val dir = File.createTempFile("curve", "dir").apply { delete(); mkdirs() }
        try {
            val levels = EnergyLevels()
            levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)
            val store = FileEnergyStore(dir)

            store.save(levels.snapshot())
            val restored = EnergyLevels().apply { restore(store.load()!!) }

            assertEquals(0.5, restored.rateAt(85.0)!!, 0.001)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Блок --------------------------------------------------------------------

    private fun publish(socPercent: Double, dischargedKwh: Double, chargedKwh: Double, charging: Boolean = false) {
        GeneralData.updateBms(
            BmsData(
                displaySoc = 50.0,
                cumulativeEnergyDischargedKwh = dischargedKwh,
                cumulativeEnergyChargedKwh = chargedKwh,
            ),
        )
        GeneralData.updateVehicle(
            VehicleData(
                preciseSocPercent = socPercent,
                charging = ChargingState(isCharging = charging),
            ),
        )
    }

    /** Поїздка: шкала вниз, лічильник відданої вгору — це і є замір. */
    @Test
    fun `driving produces a measurement`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 60_000
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 501.0)

        val curve = GeneralData.state.value.curve
        assertEquals(1, curve.samples)
        // Віддано 6, прийнято 1 — отже пішло 5 кВт·год на 10 % шкали.
        assertEquals(50.0, curve.fullKwh, 0.5)
    }

    /** Поки лічильник не набрав кіловат-години, замір брати рано. */
    @Test
    fun `a step too small to measure is not taken`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 10_000
        publish(socPercent = 89.5, dischargedKwh = 1_000.3, chargedKwh = 500.0)

        assertEquals(0, GeneralData.state.value.curve.samples)
    }

    /** Зарядка — не замір: шкала йде вгору, і різниця лічильників означає інше. */
    @Test
    fun `charging is not measured into the curve`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 50.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 60_000
        publish(socPercent = 60.0, dischargedKwh = 1_000.0, chargedKwh = 510.0, charging = true)

        assertEquals(0, GeneralData.state.value.curve.samples)
    }

    /**
     * Довга пауза між читаннями робить інтервал непридатним: за неї авто могло і
     * проїхати, і зарядитися, а лічильники цього не розділяють.
     */
    @Test
    fun `an interval across a long pause is dropped`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 10 * 60 * 60 * 1000L
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 501.0)

        assertEquals(0, GeneralData.state.value.curve.samples)
    }

    /**
     * А от сама КРИВА обрив переживає: вона лежить у файлі, і після
     * перепідключення заміри просто продовжують її, а не починають з нуля.
     */
    @Test
    fun `the curve is restored from the file, not started over`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)
        val store = MemoryStore(saved = levels.snapshot())

        EnergyBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)

        val curve = GeneralData.state.value.curve
        assertEquals(1, curve.samples)
        assertEquals(50.0, curve.fullKwh, 0.5)
    }

    @Test
    fun `the curve can be forgotten on request`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)
        val store = MemoryStore(saved = levels.snapshot())
        EnergyBlock(store, ioDispatcher = Dispatchers.Unconfined).start(scope)

        GeneralData.requestCurveReset()

        assertEquals(0, GeneralData.state.value.curve.samples)
        assertEquals(1, store.cleared)
    }
}
