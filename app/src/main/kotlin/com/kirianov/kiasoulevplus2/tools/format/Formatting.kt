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
