// Графік «скільки кВт·год на цьому відсотку» плюс крива напруги на тому самому
// полотні. По горизонталі — відсоток шкали, СПРАВА НАЛІВО: сто зліва, нуль
// справа. Так прийнято малювати розрядні криві, і так воно читається як рух —
// зліва повна батарея, праворуч порожня.
//
// КРИВИХ ЄМНОСТІ ТУТ ДВІ, І РІЗНИЦЯ МІЖ НИМИ — ГОЛОВНЕ, ЩО ПОКАЗУЄ ЦЕЙ ГРАФІК.
// Обидві починаються з однієї верхньої точки — заявленої в налаштуваннях ємності
// на ста відсотках — і йдуть униз по виміряних нахилах. Куди кожна сяде на нулі,
// те й є її відповідь про справжню ємність.
//
//   A «за лічильниками» — ΔkWhOut − ΔkWhIn з пожиттєвих лічильників BMS.
//   B «за струмом» — ΔkWhOut з лічильника мінус рекуперація за інтегралом.
//
// Лічильник прийнятої енергії міряє не кіловат-години, а шкалу РІДНОГО пакета:
// нічна зарядка на 49.6 кВт·год за лічильником станції дала за ним лише 23.0.
// Тому A сідає високо над нулем, а B — там, де насправді. Обидві на одному
// полотні саме для того, щоб цю розбіжність було видно, а не описано словами.
//
// ПРЯМА ЛІНІЯ ТТХ — заявлена ємність, розкладена по шкалі рівно. Орієнтир, від
// якого відхиляються обидві криві.
//
// Крива напруги пояснює, ЧОМУ шкала нерівна: BMS розкладає відсотки за
// заводською таблицею напруг, а комірки стоять інші, з іншим діапазоном. Де
// напруга йде рівно, а кіловат-години ні — там і сидить уся розбіжність. Тому в
// кожної кривої своя вертикаль: кВт·год ліворуч, вольти праворуч.
//
// ОКРЕМИМ ПОЛОТНОМ — КІЛОМЕТРИ НА ВІДСОТОК. Вертикаль перевернута: нуль угорі,
// десять кілометрів унизу, тож «довгі» відсотки провисають. Це НЕ властивість
// батареї, а слід того, як їздили: той самий відсоток у місті з кліматом і на
// трасі коштує різного пробігу. Тому доводити тут нема чого — точки є лише там,
// де справді їхали.
//
// ОСІ Й СІТКА МАЛЮЮТЬСЯ ЗАВЖДИ, навіть коли замірів ще нуль. Порожнє полотно з
// підписаними осями показує, що графік є і чого він чекає; картка, яка до першого
// заміру складається в абзац тексту, виглядає як відсутня функція.
//
// ПІДПИСИ ОСЕЙ — ЗВИЧАЙНІ Text, а не малювання по полотну, і розкладені за
// відомою висотою полотна. Тому висота задана числом, а не пропорцією: інакше
// цифру ніяк не поставити рівно проти її лінії сітки.
//
// ЩО ТУТ ВИМІРЯНЕ, А ЩО ДОВЕДЕНЕ. Суцільна ділянка — та, де крива справді зміряна.
// Пунктир — доведення нахилом сусідів: ми ще не бували на цих відсотках, і
// показувати їх так само, як зміряні, означало б брехати про те, чого не знаємо.

package com.kirianov.kiasoulevplus2.Interface.screens.prediction

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.kirianov.kiasoulevplus2.Data.BatteryCurve
import com.kirianov.kiasoulevplus2.Data.CurvePoint
import com.kirianov.kiasoulevplus2.Data.DistancePoint
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import kotlin.math.ceil

/** Висота полотна. Число, а не пропорція: за ним розкладаються підписи осі. */
private val PLOT_HEIGHT = 200.dp

/** Ширина стовпчика цифр біля вертикальної осі. */
private val AXIS_WIDTH = 34.dp

/** Межі вертикалі напруги, В: робоче вікно цього пакета з запасом. */
private const val MIN_VOLTS = 300.0
private const val MAX_VOLTS = 420.0

/**
 * Скільки кВт·год показувати на осі, поки не зміряно нічого.
 *
 * Шістдесят: перепакована батарея цього авто — близько 51 кВт·год, і вісь має
 * бути з запасом над нею, щоб перша ж крива не втикалася в стелю.
 */
private const val DEFAULT_TOP_KWH = 60.0

/** Висота полотна кілометрів. Нижче за головне: у нього одна крива. */
private val DISTANCE_PLOT_HEIGHT = 120.dp

