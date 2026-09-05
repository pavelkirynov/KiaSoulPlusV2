package com.kirianov.kiasoulevplus2.Data

/**
 * Запити, які інтерфейс ставить фоновому опитуванню CAN.
 */
data class InputBmsData(
    /** CAN-заголовок, за яким опитується блок. */
    val customHeader: String = BmsCommands.HEADER_BMS,

    /** Прапорець «зчитати комірки»: блок Bluetooth скидає його після циклу. */
    val scanCellsRequested: Boolean = false,

    /** Команди для зчитування комірок. */
    val cellCommands: List<String> = BmsCommands.REQUEST_CELL_VOLTAGES,
)
