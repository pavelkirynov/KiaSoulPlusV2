// ====================================================================================
// ФОРМАТУВАННЯ ЧИСЕЛ (Formatting)
//
// Усі числа в додатку виводяться через ці функції з фіксованою Locale.US.
// Причина: на локалі з комою як роздільником String.format видавав «3,85»,
// а зворотний toDoubleOrNull() таку строку не розбирав — введені вручну напруги
// комірок мовчки губилися. Тепер формат і розбір симетричні на будь-якій локалі.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.format

import java.util.Locale

/** Форматує число з фіксованою кількістю знаків після крапки. */
fun formatDecimal(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

/** Форматує значення з одиницею виміру, наприклад «364.5 В». */
fun formatMeasurement(value: Double, decimals: Int, unit: String): String =
    "${formatDecimal(value, decimals)} $unit"

/** Тривалість у вигляді «1 год 24 хв» або «7 хв 12 с». */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "$hours год $minutes хв"
        minutes > 0 -> "$minutes хв $seconds с"
        else -> "$seconds с"
    }
}

/** Значення або прочерк, якщо рахувати ще нема з чого. */
fun formatOrDash(value: Double?, decimals: Int, unit: String): String =
    value?.let { formatMeasurement(it, decimals, unit) } ?: "--"

/** Розбирає введений користувачем текст, приймаючи і крапку, і кому. */
fun parseDecimalInput(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()

/**
 * Скільки часу минуло: «щойно», «25 хв тому», «3 год тому», «2 дні тому».
 *
 * Відносний час, а не дата: годинник магнітоли на цьому авто збитий, а показувати
 * дату з телефона поруч із даними з машини — привід сплутати одне з іншим.
 * Від'ємний вік (годинник телефона перевели назад) читається як «щойно».
 */
fun formatAgo(ageMs: Long): String = when {
    ageMs < MINUTE_MS -> "щойно"
    ageMs < HOUR_MS -> "${ageMs / MINUTE_MS} хв тому"
    ageMs < DAY_MS -> "${ageMs / HOUR_MS} год тому"
    else -> "${ageMs / DAY_MS} дн тому"
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
