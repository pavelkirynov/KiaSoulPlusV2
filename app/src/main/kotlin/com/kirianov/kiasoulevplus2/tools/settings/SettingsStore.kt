// ====================================================================================
// СХОВИЩЕ НАЛАШТУВАНЬ (SettingsStore)
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.settings

import com.kirianov.kiasoulevplus2.Data.Settings
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
                    linkedMapOf(
                        "autoConnect" to settings.autoConnect,
                        "journal" to settings.journal,
                    ),
                ),
            )
        } catch (_: IOException) {
            // Втратити налаштування неприємно, але не варто падіння застосунку.
        }
    }

    private companion object {
        const val FILE_NAME = "settings.json"
    }
}
