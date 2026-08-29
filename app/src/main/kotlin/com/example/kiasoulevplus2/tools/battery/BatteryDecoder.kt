package com.example.kiasoulevplus2.tools.battery
import com.example.kiasoulevplus2.Data.BmsData
import com.example.kiasoulevplus2.services.bluetooth.ElmBluetoothManager
import java.util.Locale

class BatteryDecoder(private val canBridge: ElmBluetoothManager) {

    companion object {
        private const val HEADER_BMS = "7E4"
        private const val CMD_BMS_DATA = "21 01"

        // Індекси байтів
        private const val BMS_SOC_DECIMAL_INDEX = 6 // Байт для SOC Decimal (SoulSpy)
        private const val BMS_VOLTAGE_HIGH_INDEX = 14
        private const val BMS_VOLTAGE_LOW_INDEX = 15
    }

    suspend fun getBatteryData(): BmsData {
        val bmsBytes = fetchAndParseCommand(HEADER_BMS, CMD_BMS_DATA)

        var displaySoc = -1.0
        var actualSoc = -1.0
        var voltage = 0.0

        if (bmsBytes.size > BMS_VOLTAGE_LOW_INDEX) {
            // 1. BMS SOC (Decimal) — формула 1:1 з SoulSpy (battery.SOC_decimal_pct)
            val rawSocByte = bmsBytes[BMS_SOC_DECIMAL_INDEX]
            actualSoc = rawSocByte / 2.0
            
            displaySoc = actualSoc // Тимчасово, поки експериментуємо

            // 2. Pack Voltage — перевірена напруга
            val rawVoltHigh = bmsBytes[BMS_VOLTAGE_HIGH_INDEX]
            val rawVoltLow = bmsBytes[BMS_VOLTAGE_LOW_INDEX]
            voltage = ((rawVoltHigh * 256) + rawVoltLow) / 10.0
        }

        return BmsData(
            displaySoc = displaySoc,
            actualSoc = actualSoc,
            batteryVoltage = voltage,
            batteryCurrent = 0.0,
            batteryTempC = 0.0
        )
    }

    private suspend fun fetchAndParseCommand(
        header: String,
        command: String
    ): List<Int> {
        val rawResponse = canBridge.sendCANCommand(header, command)
        return parseMultiFrameResponse(rawResponse)
    }

    private fun parseMultiFrameResponse(rawResponse: String): List<Int> {
        if (rawResponse.contains("NO DATA") || rawResponse.contains("CAN ERROR")) {
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
            val hexTokens = line.replace(Regex("^[0-9A-Fa-f]:"), "").trim().split(" ")
            for (token in hexTokens) {
                if (token.length == 2) {
                    token.toIntOrNull(16)?.let { byteValue ->
                        byteList.add(byteValue)
                    }
                }
            }
        }

        return byteList
    }
}
