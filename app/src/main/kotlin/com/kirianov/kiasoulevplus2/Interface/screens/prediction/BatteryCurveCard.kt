// Графік «скільки кВт·год на цьому відсотку». По горизонталі — відсоток шкали,
// по вертикалі — кВт·год, які в батареї лишаються.
//
// ЧОМУ ОКРЕМИЙ ГРАФІК, А НЕ ДРУГА КРИВА НА СУСІДНЬОМУ. Дві величини з різними
// одиницями на одному полотні читаються погано: спільна вертикаль означає, що
// одна з них намальована в чужому масштабі, і форму кривої вже не побачити. Тут
// вертикаль своя, у кіловат-годинах, з власними підписами.
//
// ЩО ТУТ ВИМІРЯНЕ, А ЩО ДОВЕДЕНЕ. Суцільна ділянка — та, де крива справді
// зміряна різницею пожиттєвих лічильників. Пунктир — доведення середнім
// нахилом: ми ще не бували на цих відсотках, і показувати їх так само, як
// зміряні, означало б брехати про те, чого не знаємо.

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.BatteryCurve
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import kotlin.math.ceil

@Composable
fun BatteryCurveCard(curve: BatteryCurve, vehicle: VehicleData, onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Ємність по шкалі", fontSize = 18.sp)

            if (!curve.hasMeasurements) {
                Text(
                    text = "Ще жодного заміру. Один замір беремо, коли лічильник відданої " +
                        "енергії набирає близько кіловат-години — це приблизно п'ять " +
                        "кілометрів дороги.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            val lineColor = MaterialTheme.colorScheme.primary
            val inferredColor = MaterialTheme.colorScheme.outline
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val markerRing = MaterialTheme.colorScheme.surface

            val topKwh = ceil((curve.fullKwh.coerceAtLeast(1.0)) / 10.0) * 10.0
            val nowSoc = vehicle.preciseSocPercent.takeIf { vehicle.hasPreciseSoc }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val leftInset = 30.dp.toPx()
                    val bottomInset = 18.dp.toPx()
                    val edge = 8.dp.toPx()
                    val plotWidth = size.width - leftInset - edge
                    val plotHeight = size.height - bottomInset - edge
                    if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

                    fun x(percent: Double) = leftInset + (percent / 100.0).toFloat() * plotWidth
                    fun y(kwh: Double) = edge + (1.0 - kwh / topKwh).toFloat() * plotHeight

                    // Сітка: кожні 20 % і кожні 10 кВт·год. Рідка навмисно — вона
                    // тут для оцінки на око, а не для зняття значень.
                    for (percent in 0..100 step 20) {
                        val at = x(percent.toDouble())
                        drawLine(gridColor, Offset(at, edge), Offset(at, edge + plotHeight), 1f)
                    }
                    var kwh = 0.0
                    while (kwh <= topKwh) {
                        val at = y(kwh)
                        drawLine(gridColor, Offset(leftInset, at), Offset(leftInset + plotWidth, at), 1f)
                        kwh += 10.0
                    }

                    // Крива по відрізках: суцільна там, де обидва кінці зміряні.
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                    curve.points.zipWithNext { from, to ->
                        val measured = from.measured && to.measured
                        drawLine(
                            color = if (measured) lineColor else inferredColor,
                            start = Offset(x(from.socPercent), y(from.energyKwh)),
                            end = Offset(x(to.socPercent), y(to.energyKwh)),
                            strokeWidth = if (measured) 3f else 2f,
                            pathEffect = if (measured) null else dash,
                        )
                    }

                    // Де авто зараз: точка на кривій, щоб число з екрана мало місце.
                    nowSoc?.let { soc ->
                        curve.energyAt(soc)?.let { energy ->
                            val center = Offset(x(soc), y(energy))
                            drawCircle(markerRing, radius = 7f, center = center)
                            drawCircle(lineColor, radius = 4f, center = center)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "0 %", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "вертикаль — кВт·год, до ${formatDecimal(topKwh, 0)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text = "100 %", style = MaterialTheme.typography.bodySmall)
            }

            Text(text = measuredText(curve), style = MaterialTheme.typography.bodyMedium)

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

private fun measuredText(curve: BatteryCurve): String {
    val from = curve.measuredFromPercent
    val to = curve.measuredToPercent
    val range = if (from != null && to != null) {
        "Зміряно ${formatDecimal(from, 0)}–${formatDecimal(to, 0)} % шкали"
    } else {
        "Зміряного ще немає"
    }
    return "$range, покрито ${formatDecimal(curve.coveredPercent, 0)} % зі 100. " +
        "Замірів ${curve.samples}, повна ємність за кривою ${formatDecimal(curve.fullKwh, 1)} кВт·год."
}
