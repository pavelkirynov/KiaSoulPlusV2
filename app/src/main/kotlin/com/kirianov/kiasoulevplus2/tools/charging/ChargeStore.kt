// ====================================================================================
// СХОВИЩЕ ОБЛІКУ ЗАРЯДОК (ChargeStore)
//
// Зберігати обов'язково: базовий показ лічильника — це те, з чим порівнюється
// наступне читання. Без нього після перезапуску застосунок або нарахував би всю
// історію батареї як одну зарядку, або втратив би все, що сталося без телефона.
//
// І базовий показ — це НЕ ОДНЕ ЧИСЛО. Разом із лічильником прийнятої енергії
// зберігаємо лічильник відданої, заряд і час: тільки вчотирьох вони дозволяють
// сказати, чим була пауза — зарядкою чи поїздкою. Коли трьох із них тут не було,
// зникала кожна нічна зарядка поспіль, і найдорожче було те, що зникала мовчки.
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import com.kirianov.kiasoulevplus2.tools.paths.CarDataStore
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths
import java.io.File
import java.io.IOException

interface ChargeStore {
    /**
     * Перевести сховище на дані конкретного авто.
     *
     * Порожня реалізація навмисно: у пам'яті, де сховище живе одним об'єктом на
     * тест, переселяти нічого. Значення має лише файлова реалізація.
     */
    fun useCar(vin: String) {}

    fun load(): ChargeLog?
    fun save(log: ChargeLog)
}

class FileChargeStore(private val root: File) : ChargeStore, CarDataStore {

    /** Тека поточного авто. Порожня — корінь: так лежала спадщина до гаража. */
    @Volatile
    private var carDirectory: File = root

    private val directory: File get() = carDirectory

    /**
     * Перевести сховище на дані конкретного авто.
     *
     * Дані кожного авто живуть у власній підтеці, і перемкнути її можна на ходу:
     * VIN стає відомим лише після підключення, тобто пізніше, ніж застосунок
     * піднявся.
     *
     * ПЕРЕЇЗД СПАДЩИНИ РОБИТЬСЯ ТУТ, і це навмисно. До появи гаража всі дані
     * лежали просто в корені теки застосунку — у того, хто оновився, там
     * і лежить уся його історія. Хто саме має її переселити? Той, хто знає імена
     * своїх файлів, тобто це сховище; будь-хто інший мусив би знати чужі імена.
     * Переїзд відбувається рівно один раз: далі файл на новому місці вже є.
     */
    override fun useCar(vin: String) {
        val target = CarPaths.directoryFor(root, vin)
        adoptLegacy(target)
        carDirectory = target
    }

    private fun adoptLegacy(target: File) {
        runCatching {
            target.mkdirs()
            for (name in OWN_FILES) {
                val legacy = File(root, name)
                val moved = File(target, name)
                if (legacy.isFile && !moved.exists()) legacy.renameTo(moved)
            }
        }
    }

    private val file get() = File(directory, FILE_NAME)

    override fun hasLegacyData(): Boolean =
        OWN_FILES.any { name -> File(root, name).isFile }

    override fun exportTo(directory: File) {
        runCatching {
            directory.mkdirs()
            if (file.isFile) file.copyTo(File(directory, FILE_NAME), overwrite = true)
        }
    }

    /**
     * Облік зарядок не додається, а ЗАМІНЮЄТЬСЯ свіжішим — і це єдине можливе
     * правило.
     *
     * Тут немає чого об'єднувати: базовий показ пожиттєвого лічильника — це не сума
     * подій, а знімок «де ми стояли, коли дивилися востаннє». Додати два знімки
     * означало б записати різницю між ними як зарядку; узяти старіший — почати
     * рахувати від давно минулого числа й приписати собі все, що набігло відтоді.
     * Лишається взяти той, що бачив лічильник пізніше.
     */
    override fun mergeFrom(directory: File): String {
        val incoming = runCatching { decode(File(directory, FILE_NAME).readText()) }.getOrNull()
            ?: return ""
        val current = load()
        if (current != null && current.lastSeenAtMs >= incoming.lastSeenAtMs) return ""
        save(incoming)
        return "облік зарядок узято свіжіший"
    }

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
            // Ці три — не прикраса, а те, з чим базовий показ порівнюється. Одного
            // разу їх тут забули, і кожна зарядка без телефона зникала: після
            // перезапуску lastSeenAtMs дорівнював нулю, перевірка «чи була пауза»
            // мовчки не спрацьовувала, а базовий показ усе одно переїжджав на
            // новий — разом із 23 кВт·год, які нікуди було записати.
            "dischargedBaselineKwh" to log.dischargedBaselineKwh,
            "socBaselinePercent" to log.socBaselinePercent,
            "lastSeenAtMs" to log.lastSeenAtMs.toDouble(),
            "lastDecision" to log.lastDecision,
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
            dischargedBaselineKwh = values["dischargedBaselineKwh"] as? Double ?: 0.0,
            socBaselinePercent = values["socBaselinePercent"] as? Double ?: 0.0,
            lastSeenAtMs = (values["lastSeenAtMs"] as? Double ?: 0.0).toLong(),
            lastDecision = values["lastDecision"] as? String ?: "",
            hasBaseline = values["hasBaseline"] as? Boolean ?: false,
        )
    }

    private companion object {
        /** Свої файли: їх і переселяємо, коли з'являється авто. */
        val OWN_FILES get() = listOf(FILE_NAME)

        const val FILE_NAME = "charge-log.json"
    }
}
