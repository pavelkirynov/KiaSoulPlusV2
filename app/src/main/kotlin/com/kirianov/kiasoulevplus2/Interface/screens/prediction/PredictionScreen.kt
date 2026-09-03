// UI екрана прогнозу: вивчений запас ходу, реальний відсоток і те, наскільки
// моделі вже можна вірити. Показники беруться з GeneralData.state, запити пишуться
// туди ж — про блок прогнозу екран нічого не знає.

package com.kirianov.kiasoulevplus2.Interface.screens.prediction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.TextButton
import com.kirianov.kiasoulevplus2.Data.RangeAccuracy
import com.kirianov.kiasoulevplus2.Data.MlConfidence
import com.kirianov.kiasoulevplus2.Data.PredictionBasis
import com.kirianov.kiasoulevplus2.Data.MlModelInfo
import com.kirianov.kiasoulevplus2.Data.MlPrediction
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import com.kirianov.kiasoulevplus2.tools.format.formatDuration
import com.kirianov.kiasoulevplus2.tools.format.formatMeasurement
import com.kirianov.kiasoulevplus2.tools.format.formatOrDash

@Composable
fun PredictionScreen(predictionViewModel: PredictionViewModel = viewModel()) {
    val state by predictionViewModel.uiState.collectAsState()
    val ml = state.ml

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RangeCard(ml.prediction, ml.model.confidence, state.vehicle)

        RangeAccuracyCard(state.rangeAccuracy, predictionViewModel::onResetAccuracy)

        ml.prediction?.let { ScenarioCard(it) }



        BatteryCard(ml.model, ml.prediction)

        BatteryCurveCard(state.curve, state.vehicle, predictionViewModel::onResetCurve)

        ScaleCurveCard(ml.model, ml.prediction, state.vehicle)

        LearningCard(ml.model, ml.recentSegments.size)

        ActionsCard(
            retraining = ml.retraining,
            hasHistory = ml.model.segments > 0,
            onRetrain = predictionViewModel::onRetrainClick,
            onReset = predictionViewModel::onResetClick,
        )
    }
}

/**
 * Головна картка. Поруч із вивченим запасом навмисно стоїть той, що показує саме
 * авто: спершу це орієнтир для довіри, а далі — найцікавіше на екрані, бо саме
 * в розходженні й видно, чи вивчила модель щось, чого не знає бортовий комп'ютер.
 */
/**
 * На чому побудоване велике число зверху.
 *
 * Без цього рядка воно читається як «стільки проїду», а означає інше: «стільки
 * проїду, ЯКЩО далі їхати так само, як останні години, і за такого ж климату».
 * Скільки буде на інших швидкостях — у картці сценаріїв нижче.
 */
/**
 * Чи стримав прогноз обіцянку — одразу під самим прогнозом, щоб обіцянка та її
 * точність були в одному погляді.
 *
 * Порівнюється не відстань із відстанню, а обіцяне з пройденим: якби прогноз був
 * точний, обіцяний запас падав би рівно на стільько, скільки проїхано.
 */
