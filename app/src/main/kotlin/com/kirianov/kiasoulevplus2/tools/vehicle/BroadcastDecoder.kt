// ====================================================================================
// ДЕКОДЕР ШИРОКОМОВНИХ КАДРІВ (BroadcastDecoder)
//
// Формули звірені з вихідниками SoulEVSpy — єдиного відомого проєкту, який ці
// значення реально дістає з Kia Soul EV 27 kWh.
//
// ВАЖЛИВО ПРО ПРОБІГ: у кадрі 4F0 він лежить у порядку little-endian з роздільністю
// 0.1 км. Саме тому пошук цілого числа кілометрів у прямому порядку байтів його
// ніколи не знаходив — ні в цьому кадрі, ні в запитах-відповідях, де його взагалі немає.
//
// Кадри приходять різними порціями, тому декодер ДОМІШУЄ нові значення до
// попередніх: якщо в цьому вікні кадру 653 не було, температура лишається старою.
//
// Чистий об'єкт без стану: покривається тестами без адаптера й без авто.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame
import com.kirianov.kiasoulevplus2.Data.ChargerType
import com.kirianov.kiasoulevplus2.Data.ChargingState
import com.kirianov.kiasoulevplus2.Data.VehicleData

object BroadcastDecoder {

    /** ID кадрів, які додаток розбирає. Решта в потоці монітора ігнорується. */
    val KNOWN_IDS = setOf(ODOMETER_AND_SPEED, DISPLAY_SOC, PRECISE_SOC, RANGE, AMBIENT_TEMP, CHARGING)

    /** Домішує до [previous] усе, що вдалося розібрати з [frames]. */
    fun merge(previous: VehicleData, frames: List<CanBroadcastFrame>): VehicleData =
        frames.fold(previous) { data, frame -> apply(data, frame) }

    private fun apply(data: VehicleData, frame: CanBroadcastFrame): VehicleData {
        val b = frame.bytes
        return when (frame.id) {
            ODOMETER_AND_SPEED -> if (b.size < 8) data else data.copy(
                // little-endian, десяті кілометра
                odometerKm = (b[5] or (b[6] shl 8) or (b[7] shl 16)) / 10.0,
                speedKmh = (b[1] or ((b[2] and 0x01) shl 8)) / 2.0,
            )

            DISPLAY_SOC -> if (b.size < 8) data else data.copy(
                displaySocPercent = b[5] / 2.0 + (b[6] and 0x07) / 10.0,
            )

            PRECISE_SOC -> if (b.size < 8) data else data.copy(
                preciseSocPercent = ((b[5] shl 8) + b[4]) / 256.0,
            )

            RANGE -> if (b.size < 8) data else data.copy(
                rangeKm = (b[2] shl 1) + (b[1] shr 7),
            )

            AMBIENT_TEMP -> if (b.size < 8) data else data.copy(
                ambientTempC = b[5] / 2.0 - 40.0,
            )

            CHARGING -> if (b.size < 8) data else data.copy(
                charging = ChargingState(
                    isCharging = b[3] != 0,
                    chargerType = when (b[5]) {
                        0x0D -> ChargerType.Type1
                        0x0E -> ChargerType.J1772
                        else -> ChargerType.None
                    },
                    powerKw = ((b[7] shl 8) + b[6]) / 256.0,
                ),
            )

            else -> data
        }
    }

    private const val ODOMETER_AND_SPEED = "4F0"
    private const val DISPLAY_SOC = "594"
    private const val PRECISE_SOC = "598"
    private const val RANGE = "200"
    private const val AMBIENT_TEMP = "653"
    private const val CHARGING = "581"
}
