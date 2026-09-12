package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MlStoreTest {

    private lateinit var directory: File
    private lateinit var store: FileMlStore

    @Before
    fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "ml-store-${System.nanoTime()}")
        directory.mkdirs()
        store = FileMlStore(directory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `segments come back in the order they were written`() {
        val written = (1..5).map { segment(startedAtMs = it * 1000L, distanceKm = it.toDouble()) }
        written.forEach(store::appendSegment)

        assertEquals(written, store.readSegments())
    }

    @Test
    fun `an empty store has nothing to say`() {
        assertEquals(emptyList<MlSegment>(), store.readSegments())
        assertNull(store.loadModel())
    }

    @Test
    fun `a saved model comes back`() {
        val consumption = ConsumptionModel()
        VirtualCar().week(segments = 30).forEach(consumption::learn)

        store.saveModel(
            ModelSnapshot(
                featureSetId = MlCodec.FEATURE_SET,
                consumption = consumption.snapshot(),
                capacity = CapacityModel().snapshot(),
                quality = PredictionQuality().snapshot(),
                segments = 30,
                learnedKm = 120.0,
                updatedAtMs = 42L,
            ),
        )

        val loaded = store.loadModel()
        assertNotNull(loaded)
        assertEquals(30, loaded!!.segments)
        assertEquals(120.0, loaded.learnedKm, 1e-9)

        val rebuilt = ConsumptionModel()
        assertTrue(rebuilt.restore(loaded.consumption))
        assertEquals(
            consumption.predictWhPerKm(DriveConditions.steady(70.0)),
            rebuilt.predictWhPerKm(DriveConditions.steady(70.0)),
            1e-9,
        )
    }

    /** Запис моделі не має лишати по собі тимчасових файлів. */
    @Test
    fun `saving leaves no litter behind`() {
        store.saveModel(emptySnapshot())

        val names = directory.list()!!.toList()
        assertTrue("зайві файли: $names", names.none { it.endsWith(".tmp") })
    }

    /** Перезапис моделі не накопичує, а замінює. */
    @Test
    fun `saving twice keeps only the newer model`() {
        store.saveModel(emptySnapshot().copy(segments = 1))
        store.saveModel(emptySnapshot().copy(segments = 2))

        assertEquals(2, store.loadModel()!!.segments)
    }

    @Test
    fun `clearing forgets both the model and the log`() {
        store.appendSegment(segment())
        store.saveModel(emptySnapshot())

        store.clear()

        assertEquals(emptyList<MlSegment>(), store.readSegments())
        assertNull(store.loadModel())
    }

    /** Зіпсований файл моделі не має валити застосунок на старті. */
    @Test
    fun `a corrupted model file is simply ignored`() {
        File(directory, "ml_model.json").writeText("{зіпсовано")

        assertNull(store.loadModel())
    }

    /** Обірваний останній рядок журналу коштує одного відрізка, а не всієї історії. */
    @Test
    fun `a half-written last line costs only that one segment`() {
        store.appendSegment(segment(startedAtMs = 1))
        store.appendSegment(segment(startedAtMs = 2))
        File(directory, "ml_segments.jsonl").appendText("""{"t":3,"dist":3.0,"du""")

        assertEquals(2, store.readSegments().size)
    }

    private fun emptySnapshot() = ModelSnapshot(
        featureSetId = MlCodec.FEATURE_SET,
        consumption = ConsumptionModel().snapshot(),
        capacity = CapacityModel().snapshot(),
        quality = PredictionQuality().snapshot(),
        segments = 0,
        learnedKm = 0.0,
        updatedAtMs = 0L,
    )

    private fun segment(startedAtMs: Long = 1L, distanceKm: Double = 3.0) = MlSegment(
        startedAtMs = startedAtMs,
        distanceKm = distanceKm,
        durationMs = 300_000,
        energyKwh = 0.5,
        meanSpeedMps = 10.0,
        meanSpeedCubedMps = 1000.0,
    )
}