/** Стеля вертикалі кілометрів на відсоток. Десять — з запасом над усім баченим. */
private const val MAX_KM_PER_PERCENT = 10.0

/** Ширша прогалина між точками — не крива, а два різні шматки шкали. */
private const val MAX_JOIN_PERCENT = 3.0

@Composable
fun BatteryCurveCard(curve: BatteryCurve, vehicle: VehicleData, onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Ємність по шкалі", fontSize = 18.sp)

            val lineColor = MaterialTheme.colorScheme.primary
            val counterColor = MaterialTheme.colorScheme.secondary
            val voltsColor = MaterialTheme.colorScheme.tertiary
            val inferredColor = MaterialTheme.colorScheme.outline
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val markerRing = MaterialTheme.colorScheme.surface

            val topKwh = topOf(curve)
            val step = if (topKwh > 60.0) 20.0 else 10.0
            val nowSoc = vehicle.preciseSocPercent.takeIf { vehicle.hasPreciseSoc }

            Row(modifier = Modifier.fillMaxWidth()) {
                // Цифри вертикальної осі. Кожна зсунута рівно на висоту своєї
                // лінії сітки, тому й висота полотна тут задана числом.
                Box(
                    modifier = Modifier
                        .width(AXIS_WIDTH)
                        .height(PLOT_HEIGHT),
                ) {
                    var value = 0.0
                    while (value <= topKwh) {
                        val share = 1.0 - value / topKwh
                        Text(
                            text = formatDecimal(value, 0),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp)
                                // Мінус пів рядка: підпис має стояти серединою
                                // проти лінії, а не низом.
                                .offset(y = PLOT_HEIGHT * share.toFloat() - 8.dp),
                        )
                        value += step
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(PLOT_HEIGHT),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Справа наліво: сто відсотків зліва, нуль справа.
                        fun x(percent: Double) = (1.0 - percent / 100.0).toFloat() * size.width
                        fun y(kwh: Double) = (1.0 - kwh / topKwh).toFloat() * size.height

                        // Сітка: кожні 20 % і кожні step кВт·год. Рідка навмисно —
                        // вона для оцінки на око, а не для зняття значень.
                        for (percent in 0..100 step 20) {
                            drawLine(gridColor, Offset(x(percent.toDouble()), 0f), Offset(x(percent.toDouble()), size.height), 1f)
                        }
                        var value = 0.0
                        while (value <= topKwh) {
                            drawLine(gridColor, Offset(0f, y(value)), Offset(size.width, y(value)), 1f)
                            value += step
                        }

                        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

                        // Пряма ТТХ: заявлена ємність, розкладена по шкалі рівно.
                        // Орієнтир, від якого відхиляються обидві криві.
                        if (curve.totalKwh > 0.0) {
                            drawLine(
                                color = gridColor,
                                start = Offset(x(100.0), y(curve.totalKwh)),
                                end = Offset(x(0.0), y(0.0)),
                                strokeWidth = 2f,
                                pathEffect = dash,
                            )
                        }

                        // Крива по відрізках: суцільна там, де обидва кінці зміряні.
                        fun draw(points: List<CurvePoint>, color: Color, width: Float) {
                            points.zipWithNext { from, to ->
                                val measured = from.measured && to.measured
                                drawLine(
                                    color = if (measured) color else inferredColor,
                                    start = Offset(x(from.socPercent), y(from.energyKwh)),
                                    end = Offset(x(to.socPercent), y(to.energyKwh)),
                                    strokeWidth = if (measured) width else 2f,
                                    pathEffect = if (measured) null else dash,
                                )
                            }
                        }

                        // Спершу A, потім B: те, чому віримо, лягає зверху.
                        draw(curve.counterPoints, counterColor, 3f)
                        draw(curve.powerPoints, lineColor, 4f)

                        // Крива напруги: своя вертикаль, свій колір. Точки є лише
                        // там, де замірів вистачило прибрати просадку під струмом.
                        fun yVolts(volts: Double) =
                            (1.0 - (volts - MIN_VOLTS) / (MAX_VOLTS - MIN_VOLTS)).toFloat() * size.height
                        curve.voltagePoints.zipWithNext { from, to ->
                            drawLine(
                                color = voltsColor,
                                start = Offset(x(from.socPercent), yVolts(from.volts)),
                                end = Offset(x(to.socPercent), yVolts(to.volts)),
                                strokeWidth = 3f,
                            )
                        }

                        // Де авто зараз: точка на кривій, щоб число з екрана мало місце.
                        nowSoc?.let { soc ->
                            curve.energyAt(soc)?.let { energy ->
                                val center = Offset(x(soc), y(energy))
                                drawCircle(markerRing, radius = 8f, center = center)
                                drawCircle(lineColor, radius = 5f, center = center)
                            }
                        }
                    }
                }

                // Права вісь — вольти. Ставиться тільки коли є що на ній читати.
                Box(
                    modifier = Modifier
                        .width(AXIS_WIDTH)
                        .height(PLOT_HEIGHT),
                ) {
                    if (curve.voltagePoints.isNotEmpty()) {
                        var volts = MIN_VOLTS
                        while (volts <= MAX_VOLTS) {
                            val share = 1.0 - (volts - MIN_VOLTS) / (MAX_VOLTS - MIN_VOLTS)
                            Text(
                                text = formatDecimal(volts, 0),
                                style = MaterialTheme.typography.bodySmall,
                                color = voltsColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp)
                                    .offset(y = PLOT_HEIGHT * share.toFloat() - 8.dp),
                            )
                            volts += 30.0
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AXIS_WIDTH, end = AXIS_WIDTH),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "100 %", style = MaterialTheme.typography.bodySmall)
                Text(text = "50 %", style = MaterialTheme.typography.bodySmall)
                Text(text = "0 %", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Ліворуч кВт·год, праворуч вольти. Шкала йде від повної " +
                    "батареї до порожньої, як розрядна крива.",
                style = MaterialTheme.typography.bodySmall,
            )

            CapacityLine("Заявлено в налаштуваннях", curve.totalKwh, gridColor)
            CapacityLine("Зміряно за струмом", curve.powerCapacityKwh, lineColor)
            // Поки замірів немає, крива дорівнює прямій ТТХ — показувати її суму
            // як «зміряно» означало б видати аксіому за вимір.
            CapacityLine(
                "Зміряно за лічильниками",
                curve.counterCapacityKwh.takeIf { curve.samples > 0 },
                counterColor,
            )

            Text(
                text = "Обидві криві починаються з заявленої ємності на ста " +
                    "відсотках і йдуть униз по зміряних нахилах. Куди крива сяде " +
                    "на нулі — стільки насправді не набралося.",
                style = MaterialTheme.typography.bodySmall,
            )

            DistanceChart(curve.distancePoints, lineColor, gridColor)

            if (curve.voltagePoints.isNotEmpty()) {
                val cell = curve.voltagePoints
                Text(
                    text = "Напруга зміряна на ${cell.size} ділянках шкали, від " +
                        "${formatDecimal(cell.last().voltsPerCell, 3)} до " +
                        "${formatDecimal(cell.first().voltsPerCell, 3)} В на комірку. " +
                        "Просадка під струмом прибрана, тож це напруга спокою.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!curve.hasMeasurements) {
                Text(
                    text = "Замірів ще немає, тому кривої на полотні теж немає. Один " +
                        "замір беремо, коли лічильник відданої енергії набирає близько " +
                        "кіловат-години — це приблизно п'ять кілометрів дороги.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Text(text = measuredText(curve), style = MaterialTheme.typography.bodyMedium)

            Text(text = fullText(curve), style = MaterialTheme.typography.bodySmall)

            nowSoc?.let { soc ->
                curve.energyAt(soc)?.let { energy ->
                    Text(
                        text = "Зараз ${formatDecimal(soc, 1)} % — це ${formatDecimal(energy, 1)} кВт·год",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                text = "Міряється різницею пожиттєвих лічильників BMS, тому обрив зв'язку " +
                    "заміру не псує, а повторні проходи тим самим відсотком усереднюються.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onReset) { Text("Забути криву") }
        }
    }
}

/** Один рядок підсумку: скільки кВт·год дала ця крива на всю шкалу. */
@Composable
private fun CapacityLine(title: String, kwh: Double?, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodySmall, color = color)
        Text(
            text = if (kwh == null || kwh <= 0.0) "—" else "${formatDecimal(kwh, 1)} кВт·год",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/**
 * КІЛОМЕТРИ НА ВІДСОТОК ШКАЛИ.
 *
 * Вертикаль перевернута: нуль угорі, [MAX_KM_PER_PERCENT] унизу. Тому «довгі»
 * відсотки провисають, і видно, де саме шкала тягнеться.
 *
 * Точки є лише там, де справді їхали, і доводити тут нема чого: це не властивість
 * батареї, а слід поїздок. Той самий відсоток у місті з кліматом і на трасі
 * коштує різного пробігу — і саме тому цифри поруч мають різнитися.
 */
@Composable
private fun DistanceChart(points: List<DistancePoint>, lineColor: Color, gridColor: Color) {
    Text(text = "Кілометри на відсоток", fontSize = 16.sp)

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(AXIS_WIDTH)
                .height(DISTANCE_PLOT_HEIGHT),
        ) {
            var value = 0.0
            while (value <= MAX_KM_PER_PERCENT) {
                val share = value / MAX_KM_PER_PERCENT
                Text(
                    text = formatDecimal(value, 0),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 4.dp)
                        .offset(y = DISTANCE_PLOT_HEIGHT * share.toFloat() - 8.dp),
                )
                value += 2.0
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(DISTANCE_PLOT_HEIGHT),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                fun x(percent: Double) = (1.0 - percent / 100.0).toFloat() * size.width
                // Перевернуто навмисно: нуль угорі, довгі відсотки провисають униз.
                fun y(km: Double) = (km / MAX_KM_PER_PERCENT).toFloat() * size.height

                for (percent in 0..100 step 20) {
                    drawLine(
                        gridColor,
                        Offset(x(percent.toDouble()), 0f),
                        Offset(x(percent.toDouble()), size.height),
                        1f,
                    )
                }
                var value = 0.0
                while (value <= MAX_KM_PER_PERCENT) {
                    drawLine(gridColor, Offset(0f, y(value)), Offset(size.width, y(value)), 1f)
                    value += 2.0
                }

                // Сусідні точки з'єднуємо лише коли вони й справді сусідні: розрив
                // у замірах не має перетворитися на пряму через пів шкали.
                points.zipWithNext { from, to ->
                    if (to.socPercent - from.socPercent <= MAX_JOIN_PERCENT) {
                        drawLine(
                            color = lineColor,
                            start = Offset(x(from.socPercent), y(from.km)),
                            end = Offset(x(to.socPercent), y(to.km)),
                            strokeWidth = 3f,
                        )
                    }
                }
                points.forEach {
                    drawCircle(lineColor, radius = 2.5f, center = Offset(x(it.socPercent), y(it.km)))
                }
            }
        }

        Box(modifier = Modifier.width(AXIS_WIDTH))
    }

    Text(
        text = if (points.isEmpty()) {
            "Кілометрів ще не набралося: потрібен пробіг із одометра на тому самому " +
                "інтервалі, що й замір енергії."
        } else {
            "Нуль угорі, тож довгі відсотки провисають. Це не властивість батареї, " +
                "а слід поїздок: той самий відсоток у місті з кліматом і на трасі " +
                "коштує різного пробігу."
        },
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Стеля вертикальної осі: округлена вгору до десятка, з запасом над кривою. */
private fun topOf(curve: BatteryCurve): Double {
    if (curve.totalKwh <= 0.0) return DEFAULT_TOP_KWH
    return ceil(curve.totalKwh / 10.0) * 10.0
}

private fun measuredText(curve: BatteryCurve): String {
    val from = curve.measuredFromPercent
    val to = curve.measuredToPercent
    val range = if (from != null && to != null) {
        "Зміряно ${formatDecimal(from, 0)}–${formatDecimal(to, 0)} % шкали"
    } else {
        "Зміряного ще немає"
    }
    return "$range, покрито ${formatDecimal(curve.coveredPercent, 0)} % зі 100. " +
        "Замірів ${curve.samples} за лічильниками, ${curve.powerSamples} за струмом."
}

/**
 * Що показала зарядка з низьких відсотків.
 *
 * Рядок лишився, але вже НЕ як вимір ємності. Він рахується за лічильником
 * прийнятої енергії, а той міряє шкалу рідного пакета: нічна зарядка на
 * 49.6 кВт·год за лічильником станції дала за ним 23.0, тобто 27 на всю шкалу —
 * рівно паспорт стокового Soul EV. Тому число тут — свідчення про сам лічильник,
 * а не про батарею.
 */
private fun fullText(curve: BatteryCurve): String = if (curve.totalMeasured) {
    "Зарядка з низьких відсотків дала ${formatDecimal(curve.totalKwh, 1)} кВт·год за " +
        "лічильником прийнятої енергії. У побудові кривої це число не бере участі: " +
        "той лічильник міряє шкалу рідного пакета, а не кіловат-години."
} else {
    "Верхня точка ${formatDecimal(curve.totalKwh, 1)} кВт·год узята з налаштувань авто. " +
        "Криві йдуть від неї вниз; наскільки вони не дійдуть до нуля — настільки " +
        "заявлене й розходиться зі зміряним."
}
