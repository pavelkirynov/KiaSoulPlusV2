package com.kirianov.kiasoulevplus2.tools.garage

import com.kirianov.kiasoulevplus2.Data.CarProfile
import com.kirianov.kiasoulevplus2.Data.Garage
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.Pack
import com.kirianov.kiasoulevplus2.tools.charging.FileChargeStore
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GarageTest {

    private val vin = "KNDJX3AE5F7001234"
    private val other = "KNDJX3AE5F7009999"
    private lateinit var scope: CoroutineScope

    private class MemoryStore(var saved: Garage? = null) : GarageStore {
        override fun load(): Garage? = saved
        override fun save(garage: Garage) { saved = garage }
    }

    private fun directory(): File =
        File(System.getProperty("java.io.tmpdir"), "garage-${System.nanoTime()}").apply { mkdirs() }

    @Before
    fun setUp() {
        GeneralData.reset()
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    /**
     * VIN із шини робить своє авто активним і заводить його без питань. Питати «чи
     * додати цю машину» означало б зупинити облік до відповіді, а людина за кермом.
     */
    @Test
    fun `a vin from the bus adopts the car`() {
        GarageBlock(MemoryStore(), nowMs = { 1_000L }).start(scope)

        GeneralData.noteDetectedVin(vin)

        val garage = GeneralData.state.value.garage
        assertEquals(vin, garage.activeVin)
        assertEquals(1, garage.cars.size)
        assertEquals(1_000L, garage.active.lastSeenAtMs)
    }

    /**
     * ЦЕ ТЕСТ НА ПОМИЛКУ, ЯКА ВДВІЧІ ВКОРОТИЛА ЗАПАС ХОДУ.
     *
     * До гаража ємність пакета була константою в коді — 50.88, шістнадцять комірок
     * CATL. Застосунок із даними тих часів міряв саме ту машину. Коли ємність стала
     * налаштуванням, перенести її забули: перше ж авто отримало «не задано», за яким
     * береться рідний пакет, і крива з моделлю мовчки перебудувалися на 27 кВт·год.
     * У журналі це виглядало як «total=27?» там, де вчора стояло 50.88.
     */
    @Test
    fun `the first car of an app that lived before the garage keeps its pack`() {
        GarageBlock(MemoryStore(), hadDataBeforeGarage = true).start(scope)

        GeneralData.noteDetectedVin(vin)

        assertEquals(
            Pack.USABLE_CAPACITY_KWH,
            GeneralData.state.value.garage.active.packKwh,
            0.001,
        )
    }

    /**
     * А ДРУГЕ авто нічого не успадковує: про його батарею ми не знаємо нічого, і
     * рідний пакет там чесніший за здогад. Успадкування — це перенос відомого факту,
     * а не припущення, що всі машини однакові.
     */
    @Test
    fun `a second car inherits nothing`() {
        GarageBlock(MemoryStore(), hadDataBeforeGarage = true).start(scope)

        GeneralData.noteDetectedVin(vin)
        GeneralData.noteDetectedVin(other)

        assertFalse(GeneralData.state.value.garage.active.packKnown)
    }

    /** Свіжий застосунок нічого не успадковує: даних до гаража в нього не було. */
    @Test
    fun `a fresh install inherits nothing`() {
        GarageBlock(MemoryStore(), hadDataBeforeGarage = false).start(scope)

        GeneralData.noteDetectedVin(vin)

        assertFalse(GeneralData.state.value.garage.active.packKnown)
    }

    /**
     * Питати про спадщину треба ДО переселення: перше ж перемикання на авто її
     * переносить, і після цього відповідь «ні» назавжди.
     */
    @Test
    fun `legacy data is only visible before the first move`() {
        val root = directory()
        val store = FileChargeStore(root)
        store.save(ChargeLog(counterBaselineKwh = 27_094.0, hasBaseline = true))

        assertTrue("до переїзду спадщина видна", store.hasLegacyData())

        store.useCar(vin)

        assertFalse("після переїзду її вже немає", store.hasLegacyData())
    }

    /** Знайоме авто не дублюється, лише оновлює час останньої зустрічі. */
    @Test
    fun `a known car is not added twice`() {
        val store = MemoryStore(Garage(cars = listOf(CarProfile(vin = vin, name = "Мій")), activeVin = vin))
        GarageBlock(store, nowMs = { 2_000L }).start(scope)

        GeneralData.noteDetectedVin(vin)

        val garage = GeneralData.state.value.garage
        assertEquals(1, garage.cars.size)
        assertEquals("Мій", garage.active.name)
        assertEquals(2_000L, garage.active.lastSeenAtMs)
    }

    /**
     * Підключилися до чужої машини — рахуємо за неї, а не домішуємо до своєї. Це і є
     * вся причина, чому гараж узагалі з'явився.
     */
    @Test
    fun `another car becomes active instead of mixing in`() {
        val store = MemoryStore(
            Garage(
                cars = listOf(CarProfile(vin = vin, packKwh = Pack.USABLE_CAPACITY_KWH)),
                activeVin = vin,
            ),
        )
        GarageBlock(store).start(scope)

        GeneralData.noteDetectedVin(other)

        val garage = GeneralData.state.value.garage
        assertEquals(other, garage.activeVin)
        assertFalse("чуже авто не успадковує наш пакет", garage.active.packKnown)
        assertEquals(
            "а наше авто лишається в списку зі своєю ємністю",
            Pack.USABLE_CAPACITY_KWH,
            garage.cars.first { it.vin == vin }.packKwh,
            0.001,
        )
    }

    /** Список пишеться на диск, а VIN із шини — ні: він живе одне підключення. */
    @Test
    fun `the list is written but the detected vin is not`() {
        val store = MemoryStore()
        GarageBlock(store).start(scope)

        GeneralData.noteDetectedVin(vin)

        assertEquals(vin, store.saved?.activeVin)
        assertEquals("", store.saved?.detectedVin)
    }

    /** Знімок, щойно прочитаний із диска, писати назад немає сенсу. */
    @Test
    fun `loading does not write back`() {
        val store = MemoryStore(Garage(cars = listOf(CarProfile(vin = vin)), activeVin = vin))

        GarageBlock(store).start(scope)

        assertEquals(Garage(cars = listOf(CarProfile(vin = vin)), activeVin = vin), store.saved)
    }

    /**
     * ПЕРЕЇЗД СПАДЩИНИ. До появи гаража всі дані лежали просто в корені теки
     * застосунку. У того, хто оновився, там і лежить уся його історія, і втратити її
     * через появу нової можливості було б найгіршим з можливих результатів.
     */
    @Test
    fun `data from before the garage moves into the car folder`() {
        val root = directory()
        val store = FileChargeStore(root)
        store.save(ChargeLog(counterBaselineKwh = 27_094.0, lastSessionKwh = 4.5, hasBaseline = true))
        assertTrue("спадщина мала лежати в корені", File(root, "charge-log.json").isFile)

        store.useCar(vin)

        assertTrue(
            "файл мав переїхати до теки авто",
            File(CarPaths.directoryFor(root, vin), "charge-log.json").isFile,
        )
        assertFalse("і зникнути з кореня", File(root, "charge-log.json").isFile)
        assertEquals(4.5, store.load()!!.lastSessionKwh, 0.001)
    }

    /** У кожного авто свої дані: підключення до чужої машини не бачить наших. */
    @Test
    fun `each car keeps its own data`() {
        val root = directory()
        val store = FileChargeStore(root)

        store.useCar(vin)
        store.save(ChargeLog(counterBaselineKwh = 27_094.0, lastSessionKwh = 4.5, hasBaseline = true))

        store.useCar(other)

        assertEquals("чужа машина не бачить наших зарядок", null, store.load())
    }

    /**
     * VIN приходить із шини як завгодно — від зайвих пробілів до байтів паддінгу, —
     * а з нього робиться ім'я теки. Тому лишаємо тільки літери й цифри: не стільки
     * заради краси, скільки щоб жодна відповідь із шини не могла вивести запис за
     * межі теки застосунку.
     */
    @Test
    fun `the folder name cannot escape the app directory`() {
        assertEquals("KNDJX3AE5F7001234", CarPaths.folderName(" kndjx3ae5f7001234 "))
        assertEquals("порожній VIN має власну теку", CarPaths.UNKNOWN_CAR, CarPaths.folderName(""))

        listOf("../../etc", "a/b", "..", "a\\b", "\u0000").forEach { nasty ->
            val name = CarPaths.folderName(nasty)
            assertTrue("«$nasty» дало «$name»", name.all { it.isLetterOrDigit() })
            assertFalse(name.contains(".."))
        }
    }
}
