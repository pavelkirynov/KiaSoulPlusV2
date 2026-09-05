// ====================================================================================
// ЩО МАЛЮВАТИ НА ВЕЛИКОМУ ЕКРАНІ (CarChartModel)
//
// Медіа-браузер дає рівно дві поверхні для власного малювання. Перша — іконка
// елемента: маленька, і в сітці з неї виходить плитка. Друга — обкладинка
// «того, що зараз грає»: вона займає пів екрана магнітоли, і це єдине місце, де
// наш графік узагалі можна прочитати в машині.
//
// Тому «трек» тут — не музика, а картинка. Водій обирає елемент, хост вважає, що
// почалося відтворення, і показує екран плеера з нашою обкладинкою.
//
// ЩО ВИРІШУЄТЬСЯ ТУТ, А ЩО В МАЛЮВАЛЬНИКУ. Тут — увесь вибір: які точки взяти,
// куди їх покласти в межах полотна, що написати на осях, чого ще бракує для
// малюнка. Це чистий Kotlin під тестами. Малювальник лише перекладає готові
// частки в пікселі й нічого не вирішує.
//
// Координати нормовані: 0..1 по кожній осі, нуль ліворуч і ВНИЗУ. Так модель не
// знає ні розміру картинки, ні того, що в android.graphics вісь Y дивиться вниз.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import com.kirianov.kiasoulevplus2.Data.BatteryCurve
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal

/** Точка в частках полотна: 0..1, нуль ліворуч унизу. */
data class ChartPoint(val x: Double, val y: Double)

/**
 * Одна крива.
 *
 * [dashed] відрізняє доведене від зміряного, і це не косметика: на невиміряній
 * ділянці крива йде середнім нахилом, і малювати її так само, як зміряну,
 * означало б брехати про те, чого ми не знаємо.
 */
data class ChartSeries(
    val points: List<ChartPoint>,
    val dashed: Boolean = false,
    val axis: Axis = Axis.Left,
) {
    enum class Axis { Left, Right }
}

/** Підпис поділки осі: [at] — частка вздовж осі, [text] — що написати. */
data class ChartTick(val at: Double, val text: String)

/**
 * Готовий опис картинки. [message] заповнюється замість кривих, коли малювати ще
 * нічого: порожнє полотно без пояснення виглядає як зламаний застосунок.
 */
data class CarChart(
    val title: String,
    val subtitle: String,
    val series: List<ChartSeries> = emptyList(),
    val leftTicks: List<ChartTick> = emptyList(),
    val rightTicks: List<ChartTick> = emptyList(),
    val bottomTicks: List<ChartTick> = emptyList(),
    val leftUnit: String = "",
    val rightUnit: String = "",
    val message: String = "",
) {
    val hasCurves: Boolean get() = series.any { it.points.size > 1 }
}

object CarChartModel {

    const val CURVE_ID = "screen-curve"
    const val GAUGE_ID = "screen-gauge"

    /** Що можна показати на весь екран. Порядок — той самий, що й перемикання кнопками. */
    val PICTURES = listOf(
        CURVE_ID to "Крива ємності",
        GAUGE_ID to "Заряд великим",
    )

    /**
     * Верх шкали кіловат-годин, поки справжня ємність невідома. Ділиться на чотири
     * без залишку — підписи поділок виходять цілими.
     */
    const val DEFAULT_TOP_KWH = 60.0

    /** Межі шкали напруги, В. Ті самі, що й на екрані телефона: криві мусять збігатися. */
    const val MIN_VOLTS = 300.0
    const val MAX_VOLTS = 420.0

    /**
     * Скільки поділок на кожній осі.
     *
     * Три, а не чотири: обкладинка на машинному екрані виявилася маленькою, і
     * підписи, які не прочитати, гірші за їхню відсутність — вони ще й з'їдають
     * місце в самого графіка.
     */
    const val TICKS = 3

    fun chartFor(id: String, state: State): CarChart = when (id) {
        GAUGE_ID -> gauge(state)
        else -> curve(state.curve)
    }

