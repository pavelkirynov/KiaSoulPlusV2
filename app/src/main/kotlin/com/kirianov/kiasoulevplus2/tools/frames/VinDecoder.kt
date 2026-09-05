// ====================================================================================
// РОЗБІР VIN (VinDecoder)
//
// VIN — єдина річ на шині, яка відповідає на питання «а це взагалі те саме авто».
// Без нього застосунок, підключившись до чужої машини, спокійно домішав би її
// поїздки до вашої моделі й зіпсував би обидві.
//
// Приходить він на запит OBD `09 02` — сервіс читання, той самий, що вже
// дозволений. Відповідь багаторамкова: `49 02 01` і далі сімнадцять символів
// ASCII. Байт після `02` — номер порції; авто віддає одну, але перевіряти його
// значення не варто: деякі блоки нумерують з нуля, деякі з одиниці.
//
// ЧОМУ РОЗБІР ТАКИЙ ПРИСКІПЛИВИЙ. Клони ELM327 домішують до відповіді паддінг
// нулями, а порожні місця добивають байтом 0x00. Просто взяти сімнадцять байтів
// після заголовка означало б інколи отримати VIN із дірками. Тому беремо лише
// дозволені у VIN символи — і рівно стільки, скільки їх має бути.
//
// Чисті функції без стану, як і решта tools/frames.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

object VinDecoder {

    /** Скільки символів у VIN за стандартом ISO 3779. */
    const val LENGTH = 17

    /**
     * Літери I, O та Q у VIN не використовуються навмисно — щоб не плутати їх з
     * одиницею й нулем. Тому вони й не приймаються: символ, який не може бути у
     * VIN, означає, що ми читаємо не VIN.
     */
    private const val ALLOWED = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"

    /** Заголовок відповіді на `09 02`: сервіс 09 + 0x40, потім номер PID. */
    private const val RESPONSE_SERVICE = 0x49
    private const val RESPONSE_PID = 0x02

    /**
     * VIN із розібраних байтів відповіді, або null, якщо це не він.
     *
     * @param bytes байти відповіді разом із заголовком `49 02 ...`.
     */
    fun decode(bytes: List<Int>): String? {
        val start = headerAt(bytes) ?: return null
        val text = StringBuilder()
        for (index in start until bytes.size) {
            val symbol = bytes[index].toChar().uppercaseChar()
            if (symbol in ALLOWED) text.append(symbol)
            if (text.length == LENGTH) break
        }
        return text.toString().takeIf { it.length == LENGTH }
    }

    /**
     * Індекс першого символу VIN, або null, якщо заголовка немає.
     *
     * Шукаємо `49 02` не лише на початку: клони інколи лишають попереду службові
     * байти, і жорстка прив'язка до нульового індексу підводила вже не раз.
     */
    private fun headerAt(bytes: List<Int>): Int? {
        for (index in 0 until bytes.size - 2) {
            if (bytes[index] == RESPONSE_SERVICE && bytes[index + 1] == RESPONSE_PID) {
                // Далі йде номер порції, і лише за ним — символи.
                return index + 3
            }
        }
        return null
    }

    /** Чи схоже це на справжній VIN: правильна довжина й лише дозволені символи. */
    fun isValid(vin: String): Boolean =
        vin.length == LENGTH && vin.all { it.uppercaseChar() in ALLOWED }

    /** Короткий вигляд для екрана: останні шість символів однозначні на практиці. */
    fun shortForm(vin: String): String = if (vin.length <= 6) vin else vin.takeLast(6)
}
