// ====================================================================================
// ПЕРЕВІРКА РУЧНИХ ЗАПИТІВ (CanCommand)
//
// Екран «Експерименти» дозволяє надіслати в авто довільну команду. Дозволяються
// лише сервіси ЧИТАННЯ. Запис у блоки керування (сервіси 2E, 31, 2F та подібні)
// може змінити налаштування автомобіля, і в діагностичному застосунку такому
// не місце — тому команда з незнайомим сервісом просто не надсилається.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

object CanCommand {

    /** Сервіси, які лише читають дані з блоків. */
    private val READ_ONLY_SERVICES = setOf(
        "01", // поточні дані OBD-II
        "02", // freeze frame
        "09", // інформація про авто
        "19", // читання помилок (UDS)
        "21", // читання даних Kia/Hyundai
        "22", // читання за ідентифікатором (UDS)
    )

    private val HEADER_PATTERN = Regex("^[0-9A-Fa-f]{3}$|^[0-9A-Fa-f]{8}$")

    /** Прибирає пробіли й приводить до верхнього регістру. */
    fun normalize(text: String): String = text.trim().uppercase().replace(Regex("\\s+"), " ")

    fun isValidHeader(header: String): Boolean = HEADER_PATTERN.matches(header.trim())

    /**
     * Повертає причину відмови або null, якщо команду можна надсилати.
     */
    fun rejectionReason(command: String): String? {
        val normalized = normalize(command)
        if (normalized.isEmpty()) return "Порожня команда"

        val service = normalized.replace(" ", "").take(2)
        if (service.length < 2) return "Замало символів для номера сервісу"
        if (!normalized.replace(" ", "").matches(Regex("^[0-9A-F]+$"))) {
            return "Команда має складатися з шістнадцяткових цифр"
        }

        return if (service in READ_ONLY_SERVICES) {
            null
        } else {
            "Сервіс $service не є сервісом читання. Дозволені: ${READ_ONLY_SERVICES.joinToString(", ")}"
        }
    }
}