    /**
     * Крива ємності й крива напруги на одному полотні.
     *
     * Відсоток іде СПРАВА НАЛІВО: сто ліворуч, нуль праворуч. Так прийнято малювати
     * розрядні криві, і так воно читається як рух — зліва повна батарея, праворуч
     * порожня. Той самий порядок, що й на екрані телефона: дві різні картинки
     * однієї величини були б гірші за відсутність другої.
     */
    internal fun curve(curve: BatteryCurve): CarChart {
        val top = topKwh(curve)
        val title = "Ємність по шкалі"
        val subtitle = subtitleOf(curve)

        if (curve.points.size < 2) {
            return CarChart(
                title = title,
                subtitle = subtitle,
                message = "Замірів ще немає. Крива набирається сама, поки авто їздить.",
            )
        }

        val energy = ChartSeries(
            points = curve.points.map { ChartPoint(x = xOf(it.socPercent), y = it.energyKwh / top) },
        )
        // Доведена ділянка окремою пунктирною кривою: інакше «зміряне» й «доведене»
        // на одній лінії не відрізнити.
        val guessed = curve.points.filterNot { it.measured }
        val dashed = if (guessed.size < 2) {
            null
        } else {
            ChartSeries(
                points = guessed.map { ChartPoint(x = xOf(it.socPercent), y = it.energyKwh / top) },
                dashed = true,
            )
        }

        val volts = curve.voltagePoints.takeIf { it.size > 1 }?.let { points ->
            ChartSeries(
                points = points.map {
                    ChartPoint(
                        x = xOf(it.socPercent),
                        y = ((it.volts - MIN_VOLTS) / (MAX_VOLTS - MIN_VOLTS)).coerceIn(0.0, 1.0),
                    )
                },
                axis = ChartSeries.Axis.Right,
            )
        }

        return CarChart(
            title = title,
            subtitle = subtitle,
            series = listOfNotNull(energy, dashed, volts),
            leftTicks = ticks(0.0, top, 0),
            rightTicks = if (volts == null) emptyList() else ticks(MIN_VOLTS, MAX_VOLTS, 0),
            // Ті самі поділки, але підписані навпаки: шкала перевернута.
            bottomTicks = (0..TICKS).map { step ->
                val share = step.toDouble() / TICKS
                ChartTick(at = share, text = "${((1.0 - share) * 100).toInt()}")
            },
            leftUnit = "кВт·год",
            rightUnit = if (volts == null) "" else "В",
        )
    }

    /** Заряд великими цифрами: те саме, що на плитці, але на весь екран. */
    internal fun gauge(state: State): CarChart {
        val real = state.ml.prediction?.realPercent
        val dial = state.vehicle.displaySocPercent.takeIf { state.vehicle.hasDisplaySoc }
            ?: state.bms.displaySoc.takeIf { state.bms.hasData }
        val percent = real ?: dial

        return CarChart(
            title = percent?.let { "${it.toInt()} %" } ?: CarMediaModel.NO_DATA_TEXT,
            subtitle = when {
                percent == null -> "Немає зв'язку з авто"
                real != null -> state.ml.prediction?.rangeKm?.let { "${it.toInt()} км, реальний відсоток" }
                    ?: "реальний відсоток"
                else -> "за панеллю"
            },
            message = "",
        )
    }

    private fun xOf(socPercent: Double): Double = 1.0 - (socPercent / 100.0).coerceIn(0.0, 1.0)

    private fun topKwh(curve: BatteryCurve): Double {
        val measured = curve.points.maxOfOrNull { it.energyKwh } ?: 0.0
        return maxOf(curve.totalKwh, measured, DEFAULT_TOP_KWH * 0.5).takeIf { it > 0.0 }
            ?: DEFAULT_TOP_KWH
    }

    private fun ticks(from: Double, to: Double, decimals: Int): List<ChartTick> =
        (0..TICKS).map { step ->
            val share = step.toDouble() / TICKS
            ChartTick(at = share, text = formatDecimal(from + share * (to - from), decimals))
        }

    /**
     * Підпис під назвою.
     *
     * «Прийнято» звідси прибрано навмисно: у застосунку про зарядки це слово означає
     * «отримано в батарею», і рядок «51.9 кВт·год прийнято» на машинному екрані
     * читався саме так — ніби стільки щойно зарядили. Тут же йшлося про ємність, яку
     * ще не міряли.
     */
    private fun subtitleOf(curve: BatteryCurve): String {
        val total = formatDecimal(curve.totalKwh, 1)
        val proven = if (curve.totalMeasured) "виміряна зарядкою" else "поки за паспортом"
        return "$total кВт·год, $proven; шкали пройдено ${curve.coveredPercent.toInt()} %"
    }
}
