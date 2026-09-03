package com.kirianov.kiasoulevplus2.Data

/**
 * Крива «скільки кВт·год у батареї на цьому відсотку», зміряна по факту.
 *
 * ЧОМУ ЦЕ ОКРЕМА РІЧ, А НЕ ЧАСТИНА ПРОГНОЗУ. Прогноз запасу ходу вчиться на
 * відрізках: інтегрує миттєву потужність і вимагає неперервних даних, довжини й
 * покриття. Крива ємності таких вимог не потребує взагалі, бо міряється зовсім
 * інакше — РІЗНИЦЕЮ ПОЖИТТЄВИХ ЛІЧИЛЬНИКІВ BMS.
 *
 * Лічильник відданої енергії — абсолютне число, яке веде сама батарея. Йому
 * байдуже, чи стояв застосунок у той момент, чи обірвався Bluetooth, чи з яким
 * знаком ми прочитали струм. Якщо на 93.7 % лічильник показував X, а на 91.2 %
 * став X+0.5, то ці 2.5 % шкали містили 0.5 кВт·год — і це вимір, а не оцінка.
 *
 * Ціна такої простоти — крок лічильника 0.1 кВт·год. Тому один замір беремо не
 * раніше, ніж набіжить близько кіловат-години, а повторні проходи тим самим
 * відсотком усереднюються: що більше поїздок, то точніша крива.
 */
data class BatteryCurve(
    /** Точки через 1 % шкали. [CurvePoint.measured] каже, вимір це чи доведення. */
    val points: List<CurvePoint> = emptyList(),

    /** Межі виміряної ділянки шкали. null — не міряли ще нічого. */
    val measuredFromPercent: Double? = null,
    val measuredToPercent: Double? = null,

    /** Яку частину шкали вже виміряно, у відсотках. */
    val coveredPercent: Double = 0.0,

    /** Повна ємність за кривою: скільки кВт·год від 0 до 100 %. */
    val fullKwh: Double = 0.0,

    /** Скільки замірів увійшло в криву. */
    val samples: Int = 0,

    val request: CurveRequest = CurveRequest.None,
) {
    val hasMeasurements: Boolean get() = samples > 0 && measuredFromPercent != null

    /** Скільки кВт·год лишається на заданому відсотку. */
    fun energyAt(socPercent: Double): Double? {
        if (points.isEmpty()) return null
        val below = points.lastOrNull { it.socPercent <= socPercent } ?: return points.first().energyKwh
        val above = points.firstOrNull { it.socPercent >= socPercent } ?: return points.last().energyKwh
        if (above.socPercent == below.socPercent) return below.energyKwh
        val share = (socPercent - below.socPercent) / (above.socPercent - below.socPercent)
        return below.energyKwh + share * (above.energyKwh - below.energyKwh)
    }
}

/**
 * Точка кривої.
 *
 * [measured] відрізняє виміряне від доведеного. Це не косметика: на невиміряній
 * ділянці шкали крива йде середнім нахилом, і показувати її так само, як
 * зміряну, означало б брехати про те, чого не знаємо.
 */
data class CurvePoint(
    val socPercent: Double,
    val energyKwh: Double,
    val measured: Boolean,
)

enum class CurveRequest {
    None,

    /** Забути виміряну криву й почати заново. */
    Reset,
}
