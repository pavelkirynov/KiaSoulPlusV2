package com.kirianov.kiasoulevplus2.Data

/**
 * Показники, які авто транслює широкомовними кадрами, а не віддає на запит.
 *
 * Кожне поле має власну познаку «немає даних»: у одному вікні монітора приходять
 * не всі кадри, тому нові дані домішуються до попередніх, а не замінюють їх цілком.
 */
data class VehicleData(
    /** Пробіг, км. Кадр 4F0, роздільність 0.1 км. */
    val odometerKm: Double = 0.0,

    /** Швидкість, км/год. Кадр 4F0. */
    val speedKmh: Double = NO_DATA,

    /** SOC як на панелі, %. Кадр 594. */
    val displaySocPercent: Double = NO_DATA,

    /** SOC точний, з BMS, %. Кадр 598. Різниця з панельним показує буфери батареї. */
    val preciseSocPercent: Double = NO_DATA,

    /** Залишок ходу за бортовим компьютером, км. Кадр 200. */
    val rangeKm: Int = 0,

    /** Температура за бортом, °C. Кадр 653. Власна познака: −40 °C теж дійсне значення. */
    val ambientTempC: Double = NO_TEMPERATURE,

    /** Стан заряджання. Кадр 581. */
    val charging: ChargingState = ChargingState(),

    /**
     * Годинник авто, СЕКУНДИ від початку доби. Кадр 567.
     *
     * Секунди, а не хвилини: щоб побачити, що годинник магнітоли йде з іншою
     * швидкістю, потрібна роздільність краща за хвилину — інакше на вимір швидкості
     * ходу пішли б години.
     *
     * null, а не познака числом: 0 — це рівно 00:00:00, дійсне значення.
     */
    val clockSecondsOfDay: Int? = null,
) {
    val hasOdometer: Boolean get() = odometerKm > 0.0
    val hasSpeed: Boolean get() = speedKmh >= 0.0
    val hasDisplaySoc: Boolean get() = displaySocPercent >= 0.0
    val hasPreciseSoc: Boolean get() = preciseSocPercent >= 0.0
    val hasRange: Boolean get() = rangeKm > 0
    val hasAmbientTemp: Boolean get() = ambientTempC > NO_TEMPERATURE
    val hasClock: Boolean get() = clockSecondsOfDay != null

    companion object {
        const val NO_DATA = -1.0

        /** Нижче будь-якої реальної температури: кадр 653 дає від −40 до +87.5 °C. */
        const val NO_TEMPERATURE = -1000.0
    }
}

enum class ChargerType { None, Type1, J1772 }

data class ChargingState(
    val isCharging: Boolean = false,
    val chargerType: ChargerType = ChargerType.None,
    val powerKw: Double = 0.0,
)
