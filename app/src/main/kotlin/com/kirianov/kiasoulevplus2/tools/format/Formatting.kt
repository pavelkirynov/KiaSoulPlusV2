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
 * Секунди від початку доби у «ГГ:ХХ:СС». Саме секунди від доби, а не мітка часу:
 * кадр 567 віддає годинник магнітоли, дати в ньому немає.
 */
fun formatClock(secondsOfDay: Int): String {
    val normalized = secondsOfDay.mod(SECONDS_PER_DAY)
    return "%02d:%02d:%02d".format(
        normalized / SECONDS_PER_HOUR,
        normalized % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
        normalized % SECONDS_PER_MINUTE,
    )
}

/**
 * Розходження годинників «на око»: «+3 хв 12 с» читається краще за «192 с».
 * Знак лишається завжди — без нього не видно, спішить годинник чи відстає.
 */
fun formatDriftSigned(seconds: Int): String {
    val sign = if (seconds < 0) "-" else "+"
    val absolute = kotlin.math.abs(seconds)
    val minutes = absolute / SECONDS_PER_MINUTE
    val rest = absolute % SECONDS_PER_MINUTE
    return if (minutes == 0) "$sign$rest с" else "$sign$minutes хв $rest с"
}

/**
 * Швидкість ходу годинника відносно телефона: «+42 с/год».
 * Саме секунди на годину — у цих одиницях видно різницю за кілька хвилин поїздки.
 */
fun formatRate(secondsPerHour: Double): String {
    val sign = if (secondsPerHour < 0) "-" else "+"
    return "$sign${formatDecimal(kotlin.math.abs(secondsPerHour), 0)} с/год"
}

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
private const val SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR
