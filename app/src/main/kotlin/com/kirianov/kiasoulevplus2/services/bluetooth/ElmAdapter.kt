package com.kirianov.kiasoulevplus2.services.bluetooth

/**
 * Те, що вміє адаптер ELM327: прийняти текстову команду і повернути відповідь.
 *
 * Винесено інтерфейсом, щоб ElmCANBridge можна було перевірити тестами без
 * Bluetooth і без авто — зокрема те, що заголовок і команда йдуть неподільно.
 */
interface ElmAdapter {
    suspend fun sendCommand(command: String): String
}
