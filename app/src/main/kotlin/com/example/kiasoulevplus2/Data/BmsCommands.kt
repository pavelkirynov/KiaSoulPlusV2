package com.example.kiasoulevplus2.Data // Перевір свій package

/**
 * Довідник CAN-команд для BMS.
 * Тут зберігаються тільки текстові рядки запитів без жодної логіки.
 */
object BmsCommands {
    // Основний запит параметрів акумулятора (SOC, напруга, струм, температури)
    const val REQUEST_BATTERY_MAIN = "7E4 21 01"
    
    // Запит стану окремих комірок (на майбутнє)
    const val REQUEST_CELL_VOLTAGES = "7E4 21 02"
}
