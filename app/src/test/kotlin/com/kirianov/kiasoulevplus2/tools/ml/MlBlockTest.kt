package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.MlSegment
import com.kirianov.kiasoulevplus2.Data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MlBlockTest {

    private val store = FakeMlStore()
    private var elapsed = 0L
    private lateinit var scope: CoroutineScope

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

    /** Найпростіше: поїздка перетворюється на відрізок, і той лягає в журнал. */
    @Test
    fun `a drive turns into a segment in the log`() {
        startBlock()
        drive(seconds = 400)

        assertTrue("журнал мав поповнитися: ${store.appended.size}", store.appended.isNotEmpty())
        val segment = store.appended.first()
        assertEquals("пробіг", 5.0, segment.distanceKm, 0.2)
        assertEquals("витрата", 167.0, segment.whPerKm ?: 0.0, 15.0)
        assertTrue("модель мала порахувати відрізки", GeneralData.state.value.ml.model.segments > 0)
    }

    /**
     * Крива для екрана мусить доїжджати до стану. Ознаку легко порахувати в моделі
     * й забути віддати нагору — тоді графік буде порожній, а жоден тест моделі цього
     * не побачить.
     */
    @Test
    fun `the dial to real curve reaches the screen`() {
        startBlock()
        drive(seconds = 20)

        val curve = GeneralData.state.value.ml.model.scaleCurve
        assertTrue("крива мала доїхати до стану: ${curve.size}", curve.size >= 2)
        assertEquals(0.0, curve.first().dialPercent, 1e-9)
        assertEquals(100.0, curve.last().dialPercent, 1e-9)
    }

    /** Прогноз має з'явитися, щойно відомий заряд, — не чекаючи тижня навчання. */
    @Test
    fun `a prediction appears as soon as the charge is known`() {
        startBlock()
        drive(seconds = 20)

        val prediction = GeneralData.state.value.ml.prediction
        assertNotNull("прогноз мав з'явитися одразу", prediction)
        assertTrue("запас має бути правдоподібним: ${prediction!!.rangeKm}", prediction.rangeKm in 30.0..400.0)
        assertTrue("і з інтервалом", prediction.rangeToKm > prediction.rangeFromKm)
        assertTrue("реальний відсоток", prediction.realPercent in 0.0..100.0)
    }

    /** Поки адаптер не під'єднаний, вчитися нема на чому. */
    @Test
    fun `nothing is learned while the adapter is not connected`() {
        startBlock()
        GeneralData.updateConnection(ConnectionState.Disconnected, "")
        drive(seconds = 400, connected = false)

        assertTrue(store.appended.isEmpty())
    }

    /**
     * Розрив зв'язку посеред відрізка: пробіг за час тиші зріс, а спожита енергія
     * ні. Відрізок крізь цю дірку не має народитися.
     */
    @Test
    fun `a segment is not built across a dropped connection`() {
        startBlock()
        drive(seconds = 250)

        GeneralData.updateConnection(ConnectionState.Disconnected, "обрив")
        elapsed += 600_000
        odometerKm += 10.0
        GeneralData.updateConnection(ConnectionState.Connected, "")

        drive(seconds = 100)

        assertTrue("відрізок крізь дірку не мав закритися", store.appended.isEmpty())
    }

    /** «Забути все» стирає і модель, і журнал. */
    @Test
    fun `forgetting everything clears the model and the log`() {
        startBlock()
        drive(seconds = 400)
        assertTrue(store.appended.isNotEmpty())

        GeneralData.requestMlReset()

        assertTrue("журнал мав бути стертий", store.cleared)
        assertEquals(0, GeneralData.state.value.ml.model.segments)
        assertEquals(0, GeneralData.state.value.ml.recentSegments.size)
    }

    /**
     * Перенавчання журналом. Саме заради цього журнал і зберігає сирі моменти:
     * після зміни набору ознак модель збирається наново без жодної поїздки.
     */
    @Test
    fun `retraining rebuilds the model from the log alone`() {
        store.appended.addAll(VirtualCar().week(segments = 40))
        startBlock()

        GeneralData.requestMlRetrain()

        val model = GeneralData.state.value.ml.model
        assertEquals(40, model.segments)
        assertTrue("пробіг мав скластися: ${model.learnedKm}", model.learnedKm > 100.0)
        assertFalse(GeneralData.state.value.ml.retraining)
    }

    /** Збережена модель від іншого набору ознак не приймається — журнал переважує. */
    @Test
    fun `a model from another feature set is rebuilt from the log`() {
        store.appended.addAll(VirtualCar().week(segments = 25))
        store.model = ModelSnapshot(
            featureSetId = "щось-старе",
            consumption = ConsumptionModel().snapshot(),
            capacity = CapacityModel().snapshot(),
            quality = PredictionQuality().snapshot(),
            segments = 9999,
            learnedKm = 9999.0,
            updatedAtMs = 0L,
        )

        startBlock()

        assertEquals("мали зібрати з журналу, а не взяти чуже", 25, GeneralData.state.value.ml.model.segments)
    }

    /** Збережена модель свого набору ознак піднімається як є, без перечитування журналу. */
    @Test
    fun `a model of the current feature set is loaded as it is`() {
        store.model = ModelSnapshot(
            featureSetId = MlCodec.FEATURE_SET,
            consumption = ConsumptionModel().snapshot(),
            capacity = CapacityModel().snapshot(),
            quality = PredictionQuality().snapshot(),
            segments = 123,
            learnedKm = 456.0,
            updatedAtMs = 0L,
        )

        startBlock()

        assertEquals(123, GeneralData.state.value.ml.model.segments)
        assertEquals(456.0, GeneralData.state.value.ml.model.learnedKm, 1e-9)
    }

    /** Запит мусить бути знятий, інакше блок виконував би його без кінця. */
    @Test
    fun `a request is cleared once it has been taken`() {
        startBlock()

        GeneralData.requestMlRetrain()

        assertEquals(com.kirianov.kiasoulevplus2.Data.MlRequest.None, GeneralData.state.value.ml.request)
    }

    private fun assertFalse(value: Boolean) = assertTrue(!value)

    private var odometerKm = 1000.0

    private fun startBlock() {
        MlBlock(
            store = store,
            elapsedMillis = { elapsed },
            wallClockMillis = { elapsed },
            ioDispatcher = Dispatchers.Unconfined,
        ).start(scope)
        GeneralData.updateConnection(ConnectionState.Connected, "")
    }

    /** Проганяє через сховище рівну їзду 60 км/год при розряді 10 кВт. */
    private fun drive(seconds: Int, connected: Boolean = true) {
        if (connected) GeneralData.updateConnection(ConnectionState.Connected, "")
        repeat(seconds) {
            elapsed += 1000
            odometerKm += 60.0 / 3600.0
            // Такт задають кадри, як і в справжньому циклі опитування.
            GeneralData.publishBatteryFrames(listOf("21 01"), listOf("7EC..."))
            GeneralData.updateBms(
                BmsData(
                    displaySoc = 80.0,
                    batteryVoltage = 360.0,
                    batteryCurrent = -27.8,
                    batteryTempC = 20.0,
                    cumulativeEnergyChargedKwh = 1000.0,
                    cumulativeEnergyDischargedKwh = 1000.0,
                ),
            )
            GeneralData.updateVehicle(
                VehicleData(
                    // Лічильник віддає лише десяті: саме так приходить кадр 4F0.
                    odometerKm = Math.floor(odometerKm * 10.0) / 10.0,
                    speedKmh = 60.0,
                    preciseSocPercent = 80.0,
                    displaySocPercent = 79.0,
                    ambientTempC = 15.0,
                ),
            )
        }
    }

    private class FakeMlStore : MlStore {
        val appended = mutableListOf<MlSegment>()
        var model: ModelSnapshot? = null
        var cleared = false

        override fun loadModel(): ModelSnapshot? = model

        override fun saveModel(snapshot: ModelSnapshot) {
            model = snapshot
        }

        override fun appendSegment(segment: MlSegment) {
            appended += segment
        }

        override fun readSegments(): List<MlSegment> = appended.toList()

        override fun clear() {
            appended.clear()
            model = null
            cleared = true
        }
    }
}
