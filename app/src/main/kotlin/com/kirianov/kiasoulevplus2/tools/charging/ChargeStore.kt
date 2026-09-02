// ====================================================================================
// СХОВИЩЕ ОБЛІКУ ЗАРЯДОК (ChargeStore)
//
// Зберігати обов'язково: базовий показ лічильника — це те, з чим порівнюється
// наступне читання. Без нього після перезапуску застосунок або нарахував би всю
// історію батареї як одну зарядку, або втратив би все, що сталося без телефона.
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import java.io.File
import java.io.IOException

interface ChargeStore {
    fun load(): ChargeLog?
    fun save(log: ChargeLog)
}

class FileChargeStore(private val directory: File) : ChargeStore {

    private val file get() = File(directory, FILE_NAME)

    override fun load(): ChargeLog? = try {
        val source = file
        if (source.isFile) decode(source.readText()) else null
    } catch (_: IOException) {
        null
    }

    override fun save(log: ChargeLog) {
        try {
            directory.mkdirs()
            file.writeText(encode(log))
        } catch (_: IOException) {
            // Втратити облік зарядок неприємно, але не варто падіння застосунку.
        }
    }

    private fun encode(log: ChargeLog): String = MiniJson.encode(
        linkedMapOf(
            "lastSessionKwh" to log.lastSessionKwh,
            "lastSessionEndedAtMs" to log.lastSessionEndedAtMs.toDouble(),
            "sessionKwh" to log.sessionKwh,
            "sessionStartedAtMs" to log.sessionStartedAtMs.toDouble(),
            "charging" to log.charging,
            "todayKwh" to log.todayKwh,
            "dayKey" to log.dayKey,
            "counterBaselineKwh" to log.counterBaselineKwh,
            "hasBaseline" to log.hasBaseline,
        ),
    )

    private fun decode(text: String): ChargeLog? {
        val values = MiniJson.decode(text.trim().lineSequence().first())
        val baseline = values["counterBaselineKwh"] as? Double ?: return null
        return ChargeLog(
            lastSessionKwh = values["lastSessionKwh"] as? Double ?: 0.0,
            lastSessionEndedAtMs = (values["lastSessionEndedAtMs"] as? Double ?: 0.0).toLong(),
            sessionKwh = values["sessionKwh"] as? Double ?: 0.0,
            sessionStartedAtMs = (values["sessionStartedAtMs"] as? Double ?: 0.0).toLong(),
            charging = values["charging"] as? Boolean ?: false,
            todayKwh = values["todayKwh"] as? Double ?: 0.0,
            dayKey = values["dayKey"] as? String ?: "",
            counterBaselineKwh = baseline,
            hasBaseline = values["hasBaseline"] as? Boolean ?: false,
        )
    }

    private companion object {
        const val FILE_NAME = "charge-log.json"
    }
}
