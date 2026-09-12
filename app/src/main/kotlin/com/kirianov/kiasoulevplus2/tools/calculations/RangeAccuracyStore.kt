// ====================================================================================
// СХОВИЩЕ ПЕРЕВІРКИ ПРОГНОЗУ (RangeAccuracyStore)
//
// «Чи стримав прогноз обіцянку» набирається кілометрами: поки не проїхано хоча б
// кілька, відсоток нічого не означає. Тому відлік мусить переживати і обрив
// зв'язку, і перезапуск — а він жив тільки в пам'яті, і кожне оновлення APK
// починало його з нуля.
//
// Обидва числа тут абсолютні — одометр авто й поточний прогноз, — тож пауза між
// запусками нічого не псує: після повернення різниця рахується від тієї самої
// точки, що й до нього.
//
// Файл у теці авто, як і решта його даних: одометр другої машини менший на
// сімдесят тисяч кілометрів, і сплутати їх означало б показати відлік завдовжки
// в мінус сімдесят тисяч.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.RangeAccuracy
import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import com.kirianov.kiasoulevplus2.tools.paths.CarDataStore
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths
import java.io.File
import java.io.IOException

interface RangeAccuracyStore {
    fun useCar(vin: String) {}
    fun load(): RangeAccuracy?
    fun save(accuracy: RangeAccuracy)
}

class FileRangeAccuracyStore(private val root: File) : RangeAccuracyStore, CarDataStore {

    @Volatile
    private var carDirectory: File = root

    private val file get() = File(carDirectory, FILE_NAME)

    override fun useCar(vin: String) {
        val target = CarPaths.directoryFor(root, vin)
        runCatching { target.mkdirs() }
        carDirectory = target
    }

    /** Відлік з'явився вже після гаража, тож спадщини в корені бути не може. */
    override fun hasLegacyData(): Boolean = false

    override fun exportTo(directory: File) {
        runCatching {
            directory.mkdirs()
            if (file.isFile) file.copyTo(File(directory, FILE_NAME), overwrite = true)
        }
    }

    /**
     * Не зливається взагалі — і це не пропуск.
     *
     * Відлік описує ОДНУ поїздку, яка йде просто зараз на цьому телефоні: що
     * прогноз обіцяв на її початку і скільки з того часу проїхано. Чужа поїздка
     * не додається до нашої й не замінює її; узяти звідти нема чого.
     */
    override fun mergeFrom(directory: File): String = ""

    override fun load(): RangeAccuracy? = try {
        val source = file
        if (!source.isFile) null else decode(source.readText())
    } catch (_: IOException) {
        null
    }

    override fun save(accuracy: RangeAccuracy) {
        try {
            carDirectory.mkdirs()
            file.writeText(
                MiniJson.encode(
                    linkedMapOf(
                        "startRangeKm" to accuracy.startRangeKm,
                        "currentRangeKm" to accuracy.currentRangeKm,
                        "drivenKm" to accuracy.drivenKm,
                        "startOdometerKm" to accuracy.startOdometerKm,
                        "started" to accuracy.started,
                    ),
                ),
            )
        } catch (_: IOException) {
            // Втратити відлік прикро, але не варте падіння застосунку.
        }
    }

    private fun decode(text: String): RangeAccuracy? {
        val values = MiniJson.decode(text.trim())
        val started = values["started"] as? Boolean ?: return null
        return RangeAccuracy(
            startRangeKm = values["startRangeKm"] as? Double ?: 0.0,
            currentRangeKm = values["currentRangeKm"] as? Double ?: 0.0,
            drivenKm = values["drivenKm"] as? Double ?: 0.0,
            startOdometerKm = values["startOdometerKm"] as? Double ?: 0.0,
            started = started,
        )
    }

    private companion object {
        const val FILE_NAME = "range-accuracy.json"
    }
}
