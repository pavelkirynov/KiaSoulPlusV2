// Графік «скільки кВт·год на цьому відсотку». По горизонталі — відсоток шкали,
// по вертикалі — кВт·год, які в батареї лишаються.
//
// ЧОМУ ОКРЕМИЙ ГРАФІК, А НЕ ДРУГА КРИВА НА СУСІДНЬОМУ. Дві величини з різними
// одиницями на одному полотні читаються погано: спільна вертикаль означає, що одна
// з них намальована в чужому масштабі, і форму кривої вже не побачити. Тут
// вертикаль своя, у кіловат-годинах, і з цифрами біля осі.
//
// ОСІ Й СІТКА МАЛЮЮТЬСЯ ЗАВЖДИ, навіть коли замірів ще нуль. Порожнє полотно з
// підписаними осями показує, що графік є і чого він чекає; картка, яка до першого
// заміру складається в абзац тексту, виглядає як відсутня функція.
//
// ПІДПИСИ ОСЕЙ — ЗВИЧАЙНІ Text, а не малювання по полотну, і розкладені за
// відомою висотою полотна. Тому висота задана числом, а не пропорцією: інакше
// цифру ніяк не поставити рівно проти її лінії сітки.
//
// ЩО ТУТ ВИМІРЯНЕ, А ЩО ДОВЕДЕНЕ. Суцільна ділянка — та, де крива справді зміряна
// різницею пожиттєвих лічильників. Пунктир — доведення середнім нахилом: ми ще не
// бували на цих відсотках, і показувати їх так само, як зміряні, означало б
// брехати про те, чого не знаємо.

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
import com.kirianov.kiasoulevplus2.Data.BatteryCurve
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import kotlin.math.ceil

/** Висота полотна. Число, а не пропорція: за ним розкладаються підписи осі. */
private val PLOT_HEIGHT = 200.dp

/** Ширина стовпчика цифр біля вертикальної осі. */
private val AXIS_WIDTH = 34.dp

/**
 * Скільки кВт·год показувати на осі, поки не зміряно нічого.
 *
 * Шістдесят: перепакована батарея цього авто — близько 51 кВт·год, і вісь має
 * бути з запасом над нею, щоб перша ж крива не втикалася в стелю.
 */
private const val DEFAULT_TOP_KWH = 60.0

@Composable
fun BatteryCurveCard(curve: BatteryCurve, vehicle: VehicleData, onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Ємність по шкалі", fontSize = 18.sp)

            val lineColor = MaterialTheme.colorScheme.primary
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
                        fun x(percent: Double) = (percent / 100.0).toFloat() * size.width
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

                        // Крива по відрізках: суцільна там, де обидва кінці зміряні.
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        curve.points.zipWithNext { from, to ->
                            val measured = from.measured && to.measured
                            drawLine(
                                color = if (measured) lineColor else inferredColor,
                                start = Offset(x(from.socPercent), y(from.energyKwh)),
                                end = Offset(x(to.socPercent), y(to.energyKwh)),
                                strokeWidth = if (measured) 4f else 2f,
                                pathEffect = if (measured) null else dash,
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
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AXIS_WIDTH),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "0 %", style = MaterialTheme.typography.bodySmall)
                Text(text = "50 %", style = MaterialTheme.typography.bodySmall)
                Text(text = "100 %", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                text = "Вертикаль — кВт·год, горизонталь — відсоток шкали.",
                style = MaterialTheme.typography.bodySmall,
            )

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

/** Стеля вертикальної осі: округлена вгору до десятка, з запасом над кривою. */
private fun topOf(curve: BatteryCurve): Double {
    if (curve.fullKwh <= 0.0) return DEFAULT_TOP_KWH
    return ceil(curve.fullKwh / 10.0) * 10.0
}

private fun measuredText(curve: BatteryCurve): String {
    val from = curve.measuredFromPercent
    val to = curve.measuredToPercent
    val range = if (from != null && to != null) {
        "Зміряно ${formatDecimal(from, 0)}–${formatDecimal(to, 0)} % шкали"
    } else {
        "Зміряного ще немає"
    }
    return "$range, покрито ${formatDecimal(curve.coveredPercent, 0)} % зі 100. Замірів ${curve.samples}."
}

/**
 * Повна ємність — це ДОВЕДЕННЯ, і поки покрито кілька відсотків шкали, сказати це
 * треба прямо. Виміряний нахил розтягується на всю шкалу, а вона може бути
 * нерівною: відсоток угорі шкали і відсоток посередині коштують по-різному.
 */
private fun fullText(curve: BatteryCurve): String =
    "Якщо решта шкали така сама, як зміряна ділянка, повна ємність — " +
        "${formatDecimal(curve.fullKwh, 1)} кВт·год. Це доведення, а не вимір: " +
        "поки зміряно ${formatDecimal(curve.coveredPercent, 0)} % шкали."

