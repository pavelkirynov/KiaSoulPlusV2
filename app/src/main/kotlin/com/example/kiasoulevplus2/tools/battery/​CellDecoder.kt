// ====================================================================================
// ДЕКОДЕР НАПРУГ КОМІРОК БАТАРЕЇ (CellDecoder)
//
// ПРИЗНАЧЕННЯ:
// Цей клас відповідає ВИКЛЮЧНО за математичну обробку та розпаршування сирих HEX-відповідей,
// отриманих від блоку BMS (Battery Management System) електромобіля Kia Soul EV.
//
// ВХІДНІ ДАНІ:
// - InputBmsData (список команд, наприклад ["21 02", "21 03", "21 04"], та відповідні їм
//   сирі текстові рядки відповідей від ELM327/Vlink).
//
// ВИХІДНІ ДАНІ:
// - CellData (обчислений об'єкт із масивом напруг 96 комірок у Вольтах,
//   значеннями minVoltage, maxVoltage, deltaVoltage та розширеним debug-ном для UI).
//
// АЛГОРИТМ ТА ЛОГІКА РОБОТИ:
// 1. Очищує сирі рядочки від службових відповідей ELM327 (PROMPT '>', "SEARCHING...", "NO DATA").
// 2. Видаляє заголовки кадру CAN/ISO-TP (типу "0:", "1:", "2:") та переводить HEX в байти.
// 3. Кожна комірка BMS Kia займає 2 байти, починаючи з 7-го байта корисної відповіді.
// 4. Обчислює напругу: Voltage (V) = ((HighByte shl 8) or LowByte) / 50.0.
// 5. Формує сукупний масив із 96 комірок (32 комірки * 3 кадри = 96S) та виводить детальний
//    лог сирих відповідей безпосередньо у debugInfo для відображення на екрані.
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ надсилає команди в Bluetooth-сокет і не чекає відповідей від авто.
// - НЕ оновлює UI напряму (лише повертає готовий CellData для передачі в GeneralData).
// ====================================================================================

package com.example.kiasoulevplus2.tools.battery

import com.example.kiasoulevplus2.Data.CellData
import com.example.kiasoulevplus2.Data.InputBmsData
import java.util.Locale

class CellDecoder {

    /**
     * Головний метод декодування: приймає пакет відповідей BMS та повертає обчислений CellData.
     */
    fun decodeResponses(inputData: InputBmsData): CellData {
        val allVoltages = mutableListOf<Double>()
        val debugLines = mutableListOf<String>()

        val commands = inputData.cellCommands
        val responses = inputData.rawResponses

        for ((index, rawResponse) in responses.withIndex()) {
            val cmdName = commands.getOrElse(index) { "21 0${index + 2}" }
            val bytes = parseMultiFrameResponse(rawResponse)

            // Фіксуємо у debugInfo сиру відповідь від ELM для виводу на екран
            val cleanRaw = rawResponse.replace("\r", " ").replace("\n", " ").trim()
            if (cleanRaw.isEmpty()) {
                debugLines.add("[$cmdName] RAW: [ПОРОЖНЬО / NO DATA]")
            } else {
                debugLines.add("[$cmdName] RAW: ${cleanRaw.take(45)}...")
            }

            val frameVoltages = mutableListOf<Double>()
            if (bytes.size >= 71) {
                var i = 7
                while (i < 71 && i + 1 < bytes.size) {
                    val highByte = bytes[i]
                    val lowByte = bytes[i + 1]
                    
                    // Об'єднуємо High та Low байти
                    val rawValue = (highByte shl 8) or lowByte
                    
                    // Формула Kia Soul EV: rawValue / 50.0
                    val voltage = rawValue / 50.0

                    // Фільтр адекватності напруги (2.0V - 4.5V)
                    if (voltage in 1.5..4.5) {
                        frameVoltages.add(voltage)
                    } else {
                        frameVoltages.add(0.0)
                    }

                    i += 2
                }
            }

            allVoltages.addAll(frameVoltages)
            debugLines.add("[$cmdName] Зчитано B: ${bytes.size}, Декодовано комірок: ${frameVoltages.size}")
        }

        val count = allVoltages.size
        val validVoltages = allVoltages.filter { it > 0.5 }
        val minV = if (validVoltages.isNotEmpty()) validVoltages.minOrNull() ?: 0.0 else 0.0
        val maxV = if (validVoltages.isNotEmpty()) validVoltages.maxOrNull() ?: 0.0 else 0.0
        val deltaV = maxV - minV

        val fullDebug = debugLines.joinToString("\n") +
                "\n--------------------" +
                "\nВсього комірок: $count / 96"

        return CellData(
            cellVoltages = allVoltages,
            minVoltage = minV,
            maxVoltage = maxV,
            deltaVoltage = deltaV,
            debugInfo = fullDebug
        )
    }

    /**
     * Очищає відповідь від сміття та розбиває багаторамковий (Multi-frame) ISO-TP запит на байти.
     */
    private fun parseMultiFrameResponse(rawResponse: String): List<Int> {
        if (rawResponse.contains("NO DATA") || rawResponse.contains("CAN ERROR") || rawResponse.isBlank()) {
            return emptyList()
        }

        val cleaned = rawResponse
            .replace(">", "")
            .replace("SEARCHING...", "")
            .replace("STOPPED", "")
            .trim()

        val lines = cleaned.split("\r", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        val byteList = mutableListOf<Int>()

        for (line in lines) {
            // Видаляємо префікси преамбули кадру CAN (наприклад "0:", "1:", "2:")
            val cleanLine = line.replace(Regex("^[0-9A-Fa-f]:"), "").trim()
            val hexTokens = cleanLine.split(" ", "\t")

            for (token in hexTokens) {
                val t = token.trim()
                if (t.length == 2) {
                    t.toIntOrNull(16)?.let { byteValue ->
                        byteList.add(byteValue)
                    }
                }
            }
        }

        return byteList
    }
}
