// Графік «кВт·год проти відсотка»: по горизонталі відсоток шкали, по вертикалі —
// скільки корисної енергії лежить між справжнім дном і цією точкою.
//
// НАВІЩО ГРАФІК, ЯКЩО ЄМНІСТЬ ВЖЕ Є ЧИСЛОМ. Одна одиниця шкали важить різну
// кількість енергії в різних місцях: біля дна й біля стелі відсоток коштує менше,
// ніж у середині. Через це останні відсотки «тануть» швидше за перші — і саме це
// одним числом ємності не передати, а на кривій видно одразу.
//
// Пряма для порівняння малюється навмисно: без неї прогин не читається, бо крива
// сама по собі виглядає майже прямою.

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
import com.kirianov.kiasoulevplus2.Data.socAtEnergy
import com.kirianov.kiasoulevplus2.Data.MlModelInfo
import com.kirianov.kiasoulevplus2.Data.MlPrediction
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal

@Composable
fun EnergyCurveCard(model: MlModelInfo, prediction: MlPrediction?) {
    val curve = model.energyCurve
    if (curve.size < 2) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Скільки кВт·год за відсотком", fontSize = 18.sp)

            val curveColor = MaterialTheme.colorScheme.primary
            val referenceColor = MaterialTheme.colorScheme.onSurfaceVariant
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val markerRing = MaterialTheme.colorScheme.surface

            val fromSoc = curve.first().socPercent
            val toSoc = curve.last().socPercent
            val fullKwh = curve.last().energyKwh
            if (fullKwh <= 0.0 || toSoc <= fromSoc) return@Column

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    fun xOf(soc: Double) =
                        ((soc - fromSoc) / (toSoc - fromSoc)).toFloat() * size.width

                    fun yOf(kwh: Double) =
                        size.height - (kwh / fullKwh).toFloat() * size.height

                    // Сітка на кожні чверть висоти: без неї крутизну не оцінити.
                    (1..3).forEach { line ->
                        val y = size.height * line / 4f
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                        )
                    }

                    // Пряма: якби кожен відсоток шкали важив однаково.
                    drawLine(
                        color = referenceColor,
                        start = Offset(xOf(fromSoc), yOf(0.0)),
                        end = Offset(xOf(toSoc), yOf(fullKwh)),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    )

                    val path = Path().apply {
                        curve.forEachIndexed { index, point ->
                            val x = xOf(point.socPercent)
                            val y = yOf(point.energyKwh)
                            if (index == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    drawPath(path = path, color = curveColor, style = Stroke(width = 5f))

                    // Де стоїмо зараз: точку видно на кривій, а не лише в числах.
                    val soc = prediction?.let { it.realPercent to it.usableEnergyRemainingKwh }
                    soc?.let { (_, remaining) ->
                        val socNow = curve.socAtEnergy(remaining) ?: return@let
                        val center = Offset(xOf(socNow), yOf(remaining))
                        drawCircle(color = markerRing, radius = 11f, center = center)
                        drawCircle(color = curveColor, radius = 7f, center = center)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatDecimal(fromSoc, 0)} % — дно",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "${formatDecimal(fullKwh, 1)} кВт·год",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "стеля — ${formatDecimal(toSoc, 0)} %",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = "Пунктир — якби кожен відсоток важив однаково. Крива вище або " +
                    "нижче за нього означає, що відсотки шкали в цьому місці " +
                    "коштують більше або менше енергії.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
