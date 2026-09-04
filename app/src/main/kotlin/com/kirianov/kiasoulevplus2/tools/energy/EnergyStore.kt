// ====================================================================================
// СХОВИЩЕ ВИМІРЯНОЇ КРИВОЇ (EnergyStore)
//
// Крива набирається тижнями, тому переживати перезапуск і обрив зв'язку вона мусить
// обов'язково — інакше кожен обрив стирав би роботу всіх попередніх поїздок.
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.energy

import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import java.io.File
import java.io.IOException

interface EnergyStore {
    fun load(): LevelsSnapshot?
    fun save(snapshot: LevelsSnapshot)
    fun clear()
}

class FileEnergyStore(private val directory: File) : EnergyStore {

    private val file get() = File(directory, FILE_NAME)

    override fun load(): LevelsSnapshot? = try {
        val source = file
        if (!source.isFile) {
            null
        } else {
            val values = MiniJson.decode(source.readText().trim())
            val energy = doubles(values["sumKwh"])
            val percent = doubles(values["sumPercent"])
            val samples = (values["samples"] as? Double)?.toInt()
            if (energy == null || percent == null || samples == null) {
                null
            } else {
                LevelsSnapshot(
                    sumKwh = energy,
                    sumPercent = percent,
                    samples = samples,
                    totalSumKwh = values["totalSumKwh"] as? Double ?: 0.0,
                    fullChargeSamples = (values["fullChargeSamples"] as? Double)?.toInt() ?: 0,
                    pendingSocPercent = values["pendingSocPercent"] as? Double ?: -1.0,
                    pendingChargedKwh = values["pendingChargedKwh"] as? Double ?: 0.0,
                    pendingDischargedKwh = values["pendingDischargedKwh"] as? Double ?: 0.0,
                )
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    override fun save(snapshot: LevelsSnapshot) {
        try {
            directory.mkdirs()
            // Через тимчасовий файл: обрив живлення посеред запису не має
            // лишити півкривої, яку потім не прочитати.
            val temporary = File(directory, "$FILE_NAME.tmp")
            temporary.writeText(
                MiniJson.encode(
                    linkedMapOf(
                        "sumKwh" to snapshot.sumKwh.toList(),
                        "sumPercent" to snapshot.sumPercent.toList(),
                        "samples" to snapshot.samples.toDouble(),
                        "totalSumKwh" to snapshot.totalSumKwh,
                        "fullChargeSamples" to snapshot.fullChargeSamples.toDouble(),
                        "pendingSocPercent" to snapshot.pendingSocPercent,
                        "pendingChargedKwh" to snapshot.pendingChargedKwh,
                        "pendingDischargedKwh" to snapshot.pendingDischargedKwh,
                    ),
                ),
            )
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
        } catch (_: IOException) {
            // Втратити криву неприємно, але не варте падіння застосунку.
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private fun doubles(raw: Any?): DoubleArray? {
        val list = raw as? List<*> ?: return null
        val values = DoubleArray(list.size)
        list.forEachIndexed { index, item ->
            values[index] = item as? Double ?: return null
        }
        return values
    }

    private companion object {
        const val FILE_NAME = "battery_curve.json"
    }
}
