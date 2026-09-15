// ====================================================================================
// СХОВИЩЕ ІСТОРІЇ ЗАМІРІВ (CellHistoryStore)
//
// Один запис — один рядок файлу. Не масив в одному об'єкті, і це навмисно: додати
// замір означає дописати рядок у кінець, а зіпсований рядок коштує одного заміру,
// а не всієї історії.
//
// Файл лежить у теці авто, як і решта його даних. Перепакування другої машини —
// саме той випадок, заради якого історія й робиться: пакети різні, і плутати їхні
// заміри не можна навіть випадково.
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.cells

import com.kirianov.kiasoulevplus2.Data.CellRecord
import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import com.kirianov.kiasoulevplus2.tools.paths.CarDataStore
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths
import java.io.File
import java.io.IOException

interface CellHistoryStore {
    fun useCar(vin: String) {}

    /** Найновіші першими. */
    fun load(): List<CellRecord>

    fun save(records: List<CellRecord>)
}

class FileCellHistoryStore(private val root: File) : CellHistoryStore, CarDataStore {

    @Volatile
    private var carDirectory: File = root

    private val file get() = File(carDirectory, FILE_NAME)

    override fun useCar(vin: String) {
        val target = CarPaths.directoryFor(root, vin)
        runCatching { target.mkdirs() }
        carDirectory = target
    }

    /**
     * Історія з'явилася вже після гаража, тож спадщини в корені бути не може.
     * Метод лишається заради спільної домовленості [CarDataStore].
     */
    override fun hasLegacyData(): Boolean = false

    override fun exportTo(directory: File) {
        runCatching {
            directory.mkdirs()
            if (file.isFile) file.copyTo(File(directory, FILE_NAME), overwrite = true)
        }
    }

    /**
     * Заміри зливаються ОБ'ЄДНАННЯМ без повторів, а ключ — час заміру.
     *
     * Два телефони бачили різні заміри тієї самої машини, і кожен із них — окрема
     * подія, а не версія однієї. Той самий замір з обох телефонів має однаковий
     * час до мілісекунди, тож повтор упізнається без вигадок.
     */
    override fun mergeFrom(directory: File): String {
        val incoming = runCatching { read(File(directory, FILE_NAME)) }.getOrNull().orEmpty()
        if (incoming.isEmpty()) return ""
        val current = load()
        val known = current.mapTo(HashSet()) { it.atMs }
        val added = incoming.filterNot { it.atMs in known }
        if (added.isEmpty()) return ""
        save((current + added).sortedByDescending { it.atMs }.take(MAX_RECORDS))
        return "додано замірів комірок: ${added.size}"
    }

    override fun load(): List<CellRecord> = read(file)

    private fun read(source: File): List<CellRecord> = try {
        if (!source.isFile) {
            emptyList()
        } else {
            source.readLines()
                .mapNotNull { decode(it) }
                .sortedByDescending { it.atMs }
                .take(MAX_RECORDS)
        }
    } catch (_: IOException) {
        emptyList()
    }

    override fun save(records: List<CellRecord>) {
        try {
            carDirectory.mkdirs()
            val text = records
                .sortedByDescending { it.atMs }
                .take(MAX_RECORDS)
                .joinToString("\n") { encode(it) }
            // Через тимчасовий файл: обрив живлення посеред запису не має лишити
            // півісторії, яку потім не прочитати.
            val temporary = File(carDirectory, "$FILE_NAME.tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
        } catch (_: IOException) {
            // Втратити запис прикро, але не варте падіння застосунку.
        }
    }

    private fun encode(record: CellRecord): String = MiniJson.encode(
        linkedMapOf(
            "atMs" to record.atMs.toDouble(),
            "odometerKm" to record.odometerKm,
            "socPercent" to record.socPercent,
            "batteryTempC" to record.batteryTempC,
            "restVolts" to record.restVolts,
            "minVolts" to record.minVolts,
            "excessMilliOhm" to record.excessMilliOhm,
            "sweeps" to record.sweeps.toDouble(),
            "currentSpreadA" to record.currentSpreadA,
            "peakLoadKw" to record.peakLoadKw,
        ),
    )

    private fun decode(line: String): CellRecord? {
        if (line.isBlank()) return null
        val values = MiniJson.decode(line.trim())
        val atMs = (values["atMs"] as? Double)?.toLong() ?: return null
        val rest = doubles(values["restVolts"]) ?: return null
        if (rest.isEmpty()) return null
        return CellRecord(
            atMs = atMs,
            odometerKm = values["odometerKm"] as? Double ?: 0.0,
            socPercent = values["socPercent"] as? Double ?: 0.0,
            batteryTempC = values["batteryTempC"] as? Double ?: 0.0,
            restVolts = rest,
            minVolts = doubles(values["minVolts"]).orEmpty(),
            excessMilliOhm = doubles(values["excessMilliOhm"]).orEmpty(),
            sweeps = (values["sweeps"] as? Double)?.toInt() ?: 0,
            currentSpreadA = values["currentSpreadA"] as? Double ?: 0.0,
            peakLoadKw = values["peakLoadKw"] as? Double ?: 0.0,
        )
    }

    private fun doubles(value: Any?): List<Double>? =
        (value as? List<*>)?.map { (it as? Double) ?: 0.0 }

    private companion object {
        const val FILE_NAME = "cell-history.jsonl"

        /**
         * Скільки замірів тримаємо. Двадцять — це рік із замірами раз на два-три
         * тижні; глибше ніхто не заглядає, а файл лишається дрібним.
         */
        const val MAX_RECORDS = 20
    }
}
