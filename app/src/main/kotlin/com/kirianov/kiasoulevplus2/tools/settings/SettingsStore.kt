// ====================================================================================
// СХОВИЩЕ НАЛАШТУВАНЬ (SettingsStore)
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.settings

import com.kirianov.kiasoulevplus2.Data.Settings
import com.kirianov.kiasoulevplus2.Data.CellPalette
import com.kirianov.kiasoulevplus2.Data.CellPalettes
import com.kirianov.kiasoulevplus2.Data.CellValueMode
import com.kirianov.kiasoulevplus2.tools.json.MiniJson
import java.io.File
import java.io.IOException

interface SettingsStore {
    fun load(): Settings?
    fun save(settings: Settings)
}

class FileSettingsStore(private val directory: File) : SettingsStore {

    private val file get() = File(directory, FILE_NAME)

    override fun load(): Settings? = try {
        val source = file
        if (!source.isFile) {
            null
        } else {
            val text = source.readText().trim()
            if (text.isEmpty()) {
                null
            } else {
                val values = MiniJson.decode(text.lineSequence().first())
                val autoConnect = values["autoConnect"] as? Boolean
                if (autoConnect == null) {
                    null
                } else {
                    // Ключа journal у старих файлах немає: там береться типове
                    // значення, а не false, інакше оновлення застосунку мовчки
                    // вимикало б журнал усім, хто оновився.
                    val defaults = Settings()
                    Settings(
                        autoConnect = autoConnect,
                        journal = values["journal"] as? Boolean ?: defaults.journal,
                        wakeOnDeviceAddress = values["wakeOnDevice"] as? String
                            ?: defaults.wakeOnDeviceAddress,
                        cellPalettes = palettesOf(values, defaults.cellPalettes),
                    )
                }
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    override fun save(settings: Settings) {
        try {
            directory.mkdirs()
            file.writeText(
                MiniJson.encode(
                    linkedMapOf<String, Any?>(
                        "autoConnect" to settings.autoConnect,
                        "journal" to settings.journal,
                        "wakeOnDevice" to settings.wakeOnDeviceAddress,
                    ).also { fields ->
                        // Пороги пишуться плоскими ключами, а не вкладеним
                        // об'єктом: MiniJson навмисно вміє лише плоскі карти, і
                        // заводити для п'яти пар чисел справжній парсер JSON
                        // означало б платити бібліотекою за один рядок файлу.
                        CellValueMode.entries.forEach { mode ->
                            val palette = settings.cellPalettes.of(mode)
                            fields[keyOf(mode) + "Warn"] = palette.warnAt
                            fields[keyOf(mode) + "Alert"] = palette.alertAt
                            fields[keyOf(mode) + "Bad"] = palette.badAt
                        }
                    },
                ),
            )
        } catch (_: IOException) {
            // Втратити налаштування неприємно, але не варто падіння застосунку.
        }
    }

    /**
     * Пороги з плоских ключів. Відсутній ключ означає «цього ще не налаштовували»
     * — тоді береться типове значення, а не нуль: нуль пофарбував би весь пакет.
     * Так само читаються й файли з двома порогами замість трьох: середній просто
     * візьметься типовим.
     */
    private fun palettesOf(values: Map<String, Any?>, defaults: CellPalettes): CellPalettes {
        var palettes = defaults
        CellValueMode.entries.forEach { mode ->
            val fallback = defaults.of(mode)
            val warn = values[keyOf(mode) + "Warn"] as? Double ?: fallback.warnAt
            val alert = values[keyOf(mode) + "Alert"] as? Double ?: fallback.alertAt
            val bad = values[keyOf(mode) + "Bad"] as? Double ?: fallback.badAt
            palettes = palettes.with(
                mode,
                CellPalette(warnAt = warn, alertAt = alert, badAt = bad),
            )
        }
        return palettes
    }

    private fun keyOf(mode: CellValueMode): String = "cell" + mode.name

    private companion object {
        const val FILE_NAME = "settings.json"
    }
}