@Composable
private fun RangeAccuracyCard(accuracy: RangeAccuracy, onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Чи стримав прогноз обіцянку", fontSize = 18.sp)
                TextButton(onClick = onReset) { Text("Обнулити") }
            }

            if (!accuracy.started) {
                Text(
                    text = "Відлік почнеться, коли з'являться і прогноз, і пробіг. " +
                        "Обнуляється сам після зарядки — після неї початкова обіцянка " +
                        "означає вже інше.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            MetricRow("Обіцяв на початку", formatMeasurement(accuracy.startRangeKm, 0, "км"))
            MetricRow("Обіцяє зараз", formatMeasurement(accuracy.currentRangeKm, 0, "км"))
            MetricRow("Запас упав на", formatMeasurement(accuracy.predictedDropKm, 1, "км"))
            MetricRow("Проїхали", formatMeasurement(accuracy.drivenKm, 1, "км"))

            val error = accuracy.errorPercent
            if (error == null) {
                Text(
                    text = "Проїхали ще замало — на перших кілометрах похибка одометра " +
                        "важить більше за якість прогнозу.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            MetricRow("Похибка", "${formatDecimal(error, 1)} %")

            Text(
                text = when {
                    error > 5.0 ->
                        "Запас падає швидше за дорогу: прогноз оптимістичний, " +
                            "обіцяного не проїдете."
                    error < -5.0 ->
                        "Запас падає повільніше за дорогу: прогноз перестрахувався, " +
                            "проїдете більше за обіцяне."
                    else -> "Запас падає рівно так, як проїжджається. Прогнозу можна вірити."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (error > 5.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BasisNote(basis: PredictionBasis) {
    val text = if (basis.hasHistory) {
        "За вашим стилем їзди: ${formatDuration(basis.movingMs)} руху, " +
            "у середньому ${formatDecimal(basis.meanSpeedKmh, 0)} км/год. " +
            climateSentence(basis) +
            " Поїдете інакше — і запас буде інший: дивіться сценарії нижче."
    } else {
        "Поїздок ще не набралося, тому взято рівний хід 50 км/год. " +
            "Число уточниться з першими кілометрами."
    }

    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

/**
 * Клімат береться живий із шини, а не всереднений за години: пічку могли щойно
 * ввімкнути, і прогноз мусить подорожчати одразу, а не за чверть години.
 */
private fun climateSentence(basis: PredictionBasis): String {
    val share = basis.climateShare ?: return "Климат авто не повідомляє."
    val percent = formatDecimal(share * 100.0, 0)
    return if (basis.climateLive) {
        "Климат враховано поточний, зараз це $percent % витрати."
    } else {
        "Климат — середній за цими поїздками, $percent % витрати."
    }
}

@Composable
private fun RangeCard(
    prediction: MlPrediction?,
    confidence: MlConfidence,
    vehicle: VehicleData,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Реальний запас ходу", fontSize = 18.sp)

            if (prediction == null) {
                Text(
                    text = "Немає даних про заряд. Під'єднайтеся до адаптера — " +
                        "прогноз з'явиться одразу, а точнішати буде з кожною поїздкою.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            Text(
                text = "${formatDecimal(prediction.rangeKm, 0)} км",
                fontSize = 40.sp,
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                text = "від ${formatDecimal(prediction.rangeFromKm, 0)} " +
                    "до ${formatDecimal(prediction.rangeToKm, 0)} км",
                style = MaterialTheme.typography.bodyMedium,
            )

            BasisNote(prediction.basis)

            MetricRow("Реальний заряд", formatMeasurement(prediction.realPercent, 0, "%"))
            MetricRow("Корисної енергії", formatMeasurement(prediction.usableEnergyRemainingKwh, 1, "кВт·год"))
            MetricRow("Витрата в прогнозі", formatMeasurement(prediction.whPerKm, 0, "Вт·год/км"))

            if (vehicle.hasRange) {
                MetricRow("Показує авто", "${vehicle.rangeKm} км")
            }
            if (vehicle.hasDisplaySoc) {
                MetricRow("Показує панель", formatMeasurement(vehicle.displaySocPercent, 0, "%"))
            }

            Text(
                text = confidenceText(confidence, prediction.measuredBand),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Той самий залишок, якби всю дорогу їхати з однією швидкістю. */
@Composable
private fun ScenarioCard(prediction: MlPrediction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "А якщо їхати рівно", fontSize = 18.sp)

            prediction.scenarios.forEach { scenario ->
                MetricRow(
                    label = "${formatDecimal(scenario.speedKmh, 0)} км/год",
                    value = "${formatDecimal(scenario.rangeKm, 0)} км  ·  " +
                        formatMeasurement(scenario.whPerKm, 0, "Вт·год/км"),
                )
            }
        }
    }
}

/** Що модель вивчила про саму батарею. */
@Composable
private fun BatteryCard(model: MlModelInfo, prediction: MlPrediction?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Батарея", fontSize = 18.sp)

            // «≈» означає «це ще припущення, а не вимір». Ряди вузькі, тож позначка
            // мусить бути короткою; що вона значить, сказано текстом нижче.
            val assumed = if (model.capacityMeasured) "" else "≈ "
            val assumedScale = if (model.scaleMeasured) "" else "≈ "

            MetricRow("Робоча ємність", assumed + formatOrDash(model.usableCapacityKwh, 1, "кВт·год"))
            MetricRow("Середня ємність", formatOrDash(model.averageCapacityKwh, 1, "кВт·год"))
            MetricRow("Пройдено шкали", formatMeasurement(model.measuredScalePercent, 0, "%"))
            MetricRow("Від очікуваної", assumed + formatOrDash(model.capacityVersusNominalPercent, 0, "%"))
            MetricRow("Більше за рідний пакет", assumed + formatOrDash(model.timesLargerThanOriginal, 2, "×"))
            MetricRow("Дно шкали", assumedScale + formatOrDash(model.floorSocPercent, 1, "% SOC"))
            MetricRow("Стеля шкали", assumedScale + formatOrDash(model.ceilingSocPercent, 1, "% SOC"))
            if (prediction != null) {
                MetricRow("Реальний заряд", formatMeasurement(prediction.realPercent, 1, "%"))
            }

            if (!model.capacityMeasured) {
                Text(
                    text = capacityProgressText(model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Text(
                text = "Батарея перепакована іншими літій-іонними комірками — близько 51 " +
                    "кВт·год проти рідних 27, — а BMS рахує відсоток за паспортом рідних. " +
                    "Звідси й розбіжності з панеллю. Ємність тут не взята з даташита, а " +
                    "виміряна: із пар «скільки з'їхав SOC / скільки на це пішло енергії». " +
                    "Знак «≈» біля числа означає, що його ще не виміряно — це те, з чого " +
                    "модель починає: зарядка від нуля до ста показала 55 кВт·год із " +
                    "розетки, тобто близько 51 у батареї.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = "«Робоча» — інтеграл вивченої кривої по дозволеному BMS вікну. " +
                    "«Середня» — та сама енергія, поділена на пройдені відсотки, без жодної " +
                    "моделі: незалежна перевірка, і коли два числа сходяться, кривій можна " +
                    "вірити. Розходитися вони можуть закономірно: середня рахує лише ті " +
                    "ділянки шкали, якими ви їздили, тож якщо крива нерівна, а заряд ви " +
                    "тримаєте між 40 і 70 %, вона зміститься в бік середини шкали.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!model.scaleMeasured) {
                Text(
                    text = "Дно і стеля шкали поки не виміряні: 4.0 і 99.2 % — це початкове " +
                        "припущення, а не властивість саме вашої батареї. Щоб їх виміряти, " +
                        "потрібні пари «панельний відсоток / точний SOC» у різних кінцях " +
                        "шкали: поїздіть із застосунком і на високому, і на низькому заряді, " +
                        "і числа стануть вашими.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = "Важливо про відсоток: він майже збігається з панельним і розходиться " +
                    "лише на одиниці — бо і той, і той лінійно розтягують ту саму шкалу. " +
                    "У рази панель помиляється не у відсотках, а в кіловат-годинах і " +
                    "кілометрах, і саме їх це виправляє.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Чесна відповідь на «чи вже вивчила». Готовність рахується не за кількістю
 * поїздок, а за тим, скільки в кожному коефіцієнті даних, а не фізики: сама лише
 * траса нічого не скаже про постійний відбір, а літо — про роботу пічки.
 */
@Composable
private fun LearningCard(model: MlModelInfo, recentSegments: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Навчання", fontSize = 18.sp)

            MetricRow("Відрізків вивчено", model.segments.toString())
            MetricRow("Пробіг навчання", formatMeasurement(model.learnedKm, 0, "км"))
            MetricRow("Перевірок інтервалу", model.blocks.toString())
            MetricRow("Помилка прогнозу", formatOrDash(model.maeWhPerKm, 0, "Вт·год/км"))
            MetricRow("Постійний відбір", formatOrDash(model.auxPowerKw, 2, "кВт"))
            MetricRow("Горбистість доріг", formatOrDash(model.terrainRoughness, 0, "м/√км"))

            // Без цих двох рядків «модель не вчиться» виглядає загадкою: вимогу
            // «3 км і п'ять хвилин без розриву» на нестабільному Bluetooth
            // виконати важко, і саме тут видно, скільком відрізкам це не вдалося.
            MetricRow("Відрізків не дожило", model.abortedSegments.toString())
            if (model.lastAbortReason.isNotEmpty()) {
                MetricRow("Остання причина", model.lastAbortReason)
            }

            if (model.readiness.isNotEmpty()) {
                Text(
                    text = "Що вже вивчено з даних, а не взято з фізики:",
                    style = MaterialTheme.typography.bodySmall,
                )
                model.readiness.forEach { (name, value) ->
                    ReadinessRow(name, value)
                }
            }

            if (recentSegments == 0) {
                Text(
                    text = "Відрізок закривається приблизно раз на п'ять хвилин руху. " +
                        "Щоб модель розділила відбір, кочення й опір повітря, потрібні " +
                        "різні швидкості — саме різноманітність, а не кілометраж.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ReadinessRow(name: String, value: Double) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatMeasurement(value * 100.0, 0, "%"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { value.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActionsCard(
    retraining: Boolean,
    hasHistory: Boolean,
    onRetrain: () -> Unit,
    onReset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Журнал навчання", fontSize = 18.sp)

            Text(
                text = "Кожен відрізок лягає у файл. Саме тому модель можна зібрати " +
                    "наново — без жодної нової поїздки.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onRetrain,
                    enabled = !retraining && hasHistory,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (retraining) "Перебираю..." else "Перенавчити")
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = !retraining,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Забути все")
                }
            }

            Text(
                text = "«Забути все» стирає і модель, і журнал. Це потрібно після заміни " +
                    "батареї або якщо застосунок переїхав на інше авто.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun confidenceText(confidence: MlConfidence, measuredBand: Boolean): String {
    val base = when (confidence) {
        MlConfidence.None -> "Модель ще нічого не бачила: показано фізичне наближення за розмірами авто"
        MlConfidence.Learning -> "Модель учиться: числу вже можна вірити, але межі широкі"
        MlConfidence.Fair -> "Модель навчена: тримайтеся нижньої межі, і не помилитеся"
        MlConfidence.Good -> "Модель стабільна: інтервал вузький, промахи малі"
    }
    val band = if (measuredBand) {
        "межі виміряні за справжніми промахами на відрізках по 10 км"
    } else {
        "межі поки що припущені — виміряних промахів іще замало"
    }
    return "$base; $band."
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 16.sp)
        Text(text = value, fontSize = 16.sp, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Чому чисел ємності ще немає і скільки лишилося.
 *
 * Прочерки без пояснення читаються як «зламано». Насправді для першого виміру
 * потрібен один безперервний прохід шкали на кілька відсотків, і корисно бачити,
 * наскільки він набраний.
 */
private fun capacityProgressText(model: MlModelInfo): String {
    val target = model.sessionTargetPercent
    if (target <= 0.0) return "Ємність ще не виміряно: числа поруч — початкове припущення."

    val span = model.sessionSpanPercent
    return "Ємність ще не виміряно, тому числа поруч — початкове припущення. " +
        "Для першого виміру потрібно ${formatDecimal(target, 0)} % шкали за одне " +
        "спостереження; набрано ${formatDecimal(span, 1)} %. " +
        "Обрив зв'язку спостереження більше не скидає, але стрибок SOC за час " +
        "обриву — скидає: перевірити його нічим."
}
