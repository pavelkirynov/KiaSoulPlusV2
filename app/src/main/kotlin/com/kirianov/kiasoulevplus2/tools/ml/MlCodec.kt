// ====================================================================================
// ЗАПИС І ЧИТАННЯ МОДЕЛІ (MlCodec)
//
// Перетворює відрізки й накопичені статистики на рядки і назад. Чисті функції:
// файлами займається сховище, а не цей код, тож усе тут перевіряється тестами
// без Android.
//
// Числа пишуться як є, без округлення до трьох знаків. Це не дрібниця: у сумі
// A = Σ w·x·xᵀ бувають величини під мільйон, і обрізане десяткове представлення
// тихо зіпсувало б модель, а помітили б це через місяці.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import com.kirianov.kiasoulevplus2.Data.MlSegment

internal object MlCodec {

    /**
     * Версія набору ознак. Якщо збережена не збігається з цією, накопичені
     * статистики не підходять до нового набору — тоді вони відкидаються, а модель
     * збирається наново з журналу. Саме заради цього журнал і зберігає сирі моменти,
     * а не готові ознаки.
     */
    const val FEATURE_SET = "consumption-v2-climate"

    // --- Відрізок ---------------------------------------------------------------

    fun encodeSegment(segment: MlSegment): String = MiniJson.encode(
        linkedMapOf(
            "t" to segment.startedAtMs,
            "dist" to segment.distanceKm,
            "dur" to segment.durationMs,
            "e" to segment.energyKwh,
            "regen" to segment.regenKwh,
            "traction" to segment.tractionKwh,
            "v" to segment.meanSpeedMps,
            "v3" to segment.meanSpeedCubedMps,
            "vvar" to segment.speedVarianceMps,
            "vn" to segment.speedSamples,
            "stop" to segment.stoppedFraction,
            "cov" to segment.coverage,
            "tamb" to segment.ambientTempC,
            "tbat" to segment.batteryTempC,
            "clim" to segment.climateShare,
            "soc0" to segment.socStartPercent,
            "soc1" to segment.socEndPercent,
            "dsoc0" to segment.displaySocStartPercent,
            "dsoc1" to segment.displaySocEndPercent,
            "chg" to segment.charging,
            // Передбачення, зроблене ДО навчання: за ним видно чесну помилку моделі.
            "yhat" to segment.predictedPowerKw,
        ),
    )

    fun decodeSegment(line: String): MlSegment? {
        val values = MiniJson.decode(line)
        if (values.isEmpty()) return null
        val duration = values.long("dur") ?: return null
        val distance = values.double("dist") ?: return null
        val energy = values.double("e") ?: return null

        return MlSegment(
            startedAtMs = values.long("t") ?: 0L,
            distanceKm = distance,
            durationMs = duration,
            energyKwh = energy,
            regenKwh = values.double("regen") ?: 0.0,
            tractionKwh = values.double("traction") ?: 0.0,
            meanSpeedMps = values.double("v") ?: 0.0,
            meanSpeedCubedMps = values.double("v3") ?: 0.0,
            speedVarianceMps = values.double("vvar") ?: 0.0,
            speedSamples = values.double("vn")?.toInt() ?: 0,
            stoppedFraction = values.double("stop") ?: 0.0,
            coverage = values.double("cov") ?: 1.0,
            ambientTempC = values.double("tamb"),
            batteryTempC = values.double("tbat"),
            climateShare = values.double("clim"),
            socStartPercent = values.double("soc0"),
            socEndPercent = values.double("soc1"),
            displaySocStartPercent = values.double("dsoc0"),
            displaySocEndPercent = values.double("dsoc1"),
            charging = values["chg"] as? Boolean ?: false,
            predictedPowerKw = values.double("yhat"),
        )
    }

    // --- Модель -------------------------------------------------------------------

