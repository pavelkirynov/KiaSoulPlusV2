// ====================================================================================
// СХОВИЩЕ ГАРАЖА (GarageStore)
//
// Список відомих авто й те, за яке рахували востаннє. Один невеликий файл у корені
// теки застосунку — на відміну від даних самих авто, які лежать кожні у своїй
// підтеці.
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.garage

import com.kirianov.kiasoulevplus2.Data.CarProfile
import com.kirianov.kiasoulevplus2.Data.Garage
import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import java.io.File
import java.io.IOException

interface GarageStore {
    fun load(): Garage?
    fun save(garage: Garage)
}

class FileGarageStore(private val root: File) : GarageStore {

    private val file get() = File(root, FILE_NAME)

    override fun load(): Garage? = try {
        val source = file
        if (!source.isFile) {
            null
        } else {
            val lines = source.readText().trim().lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) {
                null
            } else {
                // Перший рядок — про гараж, решта — по авто на рядок. Так додати
                // авто означає дописати рядок, а не переписати весь файл.
                val head = MiniJson.decode(lines.first())
                Garage(
                    activeVin = head["active"] as? String ?: "",
                    cars = lines.drop(1).mapNotNull(::decodeCar),
                    loaded = true,
                )
            }
        }
    } catch (_: IOException) {
        null
    }

    override fun save(garage: Garage) {
        try {
            root.mkdirs()
            val text = buildString {
                appendLine(MiniJson.encode(linkedMapOf("active" to garage.activeVin)))
                garage.cars.forEach { appendLine(encodeCar(it)) }
            }
            file.writeText(text)
        } catch (_: IOException) {
            // Втратити список авто неприємно, але не варте падіння застосунку.
        }
    }

    private fun encodeCar(car: CarProfile): String = MiniJson.encode(
        linkedMapOf(
            "vin" to car.vin,
            "name" to car.name,
            "packKwh" to car.packKwh,
            "seen" to car.lastSeenAtMs.toDouble(),
        ),
    )

    private fun decodeCar(line: String): CarProfile? {
        val values = MiniJson.decode(line)
        val vin = values["vin"] as? String ?: return null
        if (vin.isEmpty()) return null
        return CarProfile(
            vin = vin,
            name = values["name"] as? String ?: "",
            packKwh = values["packKwh"] as? Double ?: 0.0,
            lastSeenAtMs = (values["seen"] as? Double ?: 0.0).toLong(),
        )
    }

    private companion object {
        const val FILE_NAME = "garage.json"
    }
}
