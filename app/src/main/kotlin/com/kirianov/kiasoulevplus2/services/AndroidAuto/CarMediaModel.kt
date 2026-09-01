// ====================================================================================
// ВМІСТ МЕДІА-СПИСКУ ДЛЯ ANDROID AUTO (CarMediaModel)
//
// ЧОМУ МЕДІА, А НЕ ШАБЛОН:
// Template-застосунок (androidx.car.app) Android Auto показує лише тоді, коли він
// прийшов з Play Market або коли в налаштуваннях AA увімкнено режим розробника й
// «невідомі джерела». Медіа-браузер такого обмеження не має: хост знаходить будь-який
// встановлений MediaBrowserService. Тому показники віддаються як «медіатека».
//
// ЩО ЦЕ НЕ ДАЄ: власного вигляду. Хост малює свій список рядків і плиток; підсунути
// туди сітку з машинного скриншота неможливо. Тому дані згруповані в ті самі чотири
// розділи, але виглядатиме це як список Android Auto.
//
// Чистий об'єкт без Android: перевіряється тестами без магнітоли, як і CarPaneModel.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import com.kirianov.kiasoulevplus2.tools.format.formatDuration
import com.kirianov.kiasoulevplus2.tools.format.formatMeasurement

/**
 * Один рядок медіа-списку.
 *
 * [browsable] — чи можна в нього «зайти». Показники робляться browsable, а не
 * playable, навмисно: натиснувши playable-рядок, хост перемикається на екран
 * «зараз грає», якого тут немає й бути не може.
 */
data class CarMediaItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val browsable: Boolean,
)

object CarMediaModel {

    const val ROOT_ID = "root"

    const val BATTERY_ID = "battery"
    const val PERFORMANCE_ID = "performance"
    const val ENERGY_ID = "energy"
    const val TRIP_ID = "trip"

    const val NO_DATA_TEXT = "--"

    /** Розділи кореня. Порядок повторює плитки на машинному екрані. */
    private val SECTIONS = listOf(
        BATTERY_ID to "Батарея",
        PERFORMANCE_ID to "Рух і потужність",
        ENERGY_ID to "Потік енергії",
        TRIP_ID to "Поїздка",
    )

    /**
     * Вміст вузла [parentId].
     *
     * Невідомий id повертає корінь: хост інколи питає збережений id, якого вже немає,
     * і порожня відповідь виглядала б як «медіатека зникла».
     */
    fun childrenOf(parentId: String, state: State): List<CarMediaItem> = when (parentId) {
        BATTERY_ID -> battery(state)
        PERFORMANCE_ID -> performance(state)
        ENERGY_ID -> energy(state)
        TRIP_ID -> trip(state)
        else -> root(state)
    }

    private fun root(state: State): List<CarMediaItem> {
        val status = if (state.isConnected) {
            "Підключено"
        } else {
            "Немає зв'язку з адаптером"
        }

        return SECTIONS.mapIndexed { index, (id, title) ->
            CarMediaItem(
                id = id,
                title = title,
                // Стан з'єднання ставиться під перший розділ: інакше водій не зрозуміє,
                // чому всі значення раптом «--».
                subtitle = if (index == 0) status else null,
                browsable = true,
            )
        }
    }

    private fun battery(state: State): List<CarMediaItem> {
        val bms = state.bms
        val vehicle = state.vehicle
        return listOf(
            row("soc-display", "SOC панелі", percentOrNull(vehicle.displaySocPercent.takeIf { vehicle.hasDisplaySoc })),
            row("soc-bms", "SOC з BMS", percentOrNull(bms.displaySoc.takeIf { bms.hasData })),
            row("voltage", "Напруга", measureOrNull(bms.batteryVoltage.takeIf { bms.hasData }, 1, "В")),
            row("temperature", "Температура ВВБ", measureOrNull(bms.batteryTempC.takeIf { bms.hasData }, 1, "°C")),
            row("range", "Запас ходу", measureOrNull(vehicle.rangeKm.toDouble().takeIf { vehicle.hasRange }, 0, "км")),
        )
    }

    private fun performance(state: State): List<CarMediaItem> {
        val bms = state.bms
        val vehicle = state.vehicle
        return listOf(
            row("speed", "Швидкість", measureOrNull(vehicle.speedKmh.takeIf { vehicle.hasSpeed }, 0, "км/год")),
            row("power", "Потужність", measureOrNull(state.calculated.powerKw.takeIf { bms.hasData }, 2, "кВт")),
            row("current", "Струм", measureOrNull(bms.batteryCurrent.takeIf { bms.hasData }, 1, "А")),
            row("odometer", "Пробіг", measureOrNull(vehicle.odometerKm.takeIf { vehicle.hasOdometer }, 1, "км")),
            row("ambient", "За бортом", measureOrNull(vehicle.ambientTempC.takeIf { vehicle.hasAmbientTemp }, 1, "°C")),
        )
    }

    private fun energy(state: State): List<CarMediaItem> {
        val bms = state.bms
        val calculated = state.calculated
        val counters = bms.hasEnergyCounters
        return listOf(
            row("discharged-total", "Віддано за весь час", measureOrNull(bms.cumulativeEnergyDischargedKwh.takeIf { counters }, 1, "кВт·год")),
            row("charged-total", "Прийнято за весь час", measureOrNull(bms.cumulativeEnergyChargedKwh.takeIf { counters }, 1, "кВт·год")),
            row("discharged-ah", "Віддано, А·год", measureOrNull(bms.cumulativeDischargedAh.takeIf { counters }, 1, "А·год")),
            row("cell-min", "Мін. комірка", measureOrNull(calculated.minCellVoltage.takeIf { it > 0.0 }, 2, "В")),
            row("cell-max", "Макс. комірка", measureOrNull(calculated.maxCellVoltage.takeIf { it > 0.0 }, 2, "В")),
            row("cell-delta", "Розкид комірок", measureOrNull(calculated.cellDeltaVolts.takeIf { calculated.maxCellVoltage > 0.0 }, 3, "В")),
        )
    }

    private fun trip(state: State): List<CarMediaItem> {
        val stats = state.calculated.trip
        return listOf(
            row("consumption", "Витрата", stats.kwhPer100Km?.let { "${formatDecimal(it, 1)} кВт·год/100 км" }),
            row("distance", "Пройдено", measureOrNull(stats.distanceKm.takeIf { it > 0.0 }, 1, "км")),
            row("duration", "Час", formatDuration(stats.durationMs).takeIf { stats.hasData }),
            row("avg-speed", "Сер. швидкість", measureOrNull(stats.averageSpeedKmh, 0, "км/год")),
            row("net", "Витрачено", measureOrNull(stats.netKwh.takeIf { stats.hasData }, 2, "кВт·год")),
        )
    }

    private fun row(id: String, title: String, value: String?) = CarMediaItem(
        id = id,
        title = title,
        subtitle = value ?: NO_DATA_TEXT,
        // Показник теж browsable: playable-рядок відкрив би екран «зараз грає».
        browsable = true,
    )

    private fun percentOrNull(value: Double?) = value?.let { "${formatDecimal(it, 1)} %" }

    private fun measureOrNull(value: Double?, decimals: Int, unit: String) =
        value?.let { formatMeasurement(it, decimals, unit) }
}
