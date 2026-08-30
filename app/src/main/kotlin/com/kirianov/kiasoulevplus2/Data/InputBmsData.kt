package com.kirianov.kiasoulevplus2.Data

/**
 * Запити, які UI ставить фоновому опитуванню CAN.
 */
data class InputBmsData(
    /** CAN-заголовок, за яким опитується блок. */
    val customHeader: String = BmsCommands.HEADER_BMS,

    /** Прапорець «зчитати комірки»: ConnectionManager скидає його після циклу. */
    val scanCellsRequested: Boolean = false,

    /** Команди для зчитування комірок. */
    val cellCommands: List<String> = BmsCommands.REQUEST_CELL_VOLTAGES,

    /** Сирі відповіді ELM327 на cellCommands — їх показує лог на екрані комірок. */
    val rawResponses: List<String> = emptyList(),
)