    fun encodeModel(snapshot: ModelSnapshot): String = MiniJson.encode(
        linkedMapOf(
            "featureSet" to snapshot.featureSetId,
            "segments" to snapshot.segments,
            "km" to snapshot.learnedKm,
            "updatedAt" to snapshot.updatedAtMs,
            "consumption" to encodeRegression(snapshot.consumption.regression),
            "noise" to encodeRegression(snapshot.consumption.noise),
            "capacityEnergy" to encodeRegression(snapshot.capacity.energy),
            "capacityBuffer" to encodeRegression(snapshot.capacity.buffer),
            "capacityMeasuredKwh" to snapshot.capacity.measuredEnergyKwh,
            "capacityMeasuredSpan" to snapshot.capacity.measuredSpanPercent,
            "ratios" to snapshot.quality.ratios,
            "bandMultiplier" to snapshot.quality.coverageMultiplier,
            "blocks" to snapshot.quality.blocksSeen,
        ),
    )

    fun decodeModel(text: String): ModelSnapshot? {
        val values = MiniJson.decode(text)
        if (values.isEmpty()) return null

        val consumption = decodeRegression(values["consumption"]) ?: return null
        val noise = decodeRegression(values["noise"]) ?: return null
        val capacityEnergy = decodeRegression(values["capacityEnergy"]) ?: return null
        val capacityBuffer = decodeRegression(values["capacityBuffer"]) ?: return null

        @Suppress("UNCHECKED_CAST")
        val ratios = (values["ratios"] as? List<Any?>)?.mapNotNull { it as? Double } ?: emptyList()

        return ModelSnapshot(
            featureSetId = values["featureSet"] as? String ?: "",
            consumption = ConsumptionSnapshot(consumption, noise),
            capacity = CapacitySnapshot(
                energy = capacityEnergy,
                buffer = capacityBuffer,
                measuredEnergyKwh = values.double("capacityMeasuredKwh") ?: 0.0,
                measuredSpanPercent = values.double("capacityMeasuredSpan") ?: 0.0,
            ),
            quality = QualitySnapshot(
                ratios = ratios,
                coverageMultiplier = values.double("bandMultiplier") ?: 1.0,
                blocksSeen = values.double("blocks")?.toInt() ?: 0,
            ),
            segments = values.double("segments")?.toInt() ?: 0,
            learnedKm = values.double("km") ?: 0.0,
            updatedAtMs = values.long("updatedAt") ?: 0L,
        )
    }

    /**
     * Достатні статистики пишуться як вкладений рядок, а не як вкладений об'єкт:
     * MiniJson навмисно вміє лише плаский об'єкт, і цього досить.
     */
    private fun encodeRegression(snapshot: RegressionSnapshot): String = MiniJson.encode(
        linkedMapOf(
            "gram" to snapshot.gram,
            "moment" to snapshot.moment,
            "w" to snapshot.weightSum,
            "rss" to snapshot.residualSquareSum,
            "ars" to snapshot.absoluteResidualSum,
            "n" to snapshot.observations,
            "decayAt" to snapshot.lastDecayAtMs,
        ),
    )

    private fun decodeRegression(raw: Any?): RegressionSnapshot? {
        val text = raw as? String ?: return null
        val values = MiniJson.decode(text)
        if (values.isEmpty()) return null

        val gram = values.doubles("gram") ?: return null
        val moment = values.doubles("moment") ?: return null

        return RegressionSnapshot(
            gram = gram,
            moment = moment,
            weightSum = values.double("w") ?: 0.0,
            residualSquareSum = values.double("rss") ?: 0.0,
            absoluteResidualSum = values.double("ars") ?: 0.0,
            observations = values.double("n") ?: 0.0,
            lastDecayAtMs = values.long("decayAt") ?: 0L,
        )
    }

    private fun Map<String, Any?>.double(key: String): Double? = this[key] as? Double

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Double)?.toLong()

    private fun Map<String, Any?>.doubles(key: String): DoubleArray? {
        val list = this[key] as? List<*> ?: return null
        val values = DoubleArray(list.size)
        list.forEachIndexed { index, item ->
            values[index] = item as? Double ?: return null
        }
        return values
    }
}

/** Усе, що модель пам'ятає, у вигляді, придатному для файлу. */
data class ModelSnapshot(
    val featureSetId: String,
    val consumption: ConsumptionSnapshot,
    val capacity: CapacitySnapshot,
    val quality: QualitySnapshot,
    val segments: Int,
    val learnedKm: Double,
    val updatedAtMs: Long,
)
