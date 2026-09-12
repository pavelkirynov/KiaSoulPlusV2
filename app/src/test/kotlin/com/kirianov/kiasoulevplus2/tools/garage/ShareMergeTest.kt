package com.kirianov.kiasoulevplus2.tools.garage

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.MlSegment
import com.kirianov.kiasoulevplus2.tools.charging.FileChargeStore
import com.kirianov.kiasoulevplus2.tools.energy.FileEnergyStore
import com.kirianov.kiasoulevplus2.tools.energy.LevelsSnapshot
import com.kirianov.kiasoulevplus2.tools.ml.FileMlStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Злиття даних двох телефонів. Помилка тут була б ТИХОЮ: числа лишилися б
 * правдоподібними, а половина науки зникла б або порахувалася двічі. Тому кожне
 * правило перевіряється окремо.
 */
class ShareMergeTest {

    private val vin = "KNDJX3AE5F7001234"

    private fun phone(): File =
        File(System.getProperty("java.io.tmpdir"), "phone-${System.nanoTime()}").apply { mkdirs() }

    private fun bundle(): File =
        File(System.getProperty("java.io.tmpdir"), "bundle-${System.nanoTime()}").apply { mkdirs() }

    private fun segment(atMs: Long, km: Double) = MlSegment(
        startedAtMs = atMs,
        distanceKm = km,
        durationMs = 600_000L,
        energyKwh = km * 0.17,
        meanSpeedMps = 15.0,
        meanSpeedCubedMps = 3375.0,
        speedSamples = 40,
        coverage = 1.0,
    )

    /**
     * ГОЛОВНЕ ЗЛИТТЯ: журнал поїздок. Обидва телефони їздили тією самою машиною й
     * бачили різні її поїздки — після обміну кожен мусить знати всі.
     */
    @Test
    fun `trip journals join without repeating`() {
        val mine = FileMlStore(phone()).apply { useCar(vin) }
        val theirs = FileMlStore(phone()).apply { useCar(vin) }

        listOf(1_000L, 2_000L, 3_000L).forEach { mine.appendSegment(segment(it, 5.0)) }
        // Один спільний відрізок і два чужих: спільний не має подвоїтися.
        listOf(3_000L, 4_000L, 5_000L).forEach { theirs.appendSegment(segment(it, 6.0)) }

        val pack = bundle()
        theirs.exportTo(pack)
        val note = mine.mergeFrom(pack)

        val merged = mine.readSegments()
        assertEquals("три свої плюс два чужі", 5, merged.size)
        assertEquals("і в порядку часу", listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L), merged.map { it.startedAtMs })
        assertTrue(note.contains("2"))
    }

    /** Знімок моделі після злиття зникає: він зібраний зі старого журналу. */
    @Test
    fun `the model snapshot is dropped so it rebuilds from the joined journal`() {
        val root = phone()
        val mine = FileMlStore(root).apply { useCar(vin) }
        val theirs = FileMlStore(phone()).apply { useCar(vin) }
        mine.appendSegment(segment(1_000L, 5.0))
        theirs.appendSegment(segment(2_000L, 5.0))

        val modelFile = File(com.kirianov.kiasoulevplus2.tools.paths.CarPaths.directoryFor(root, vin), "ml_model-v2.json")
        modelFile.writeText("{}")

        val pack = bundle()
        theirs.exportTo(pack)
        mine.mergeFrom(pack)

        assertTrue("знімок мав зникнути", !modelFile.exists())
    }

    /** Нічого нового у файлі — нічого й не міняємо, і кажемо про це мовчанням. */
    @Test
    fun `a bundle with nothing new changes nothing`() {
        val mine = FileMlStore(phone()).apply { useCar(vin) }
        val theirs = FileMlStore(phone()).apply { useCar(vin) }
        listOf(1_000L, 2_000L).forEach {
            mine.appendSegment(segment(it, 5.0))
            theirs.appendSegment(segment(it, 5.0))
        }

        val pack = bundle()
        theirs.exportTo(pack)

        assertEquals("", mine.mergeFrom(pack))
        assertEquals(2, mine.readSegments().size)
    }

    /**
     * Криві ДОДАЮТЬСЯ. У корзинах лежать суми за всі проходи, а два телефони бачили
     * різні проходи — сума сум це рівно те, що вийшло б в одного телефона.
     */
    @Test
    fun `curve sums add up`() {
        val mine = FileEnergyStore(phone()).apply { useCar(vin) }
        val theirs = FileEnergyStore(phone()).apply { useCar(vin) }

        mine.save(
            LevelsSnapshot(
                sumKwh = doubleArrayOf(1.0, 2.0),
                sumPercent = doubleArrayOf(10.0, 20.0),
                samples = 3,
                totalSumKwh = 50.0,
                fullChargeSamples = 1,
            ),
        )
        theirs.save(
            LevelsSnapshot(
                sumKwh = doubleArrayOf(0.5, 1.0),
                sumPercent = doubleArrayOf(5.0, 10.0),
                samples = 2,
                totalSumKwh = 51.0,
                fullChargeSamples = 1,
            ),
        )

        val pack = bundle()
        theirs.exportTo(pack)
        mine.mergeFrom(pack)

        val merged = mine.load()!!
        assertEquals(1.5, merged.sumKwh[0], 1e-9)
        assertEquals(3.0, merged.sumKwh[1], 1e-9)
        assertEquals(15.0, merged.sumPercent[0], 1e-9)
        assertEquals(5, merged.samples)
        assertEquals(101.0, merged.totalSumKwh, 1e-9)
        assertEquals(2, merged.fullChargeSamples)
    }

    /** Порожня крива приймає чужу цілком: додавати нема до чого. */
    @Test
    fun `an empty curve takes the incoming one whole`() {
        val mine = FileEnergyStore(phone()).apply { useCar(vin) }
        val theirs = FileEnergyStore(phone()).apply { useCar(vin) }
        theirs.save(
            LevelsSnapshot(doubleArrayOf(1.0), doubleArrayOf(10.0), samples = 4),
        )

        val pack = bundle()
        theirs.exportTo(pack)
        mine.mergeFrom(pack)

        assertEquals(4, mine.load()!!.samples)
    }

    /**
     * Облік зарядок не додається, а береться свіжіший: базовий показ пожиттєвого
     * лічильника — це знімок, а не сума подій. Додати два знімки означало б
     * записати різницю між ними як зарядку.
     */
    @Test
    fun `the charge log takes whichever saw the counter later`() {
        val mine = FileChargeStore(phone()).apply { useCar(vin) }
        val theirs = FileChargeStore(phone()).apply { useCar(vin) }

        mine.save(ChargeLog(counterBaselineKwh = 27_000.0, lastSeenAtMs = 1_000L, hasBaseline = true))
        theirs.save(ChargeLog(counterBaselineKwh = 27_094.0, lastSeenAtMs = 9_000L, hasBaseline = true))

        val pack = bundle()
        theirs.exportTo(pack)
        mine.mergeFrom(pack)

        assertEquals(27_094.0, mine.load()!!.counterBaselineKwh, 0.001)
    }

    /** А застарілий чужий облік наш не чіпає. */
    @Test
    fun `an older charge log is left alone`() {
        val mine = FileChargeStore(phone()).apply { useCar(vin) }
        val theirs = FileChargeStore(phone()).apply { useCar(vin) }

        mine.save(ChargeLog(counterBaselineKwh = 27_094.0, lastSeenAtMs = 9_000L, hasBaseline = true))
        theirs.save(ChargeLog(counterBaselineKwh = 27_000.0, lastSeenAtMs = 1_000L, hasBaseline = true))

        val pack = bundle()
        theirs.exportTo(pack)

        assertEquals("", mine.mergeFrom(pack))
        assertEquals(27_094.0, mine.load()!!.counterBaselineKwh, 0.001)
    }

    /**
     * Знімок моделі назовні НЕ йде. Він зібраний на чужій машині з чужою ємністю, і
     * прийняти його означало б узяти чужу впевненість; зібрати те саме з журналу
     * приймальна сторона вміє сама — і вже зі своєю ємністю.
     */
    @Test
    fun `only the raw journal leaves the phone, not the trained model`() {
        val root = phone()
        val store = FileMlStore(root).apply { useCar(vin) }
        store.appendSegment(segment(1_000L, 5.0))
        File(com.kirianov.kiasoulevplus2.tools.paths.CarPaths.directoryFor(root, vin), "ml_model-v2.json")
            .writeText("{}")

        val pack = bundle()
        store.exportTo(pack)

        assertTrue(File(pack, "ml_segments-v2.jsonl").isFile)
        assertTrue("знімок моделі не має виїжджати", !File(pack, "ml_model-v2.json").exists())
    }
}
