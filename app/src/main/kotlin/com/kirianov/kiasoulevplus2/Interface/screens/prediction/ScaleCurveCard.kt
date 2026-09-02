// Графік «панель проти реальності»: по горизонталі відсоток, який показує авто,
// по вертикалі — справжній відсоток енергії, що лишилася.
//
// Чому графік узагалі потрібен, хоча числа вже є на екрані. Розходження тут не
// стале: воно змінюється вздовж шкали, а таке словами й одним числом не передати.
// Крива показує одразу і **де** панель поспішає, і **наскільки**.
//
// Кінці кривої нерухомі за побудовою: нуль на панелі — це нуль реальних, сто — це
// сто. Так і задумано, бо шкала на екрані свідомо збігається з вікном, яке дозволяє
// BMS. Уся інформація — у прогині між кінцями, тому він і залитий: сама лінія на
// тлі діагоналі майже не читалася б, розходження тут — одиниці відсотків.

package com.kirianov.kiasoulevplus2.Interface.screens.prediction

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.MlModelInfo
import com.kirianov.kiasoulevplus2.Data.MlPrediction
import com.kirianov.kiasoulevplus2.Data.ScalePoint
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import kotlin.math.abs

@Composable
fun ScaleCurveCard(model: MlModelInfo, prediction: MlPrediction?, vehicle: VehicleData) {
    val curve = model.scaleCurve
    if (curve.size < 2) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Панель проти реальності", fontSize = 18.sp)

            // Крива енергії малюється тим самим полотном, але власним кольором:
            // дві різні величини на одному графіку інакше не відрізнити.
            val energyCurve = model.energyCurve
            val energyColor = MaterialTheme.colorScheme.tertiary
            val fullKwh = energyCurve.lastOrNull()?.energyKwh ?: 0.0

            val curveColor = MaterialTheme.colorScheme.primary
            val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val markerRing = MaterialTheme.colorScheme.surface

            // Поточне положення: те, що показує панель, проти того, що порахували ми.
            val nowDial = if (vehicle.hasDisplaySoc) vehicle.displaySocPercent else null
            val nowReal = prediction?.realPercent

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val leftInset = 26.dp.toPx()
                    val bottomInset = 18.dp.toPx()
                    val edge = 8.dp.toPx()
                    val plotWidth = size.width - leftInset - edge
                    val plotHeight = size.height - bottomInset - edge
                    if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

                    fun x(percent: Double) = leftInset + (percent / 100.0 * plotWidth).toFloat()
                    fun y(percent: Double) = edge + plotHeight - (percent / 100.0 * plotHeight).toFloat()

                    // Сітка навмисно бліда: вона для орієнтації, а не для читання.
                    listOf(0.0, 25.0, 50.0, 75.0, 100.0).forEach { tick ->
                        drawLine(gridColor, Offset(x(tick), y(0.0)), Offset(x(tick), y(100.0)), 1.dp.toPx())
                        drawLine(gridColor, Offset(x(0.0), y(tick)), Offset(x(100.0), y(tick)), 1.dp.toPx())
                    }

                    // Діагональ — це «панель не бреше». Пунктир, щоб її не сплутали
                    // з даними: вона не вимір, а орієнтир.
                    drawLine(
                        color = referenceColor,
                        start = Offset(x(0.0), y(0.0)),
                        end = Offset(x(100.0), y(100.0)),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 6.dp.toPx()),
                        ),
                    )

                    // Заливка між кривою і діагоналлю: саме вона робить видимим те,
                    // що на око губиться — розходження в одиниці відсотків.
                    val area = Path().apply {
                        moveTo(x(curve.first().dialPercent), y(curve.first().realPercent))
                        curve.drop(1).forEach { lineTo(x(it.dialPercent), y(it.realPercent)) }
                        curve.reversed().forEach { lineTo(x(it.dialPercent), y(it.dialPercent)) }
                        close()
                    }
                    drawPath(area, curveColor.copy(alpha = 0.18f))

                    // Виміряне малюється суцільним, доведене з відомого — пунктиром.
                    // Інакше здогад виглядає так само переконливо, як вимір, і по
                    // графіку не зрозуміти, де кінчаються дані й починається модель.
                    curve.zipWithNext().forEach { (a, b) ->
                        val measured = a.measured && b.measured
                        drawLine(
                            color = if (measured) curveColor else curveColor.copy(alpha = 0.5f),
                            start = Offset(x(a.dialPercent), y(a.realPercent)),
                            end = Offset(x(b.dialPercent), y(b.realPercent)),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = if (measured) {
                                null
                            } else {
                                PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                            },
                        )
                    }

                    // Друга крива: скільки кВт·год стоїть за відсотком шкали. Своя
                    // вертикальна шкала — від нуля до повної ємності, тому обидві
                    // криві вміщаються в одне полотно без стискання однієї з них.
                    if (energyCurve.size >= 2 && fullKwh > 0.0) {
                        energyCurve.zipWithNext().forEach { (a, b) ->
                            val measured = a.measured && b.measured
                            drawLine(
                                color = if (measured) energyColor else energyColor.copy(alpha = 0.5f),
                                start = Offset(x(a.socPercent), y(a.energyKwh / fullKwh * 100.0)),
                                end = Offset(x(b.socPercent), y(b.energyKwh / fullKwh * 100.0)),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = if (measured) {
                                    null
                                } else {
                                    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                                },
                            )
                        }
                    }

                    if (nowDial != null && nowReal != null) {
                        val point = Offset(x(nowDial), y(nowReal))
                        drawCircle(markerRing, radius = 6.dp.toPx(), center = point)
                        drawCircle(curveColor, radius = 4.dp.toPx(), center = point)
                    }
                }

                AxisLabel("100", Modifier.align(Alignment.TopStart))
                AxisLabel("50", Modifier.align(Alignment.CenterStart))
                AxisLabel("0", Modifier.align(Alignment.BottomStart))
                AxisLabel("50", Modifier.align(Alignment.BottomCenter))
                AxisLabel("100", Modifier.align(Alignment.BottomEnd))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = "↑ реально, %", style = MaterialTheme.typography.bodySmall)
                Text(text = "показує панель, % →", style = MaterialTheme.typography.bodySmall)
            }

            if (energyCurve.size >= 2 && fullKwh > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "— кВт·год, від 0 до ${formatDecimal(fullKwh, 1)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = energyColor,
                    )
                    Text(
                        text = "права шкала ↑",
                        style = MaterialTheme.typography.bodySmall,
                        color = energyColor,
                    )
                }

                Text(
                    text = "Друга крива — скільки кВт·год лежить між дном і кожною " +
                        "точкою шкали. Її прогин показує, де відсотки «тануть» швидше: " +
                        "біля дна й біля стелі відсоток коштує менше енергії, ніж у середині.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(text = gapText(curve), style = MaterialTheme.typography.bodySmall)

            Text(
                text = measuredText(model),
                style = MaterialTheme.typography.bodySmall,
            )

            if (nowDial != null && nowReal != null) {
                Text(
                    text = "Крапка — зараз: панель показує ${formatDecimal(nowDial, 0)} %, " +
                        "реально ${formatDecimal(nowReal, 0)} %.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = "Пунктир — «панель не бреше». Крива — те, що виміряно; залите " +
                    "між ними і є розходження. Кінці зійдуться завжди: нуль і сто на " +
                    "панелі — це і є краї вікна, яке дозволяє BMS. Тому у відсотках " +
                    "різниця невелика, а в кіловат-годинах і кілометрах — у рази.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!model.scaleMeasured) {
                Text(
                    text = "Поки це початкове припущення, а не вимір: пар «панель / точний " +
                        "SOC» ще не набралося. Крива стане вашою після кількох поїздок у " +
                        "різних кінцях шкали.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Найбільше розходження і місце, де воно трапляється. Число тут потрібне саме тому,
 * що на графіку прогин навмисно малий: без підпису його легко недооцінити.
 */
private fun gapText(curve: List<ScalePoint>): String {
    val worst = curve.maxByOrNull { abs(it.realPercent - it.dialPercent) } ?: return ""
    val gap = worst.realPercent - worst.dialPercent
    if (abs(gap) < 0.5) {
        return "Розходження поки менше за пів відсотка: за виміряним крива шкали майже пряма."
    }
    val direction = if (gap > 0.0) "занижує" else "завищує"
    return "Найбільше розходження: ${formatDecimal(abs(gap), 1)} % на " +
        "${formatDecimal(worst.dialPercent, 0)} % панелі — там вона $direction заряд."
}

@Composable
private fun AxisLabel(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Яку частину шкали справді пройшли через вимірювання.
 *
 * Пунктир на графіку без цього рядка виглядав би дефектом малювання, а він означає
 * рівно одне: тут ще не їхали, і крива в цьому місці доведена з відомого.
 */
private fun measuredText(model: MlModelInfo): String {
    val from = model.measuredFromPercent
    val to = model.measuredToPercent
    if (from == null || to == null) {
        return "Суцільна лінія — виміряне, пунктир — доведене з відомого. " +
            "Поки виміряного немає: крива вся доведена."
    }
    return "Виміряно від ${formatDecimal(from, 0)} до ${formatDecimal(to, 0)} % шкали — " +
        "це суцільна лінія. Пунктир далі доведений з того, що вже відомо."
}
