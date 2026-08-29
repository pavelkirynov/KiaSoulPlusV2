package com.example.kiasoulevplus2.Data

/**
 * Клас вхідних даних та запитів для BMS блоку.
 * Сюди UI передає прапорці та параметри, які потрібно відправити в ELM.
 */
data class InputBmsData(
    // Загальні запити BMS:
    val requestBmsData: Boolean = false,   // Запит оновлення PID 21 01
    val resetErrors: Boolean = false,      // Запит на скидання помилок BMS
    val customHeader: String = "7E4",      // За замовчуванням Header для BMS

    // Поля для опитування 96 комірок:
    val scanCellsRequested: Boolean = false, // Прапорець запиту сканування комірок
    val cellCommands: List<String> = listOf("2101", "2102"), // Коди запитів комірок (або "21 02", "21 03", "21 04")
    val rawResponses: List<String> = emptyList() // Сюди Bluetooth-сервіс записує сирі відповіді
)
