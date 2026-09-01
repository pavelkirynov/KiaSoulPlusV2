package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlCodecTest {

    @Test
    fun `a segment survives a trip through the log`() {
        val segment = MlSegment(
            startedAtMs = 1_756_600_000_000,
            distanceKm = 3.4,
            durationMs = 312_000,
            energyKwh = 0.412,
            regenKwh = 0.061,
            tractionKwh = 0.473,
            meanSpeedMps = 9.92,
            meanSpeedCubedMps = 1120.4,
            speedVarianceMps = 8.1,
            speedSamples = 30,
            stoppedFraction = 0.12,
            coverage = 0.83,
            ambientTempC = 4.5,
            batteryTempC = 11.0,
            socStartPercent = 71.48,
            socEndPercent = 69.91,
            displaySocStartPercent = 71.5,
            displaySocEndPercent = 70.0,
            charging = false,
            predictedPowerKw = 4.75,
        )

        val back = MlCodec.decodeSegment(MlCodec.encodeSegment(segment))

        assertEquals(segment, back)
    }

    @Test
    fun `a segment with nothing measured still survives`() {
        val bare = MlSegment(
            startedAtMs = 5,
            distanceKm = 0.0,
            durationMs = 900_000,
            energyKwh = 0.37,
            meanSpeedMps = 0.0,
            meanSpeedCubedMps = 0.0,
        )

        val back = MlCodec.decodeSegment(MlCodec.encodeSegment(bare))

        assertEquals(bare, back)
        assertNull("температури не було, і вигадувати її не можна", back!!.ambientTempC)
    }

    /** Журнал дописується на ходу: обірваний останній рядок не має ламати решту. */
    @Test
    fun `a truncated log line is skipped, not fatal`() {
        assertNull(MlCodec.decodeSegment("""{"t":1,"dist":3.0,"du"""))
        assertNull(MlCodec.decodeSegment(""))
    }

    /**
     * Найважливіше в збереженні: коефіцієнти після читання мусять бути ті самі.
     * Округлення до трьох знаків тут зіпсувало б модель тихо, і помітили б це
     * лише через місяці.
     */
    @Test
    fun `a saved model predicts exactly the same after being read back`() {
        val car = VirtualCar()
        val consumption = ConsumptionModel()
        val capacity = CapacityModel()
        val quality = PredictionQuality()

        car.week(segments = 60).forEach { segment ->
            consumption.learn(segment)?.let { quality.observe(segment, it) }
        }
        capacity.learn(90.0, 55.0, 8.4, atMs = 1000L)

        val text = MlCodec.encodeModel(
            ModelSnapshot(
                featureSetId = MlCodec.FEATURE_SET,
                consumption = consumption.snapshot(),
                capacity = capacity.snapshot(),
                quality = quality.snapshot(),
                segments = 60,
                learnedKm = 240.0,
                updatedAtMs = 1_700_000_000_000,
            ),
        )

        val restored = MlCodec.decodeModel(text)
        assertNotNull(restored)
        restored!!

        assertEquals(MlCodec.FEATURE_SET, restored.featureSetId)
        assertEquals(60, restored.segments)
        assertEquals(240.0, restored.learnedKm, 1e-9)
        assertEquals(1_700_000_000_000, restored.updatedAtMs)

        val rebuilt = ConsumptionModel()
        assertTrue(rebuilt.restore(restored.consumption))
        listOf(40.0, 60.0, 90.0).forEach { speed ->
            assertEquals(
                consumption.predictWhPerKm(DriveConditions.steady(speed)),
                rebuilt.predictWhPerKm(DriveConditions.steady(speed)),
                1e-9,
            )
        }

        val rebuiltCapacity = CapacityModel()
        assertTrue(rebuiltCapacity.restore(restored.capacity))
        assertEquals(capacity.usableCapacityKwh, rebuiltCapacity.usableCapacityKwh, 1e-9)
    }

    @Test
    fun `a corrupted model file decodes to nothing instead of throwing`() {
        assertNull(MlCodec.decodeModel("це не модель"))
        assertNull(MlCodec.decodeModel("{}"))
        assertNull(MlCodec.decodeModel("""{"featureSet":"consumption-v1"}"""))
    }
}
