// ====================================================================================
// ФАЙЛ ЖУРНАЛУ (JournalStore)
//
// Дописує рядки в кінець файлу і стежить, щоб він не з'їв пам'ять телефона.
//
// ЧОМУ ДВА ФАЙЛИ, А НЕ ОДИН. Журнал росте на мегабайти за день, а цікаве в ньому —
// останні години. Обрізати файл посередині не можна: він потрібен цілим, з
// мітками часу підряд. Тому коли поточний доростає до межі, він переїжджає в
// «попередній», а новий починається з нуля. Так на диску завжди від однієї до
// двох меж, а найсвіжіше не втрачається ніколи.
//
// Каталог, а не Context: сховище лишається чистим Kotlin і перевіряється тестами
// без емулятора, як і решта логіки проєкту.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.journal

import java.io.File

interface JournalStore {
    /** Дописує рядки; повертає новий розмір файлу в байтах. */
    fun append(lines: List<String>): Long

    fun sizeBytes(): Long

    fun clear()

    /** Повний шлях до поточного файлу — потрібен інтерфейсу для «Поділитися». */
    fun path(): String
}

class FileJournalStore(
    baseDir: File,
    private val maxBytes: Long = MAX_BYTES,
) : JournalStore {

    private val dir = File(baseDir, DIR_NAME)
    private val current = File(dir, FILE_NAME)
    private val previous = File(dir, PREVIOUS_NAME)

    override fun append(lines: List<String>): Long {
        if (lines.isEmpty()) return sizeBytes()
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            rotateIfNeeded()
            current.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
            current.length()
        }.getOrDefault(0L)
    }

    override fun sizeBytes(): Long = if (current.exists()) current.length() else 0L

    override fun clear() {
        runCatching { current.delete() }
        runCatching { previous.delete() }
    }

    override fun path(): String = current.absolutePath

    private fun rotateIfNeeded() {
        if (current.length() < maxBytes) return
        runCatching { previous.delete() }
        runCatching { current.renameTo(previous) }
    }

    private companion object {
        const val DIR_NAME = "journal"
        const val FILE_NAME = "journal.txt"
        const val PREVIOUS_NAME = "journal-previous.txt"

        /**
         * Два мегабайти на файл — приблизно доба звичайної їзди. Разом із
         * попереднім це до чотирьох: непомітно для телефона й достатньо, щоб
         * розібрати вчорашню поїздку.
         */
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
