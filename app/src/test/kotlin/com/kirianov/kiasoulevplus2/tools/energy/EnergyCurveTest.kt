package com.kirianov.kiasoulevplus2.tools.energy

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.ChargingState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.Pack
import com.kirianov.kiasoulevplus2.Data.CarProfile
import com.kirianov.kiasoulevplus2.Data.CurvePoint
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
    fun setUp() {
        GeneralData.reset()
        // Ємність пакета задає власник авто на екрані налаштувань. Тут ставимо
        // перепакований пакет цього авто: без цього застосунок узяв би рідні
        // 27 кВт·год — обережне типове значення для машини, про яку ще не питали.
        GeneralData.updateGarage {
            it.copy(
                cars = listOf(CarProfile(vin = VIN, packKwh = Pack.USABLE_CAPACITY_KWH)),
                activeVin = VIN,
                loaded = true,
            )
        }
    }

    /**
     * Стокове авто мусить лишитися стоковим. Поки ємність не задана, крива
     * прив'язується до РІДНОГО пакета, а не до перепакованого: занизити запас ходу
     * означає, що людина зайвий раз зарядиться, а завищити — що вона стане на
     * дорозі.
     */
    @Test
    fun `an unconfigured car is treated as the stock pack, not the repacked one`() {
        GeneralData.updateGarage {
            it.copy(cars = listOf(CarProfile(vin = VIN)), activeVin = VIN, loaded = true)
        }
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 60_000
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 501.0)

        assertEquals(
            Pack.ORIGINAL_CAPACITY_KWH,
            GeneralData.state.value.curve.totalKwh,
            0.001,
        )
    }

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

        levels.learn(fromPercent = 90.0, toPercent = 85.0, netKwh = 2.5)   // нахил 0.5
        levels.learn(fromPercent = 85.5, toPercent = 85.0, netKwh = 0.05)  // нахил 0.1, пів відсотка

        // Кошик 85 бачив цілий відсоток при 0.5 і пів відсотка при 0.1 — з вагою
        // це 0.367, а не просте середнє 0.3: коротший прохід важить удвічі менше.
        assertEquals(0.367, levels.rateAt(85.5)!!, 0.005)
        // Сусідній кошик короткого проходу не бачив узагалі.
        assertEquals(0.5, levels.rateAt(87.5)!!, 0.001)
    }

    /** Замір розкладається по кошиках пропорційно пройденій у кожному частині. */
    @Test
    fun `a measurement spanning several bins is spread across them`() {
        val levels = EnergyLevels()

        // 96 -> 84 % шкали накриває дванадцять кошиків по одному відсотку.
        levels.learn(fromPercent = 96.0, toPercent = 84.0, netKwh = 6.0)

        assertEquals(0.5, levels.rateAt(95.5)!!, 0.001)
        assertEquals(0.5, levels.rateAt(92.0)!!, 0.001)
        assertEquals(0.5, levels.rateAt(84.5)!!, 0.001)
        assertNull("Кошик 97 не міряли", levels.rateAt(97.5))
        assertNull("Кошик 72 не міряли", levels.rateAt(72.0))
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
     * Але кожна точка знає, вимір це чи доведення.
     */
    @Test
    fun `the curve is continuous and marks what was measured`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 90.0, toPercent = 80.0, netKwh = 5.0)

        val curve = levels.curve(nominalKwh = 51.0)

        assertEquals(101, curve.size)
        // Верхня точка — заявлена ємність: звідси крива й будується.
        assertEquals(51.0, curve.last().energyKwh, 0.001)
        assertTrue("Виміряне не позначене", curve.any { it.socPercent == 85.0 && it.measured })
        assertTrue("Доведене позначене як вимір", curve.any { it.socPercent == 50.0 && !it.measured })
    }

    /** Поки не зміряно нічого, крива — рівна лінія від заявленої ємності. */
    @Test
    fun `with nothing measured the curve is the datasheet line`() {
        val curve = EnergyLevels().curve(nominalKwh = 51.0)

        assertEquals(51.0, curve.last().energyKwh, 0.001)
        assertEquals(0.0, curve.first().energyKwh, 0.001)
        assertEquals(25.5, curve.first { it.socPercent == 50.0 }.energyKwh, 0.001)
        assertTrue("Нічого не міряли — все має бути пунктиром", curve.none { it.measured })
    }

    /**
     * ГОЛОВНЕ ПРО ЦЮ КРИВУ: вона будується ЗВЕРХУ ВНИЗ і має право не зійтися.
     *
     * Верхня точка — заявлена ємність. Далі вниз ідуть виміряні нахили, і куди
     * крива сяде на нулі — те й є відповідь. Сіла вище нуля — стільки насправді
     * не набралося; сіла нижче — пакет більший за заявлений.
     *
     * Раніше було навпаки: сума задавалася наперед, а невиміряне тиснулося
     * коефіцієнтом під неї. Така крива фізично не могла суперечити заявленому — і
     * тому нічого про нього не казала.
     */
    @Test
    fun `the curve may land above zero and that is the measurement`() {
        val levels = EnergyLevels()
        // Скрізь по 0.29 кВт·год на відсоток — на всю шкалу це 29, а не 51.
        levels.learn(fromPercent = 95.0, toPercent = 5.0, netKwh = 26.1)

        val curve = levels.curve(nominalKwh = 51.0)

        assertEquals("Верх — заявлена ємність", 51.0, curve.last().energyKwh, 0.01)
        assertEquals("Зміряно 29 — на нулі має лишитися 22", 22.0, curve.first().energyKwh, 0.5)
        assertEquals("Зміряна ємність", 29.0, levels.capacityKwh(51.0), 0.5)
    }

    /** Зміряний нахил лишається таким, як зміряний: його більше не підганяють. */
    @Test
    fun `a measured slope is kept as measured`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 95.0, toPercent = 88.0, netKwh = 2.03) // 0.29 на відсоток

        val curve = levels.curve(nominalKwh = 51.0)
        val slope = (curve.first { it.socPercent == 94.0 }.energyKwh -
            curve.first { it.socPercent == 90.0 }.energyKwh) / 4.0

        assertEquals(0.29, slope, 0.01)
    }

    /**
     * Порожнє місце між двома виміряними островами заповнюється плавним
     * переходом, а не стрибком на середнє. Рівний нахил на порожнечі давав зломи
     * на кожній межі острова — зломи від способу малювання, а не від батареї.
     */
    @Test
    fun `a gap between two measured islands is bridged smoothly`() {
        val levels = EnergyLevels()
        // Дешевий острів угорі шкали і дорогий унизу — як воно й буває насправді.
        levels.learn(fromPercent = 95.0, toPercent = 90.0, netKwh = 1.0)  // 0.2 на відсоток
        levels.learn(fromPercent = 20.0, toPercent = 15.0, netKwh = 4.0)  // 0.8 на відсоток

        val curve = levels.curve(nominalKwh = 51.0)
        fun rateAt(percent: Double) = (curve.first { it.socPercent == percent }.energyKwh -
            curve.first { it.socPercent == percent - 5.0 }.energyKwh) / 5.0

        // Посередині прогалини нахил мусить бути між сусідами, а не однаковий скрізь.
        val low = rateAt(25.0)
        val middle = rateAt(55.0)
        val high = rateAt(85.0)
        assertTrue("Перехід не плавний: $low, $middle, $high", low > middle && middle > high)
    }

    // --- Крива напруги ------------------------------------------------------------

    /**
     * Просадку під струмом прибирає пряма, а не фільтр «беремо лише спокій».
     *
     * Інакше довелося б викидати майже всі заміри: авто в русі майже завжди під
     * навантаженням, і кошики зі спокоєм набиралися б місяцями. Пряма ж бере всі
     * заміри до дозволеного навантаження і прибирає рівно ту частину просадки,
     * яка пояснюється струмом.
     */
    @Test
    fun `the sag under load is fitted out of the voltage`() {
        val levels = EnergyLevels()
        // Батарея тримає 390 В без струму, внутрішній опір 0.08 Ом.
        // Домовленість застосунку: від'ємний струм — розряд, тобто просадка.
        listOf(-60.0, -60.0, -30.0, -30.0, 0.0, 0.0, 20.0, 20.0).forEach { amps ->
            assertTrue(levels.learnVoltage(50.0, volts = 390.0 + amps * 0.08, amps = amps))
        }

        assertEquals(390.0, levels.restVoltageAt(50.0)!!, 0.01)
    }

    /** Під великим навантаженням просадка вже не пряма — такі заміри не беремо. */
    @Test
    fun `a heavy load is refused`() {
        val levels = EnergyLevels()

        assertFalse(levels.learnVoltage(50.0, volts = 350.0, amps = -200.0))
        assertNull(levels.restVoltageAt(50.0))
    }

    /** Поки замірів у кошику мало, кривої немає: одна точка нічого не каже. */
    @Test
    fun `too few samples give no voltage`() {
        val levels = EnergyLevels()
        repeat(3) { levels.learnVoltage(50.0, volts = 390.0, amps = 0.0) }

        assertNull(levels.restVoltageAt(50.0))
    }

    /** Якщо струм у всіх замірах однаковий, прямої не побудувати — беремо середнє. */
    @Test
    fun `a constant load falls back to the mean voltage`() {
        val levels = EnergyLevels()
        repeat(10) { levels.learnVoltage(50.0, volts = 385.0, amps = -20.0) }

        assertEquals(385.0, levels.restVoltageAt(50.0)!!, 0.01)
    }

    /** Крива напруги мусить пережити перезапуск разом з рештою. */
    @Test
    fun `the voltage curve survives a restart`() {
        val levels = EnergyLevels()
        listOf(-60.0, -60.0, -30.0, -30.0, 0.0, 0.0, 20.0, 20.0).forEach { amps ->
            levels.learnVoltage(50.0, volts = 390.0 + amps * 0.08, amps = amps)
        }

        val dir = File.createTempFile("curve", "dir").apply { delete(); mkdirs() }
        try {
            val store = FileEnergyStore(dir)
            store.save(levels.snapshot())
            val restored = EnergyLevels().apply { restore(store.load()!!) }

            assertEquals(390.0, restored.restVoltageAt(50.0)!!, 0.01)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Повна ємність із зарядки ------------------------------------------------

    /**
     * Зарядка з низьких відсотків до сотні — єдиний прямий вимір повної ємності.
     * Лічильник прийнятої енергії веде сама батарея, тож втрат зарядного в цьому
     * числі немає.
     */
    @Test
    fun `a charge from a low percent measures the whole pack`() {
        val levels = EnergyLevels()

        assertTrue(levels.learnFullCharge(fromPercent = 3.0, toPercent = 100.0, energyInKwh = 49.3))

        assertEquals(50.8, levels.measuredTotalKwh!!, 0.1)
        assertEquals(1, levels.fullChargeSamples)
    }

    /** Зарядка з половини нічого про повну ємність не каже. */
    @Test
    fun `a charge from halfway is refused`() {
        val levels = EnergyLevels()

        assertFalse(levels.learnFullCharge(fromPercent = 50.0, toPercent = 100.0, energyInKwh = 25.0))
        assertNull(levels.measuredTotalKwh)
    }

    /** Незакінчена зарядка — теж ні: невідомо, скільки лишилося до сотні. */
    @Test
    fun `a charge that stopped early is refused`() {
        val levels = EnergyLevels()

        assertFalse(levels.learnFullCharge(fromPercent = 3.0, toPercent = 80.0, energyInKwh = 39.0))
    }

    /** Кілька глибоких зарядок усереднюються. */
    @Test
    fun `deep charges are averaged`() {
        val levels = EnergyLevels()

        levels.learnFullCharge(fromPercent = 0.0, toPercent = 100.0, energyInKwh = 50.0)
        levels.learnFullCharge(fromPercent = 0.0, toPercent = 100.0, energyInKwh = 52.0)

        assertEquals(51.0, levels.measuredTotalKwh!!, 0.001)
        assertEquals(2, levels.fullChargeSamples)
    }

    /** Виміряна ємність замінює аксіому в кривій. */
    @Test
    fun `a measured total replaces the axiom in the curve`() {
        val levels = EnergyLevels()
        levels.learn(fromPercent = 95.0, toPercent = 88.0, netKwh = 2.03)
        levels.learnFullCharge(fromPercent = 2.0, toPercent = 100.0, energyInKwh = 44.1)

        val total = levels.measuredTotalKwh!!
        assertEquals(45.0, total, 0.1)
        assertEquals(total, levels.curve(total).last().energyKwh, 0.01)
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
                charging = ChargingState(reported = charging),
            ),
        )
    }

    /**
     * СТОЯНКА — НЕ ЗАМІР, і це найдорожча помилка з усіх можливих тут.
     *
     * Авто живить свою електроніку з тягового пакета й на місці, тож лічильник
     * відданої росте і в дворі. За чотири години набігає кіловат-година, а шкала
     * за той самий час сповзає на кілька відсотків — BMS підправляє оцінку, коли
     * струму майже немає. Виходить «мало енергії на багато відсотків»: замір,
     * який тягне всю криву вниз і виглядає при цьому бездоганно.
     */
    @Test
    fun `standing still is not a measurement`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        // Чотири години на місці: кіловат-година зі шкали на 4 %, тобто 0.25
        // кВт·год на відсоток — удвічі менше за справжній нахил цієї батареї.
        now += 4 * 60 * 60 * 1000L
        publish(socPercent = 86.0, dischargedKwh = 1_001.2, chargedKwh = 500.0)

        assertEquals(0, GeneralData.state.value.curve.samples)
    }

    /**
     * Той самий інтервал, пройдений за годину, — уже їзда, і замір береться.
     *
     * Межа проходить по середній потужності: стоянка це сотні ватів, їзда —
     * кіловати. Тут 1.2 кВт·год за годину, тобто 1.2 кВт — усе ще замало.
     */
    @Test
    fun `a slow crawl still counts if the power says driving`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 10 * 60 * 1000L
        // 2.4 кВт·год за десять хвилин — 14.4 кВт, звичайна міська їзда.
        publish(socPercent = 86.0, dischargedKwh = 1_002.4, chargedKwh = 500.0)

        assertEquals(1, GeneralData.state.value.curve.samples)
    }

    /**
     * Кожен прийнятий інтервал іде в стан цілком, сирими числами.
     *
     * Без цього розбіжність між кривими лишається загадкою: на екрані видно лише
     * підсумок, а помилка живе в окремому замірі.
     */
    @Test
    fun `an accepted interval is published with its raw numbers`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        now += 60_000
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 501.0)

        val sample = GeneralData.state.value.curve.lastSample!!
        assertEquals(90.0, sample.fromPercent, 0.001)
        assertEquals(80.0, sample.toPercent, 0.001)
        assertEquals(6.0, sample.outKwh, 0.001)
        assertEquals(1.0, sample.counterInKwh, 0.001)
        assertTrue("Замір мав лягти в криву A", sample.intoCounter)
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
        // Віддано 6, прийнято 1 — отже пішло 5 кВт·год на 10 % шкали. Але повна
        // ємність від цього не змінюється: вона аксіома, поки її не зміряли.
        // 16 комірок CATL по 3.18 кВт·год — рівно те, що стоїть в авто.
        assertEquals(Pack.USABLE_CAPACITY_KWH, curve.totalKwh, 0.001)
        assertFalse(curve.totalMeasured)
        // Кривої B тут немає: інтеграл струму не працював — сирих кадрів шини в
        // тесті не було, — і замір за струмом навмисно не береться.
        assertEquals(0, curve.powerSamples)
        assertEquals(2.5, curve.points.first { it.socPercent == 85.0 }.energyKwh -
            curve.points.first { it.socPercent == 80.0 }.energyKwh, 0.05)
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
        assertEquals(90.0, curve.measuredToPercent!!, 0.001)
    }

    /**
     * ЗАРЯДКУ МИ НЕ БАЧИМО ЦІЛКОМ, і вимір мусить це переживати. OBD-порт гасне,
     * щойно авто йде в режим зарядки: у журналі адаптер відвалюється за хвилину
     * після початку й до ранку не повертається.
     *
     * Тому замір тримається на двох кінцях: закладка на початку і перше читання,
     * коли шкала вже вгорі. Скільки годин між ними — байдуже, лічильник веде сама
     * батарея.
     */
    @Test
    fun `a charge measured only at its two ends still counts`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        // Увечері: побачили початок зарядки на 3 % і одразу втратили зв'язок.
        publish(socPercent = 3.0, dischargedKwh = 26_000.0, chargedKwh = 27_000.0, charging = true)

        // Вранці: зарядка давно скінчилася, шкала вгорі, лічильник виріс на 49.3.
        now += 8 * 60 * 60 * 1000L
        publish(socPercent = 100.0, dischargedKwh = 26_000.0, chargedKwh = 27_049.3, charging = false)

        val curve = GeneralData.state.value.curve
        assertTrue("Повну ємність не зміряно", curve.totalMeasured)
        assertEquals(50.8, curve.totalKwh, 0.2)
        assertEquals(1, curve.fullChargeSamples)
    }

    /** Закладка мусить пережити перезапуск: телефон їде з машиною, а не з зарядкою. */
    @Test
    fun `the start of a charge survives a restart`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)
        publish(socPercent = 3.0, dischargedKwh = 26_000.0, chargedKwh = 27_000.0, charging = true)

        // Застосунок перезапустили: новий блок, той самий файл.
        scope.cancel()
        GeneralData.reset()
        val second = CoroutineScope(Dispatchers.Unconfined)
        try {
            EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(second)
            now += 8 * 60 * 60 * 1000L
            publish(socPercent = 100.0, dischargedKwh = 26_000.0, chargedKwh = 27_049.3, charging = false)

            assertTrue("Закладка не пережила перезапуск", GeneralData.state.value.curve.totalMeasured)
        } finally {
            second.cancel()
        }
    }

    /** Якщо між закладкою й ранком авто ще й їздило, розділити нічим. */
    @Test
    fun `a charge with a drive in the middle is dropped`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 3.0, dischargedKwh = 26_000.0, chargedKwh = 27_000.0, charging = true)
        now += 8 * 60 * 60 * 1000L
        publish(socPercent = 100.0, dischargedKwh = 26_012.0, chargedKwh = 27_049.3, charging = false)

        assertFalse(GeneralData.state.value.curve.totalMeasured)
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

    // --- Крива за струмом і кілометри ---------------------------------------------

    /**
     * Крива B: віддане з лічильника мінус рекуперація з інтеграла.
     *
     * Тут і видно, чим вона відрізняється від A. Лічильник прийнятої каже, що
     * повернулося 4 кВт·год, а струм за той самий час не повертав нічого — і
     * різниця між кривими на графіку буде рівно ця.
     */
    @Test
    fun `the current based curve ignores the charged counter`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        repeat(40) { now += 1_000; traction(now) }
        now += 1_000
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 504.0)

        val curve = GeneralData.state.value.curve
        assertEquals(1, curve.powerSamples)
        // A: 6 віддано мінус 4 прийнято = 2 на 10 % шкали.
        assertEquals(0.2, slopeOf(curve.counterPoints, 85.0), 0.02)
        // B: ті самі 6 віддано, рекуперації струм не бачив жодної.
        assertEquals(0.6, slopeOf(curve.powerPoints, 85.0), 0.02)
    }

    /** Рекуперація, побачена струмом, з кривої B віднімається. */
    @Test
    fun `regen seen by the current is taken off the curve`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        // Половину читань тягнемо, половину рекуперуємо по +100 А при 360 В.
        repeat(40) { step ->
            now += 1_000
            frame(now, amps = if (step % 2 == 0) -50.0 else 100.0)
        }
        now += 1_000
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 500.0)

        // Двадцять секунд по 36 кВт — це 0.2 кВт·год рекуперації.
        val curve = GeneralData.state.value.curve
        assertEquals((6.0 - 0.2) / 10.0, slopeOf(curve.powerPoints, 85.0), 0.02)
    }

    /** Дірка в спостереженні ламає інтеграл: замір за струмом тоді не береться. */
    @Test
    fun `a gap in the readings drops the current based measurement`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publish(socPercent = 90.0, dischargedKwh = 1_000.0, chargedKwh = 500.0)
        // Десять секунд між читаннями замість звичних 0.8: крізь таку дірку
        // інтегрувати не можна — за неї струм устигає змінитися повністю.
        repeat(40) { now += 10_000; traction(now) }
        now += 1_000
        publish(socPercent = 80.0, dischargedKwh = 1_006.0, chargedKwh = 500.0)

        val curve = GeneralData.state.value.curve
        assertEquals("Замір за струмом мав відпасти", 0, curve.powerSamples)
        assertEquals("А за лічильниками — лишитися", 1, curve.samples)
    }

    /** Кілометри на відсоток беруться з одометра на тому самому інтервалі. */
    @Test
    fun `kilometres per percent are measured from the odometer`() {
        val store = MemoryStore()
        var now = 0L
        EnergyBlock(store, nowMs = { now }, ioDispatcher = Dispatchers.Unconfined).start(scope)

        publishWithOdometer(socPercent = 90.0, dischargedKwh = 1_000.0, odometerKm = 100_000.0)
        now += 60_000
        publishWithOdometer(socPercent = 80.0, dischargedKwh = 1_006.0, odometerKm = 100_020.0)

        val curve = GeneralData.state.value.curve
        assertEquals(1, curve.distanceSamples)
        // Двадцять кілометрів на десять відсотків — по два на відсоток.
        assertEquals(2.0, curve.distancePoints.first { it.socPercent == 85.5 }.km, 0.01)
        // Там, де не їхали, точок немає взагалі: доводити пробіг нема з чого.
        assertTrue(curve.distancePoints.none { it.socPercent < 80.0 })
    }

    private var frames = 0

    /** Одне читання шини: кадр плюс розібрані з нього числа. */
    private fun frame(atMs: Long, amps: Double) {
        GeneralData.publishBatteryFrames(listOf("2101"), listOf("resp${frames++}@$atMs"))
        GeneralData.updateBms(
            BmsData(
                displaySoc = 50.0,
                batteryCurrent = amps,
                batteryVoltage = 360.0,
                cumulativeEnergyDischargedKwh = 1_000.0,
                cumulativeEnergyChargedKwh = 500.0,
            ),
        )
    }

    private fun traction(atMs: Long) = frame(atMs, amps = -50.0)

    private fun publishWithOdometer(socPercent: Double, dischargedKwh: Double, odometerKm: Double) {
        GeneralData.updateBms(
            BmsData(
                displaySoc = 50.0,
                cumulativeEnergyDischargedKwh = dischargedKwh,
                cumulativeEnergyChargedKwh = 500.0,
            ),
        )
        GeneralData.updateVehicle(
            VehicleData(preciseSocPercent = socPercent, odometerKm = odometerKm),
        )
    }

    /** Наскільки крива піднімається за один відсоток у цьому місці шкали. */
    private fun slopeOf(points: List<CurvePoint>, percent: Double): Double {
        val above = points.first { it.socPercent == percent + 1.0 }
        val below = points.first { it.socPercent == percent }
        return above.energyKwh - below.energyKwh
    }
}

private const val VIN = "KNDJX3AE5F7001234"
