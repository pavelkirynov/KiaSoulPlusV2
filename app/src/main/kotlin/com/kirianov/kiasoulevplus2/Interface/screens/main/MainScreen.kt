// UI головного екрана: статус підключення та показники ВВБ із GeneralData.state.

package com.kirianov.kiasoulevplus2.Interface.screens.main

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.WindowStats
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import com.kirianov.kiasoulevplus2.tools.format.formatDuration
import com.kirianov.kiasoulevplus2.tools.format.formatMeasurement
import com.kirianov.kiasoulevplus2.tools.format.formatOrDash

@Composable
fun MainScreen(mainViewModel: MainViewModel = viewModel()) {
    val state by mainViewModel.uiState.collectAsState()
    val bms = state.bms
    val calculated = state.calculated

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                    Button(
                        onClick = mainViewModel::onConnectClick,
                        // Під час підключення кнопка вимкнена: повторне натискання
                        // раніше могло запустити другу спробу поверх першої.
                        enabled = state.connection != ConnectionState.Connecting,
                    ) {
                        Text(if (state.isConnected) "Відключити" else "Підключити")
                    }

                    Text(
                        text = when (state.connection) {
                            ConnectionState.Connected -> "З'єднано"
                            ConnectionState.Connecting -> "Підключення..."
                            ConnectionState.Disconnected -> "Відключено"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when (state.connection) {
                            ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                            ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
                            ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
                        },
                    )
                }

                Text(
                    text = "Статус: ${state.debugInfo}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Показники батареї", fontSize = 18.sp)

                MetricRow(
                    label = "Заряд (SOC)",
                    value = if (bms.hasData) "${formatDecimal(bms.displaySoc, 1)}%" else NO_VALUE,
                )
                MetricRow(
                    label = "Напруга ВВБ",
                    value = if (bms.hasData) formatMeasurement(bms.batteryVoltage, 1, "В") else NO_VALUE,
                )
                MetricRow(
                    label = "Струм ВВБ",
                    value = if (bms.hasData) formatMeasurement(bms.batteryCurrent, 1, "А") else NO_VALUE,
                )
                MetricRow(
                    label = "Потужність",
                    value = if (bms.hasData) formatMeasurement(calculated.powerKw, 2, "кВт") else NO_VALUE,
                )
                MetricRow(
                    label = "Температура ВВБ",
                    value = if (bms.hasData) formatMeasurement(bms.batteryTempC, 1, "°C") else NO_VALUE,
                )
            }
        }

        if (bms.hasEnergyCounters) {
            ConsumptionCard(
                stats = calculated.window,
                selected = state.consumptionWindow,
                hasOdometer = state.vehicle.hasOdometer,
                onWindowSelected = mainViewModel::onWindowSelected,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "Лічильники батареї за весь час", fontSize = 18.sp)

                    MetricRow(
                        "Віддано",
                        formatMeasurement(bms.cumulativeEnergyDischargedKwh, 1, "кВт·год"),
                    )
                    MetricRow(
                        "Прийнято",
                        formatMeasurement(bms.cumulativeEnergyChargedKwh, 1, "кВт·год"),
                    )
                    MetricRow(
                        "Пробіг",
                        if (state.vehicle.hasOdometer) {
                            formatMeasurement(state.vehicle.odometerKm, 0, "км")
                        } else {
                            "--"
                        },
                    )
                }
            }
        }

        if (calculated.maxCellVoltage > 0.0) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "Комірки", fontSize = 18.sp)
                    MetricRow("Мінімальна", formatMeasurement(calculated.minCellVoltage, 3, "В"))
                    MetricRow("Максимальна", formatMeasurement(calculated.maxCellVoltage, 3, "В"))
                    MetricRow("Розбаланс ΔV", formatMeasurement(calculated.cellDeltaVolts, 3, "В"))
                }
            }
        }
    }
}

@Composable
private fun ConsumptionCard(
    stats: WindowStats,
    selected: ConsumptionWindow,
    hasOdometer: Boolean,
    onWindowSelected: (ConsumptionWindow) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Витрата енергії", fontSize = 18.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ConsumptionWindow.entries.forEach { window ->
                    FilterChip(
                        selected = window == selected,
                        onClick = { onWindowSelected(window) },
                        label = { Text(windowLabel(window), fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (!stats.hasData) {
                Text(
                    text = "Збираю дані...",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            MetricRow("Витрата", formatOrDash(stats.kwhPer100Km, 1, "кВт·год/100 км"))
            MetricRow("Пробіг", formatMeasurement(stats.distanceKm, 1, "км"))
            MetricRow("Час", formatDuration(stats.durationMs))
            MetricRow("Сер. швидкість", formatOrDash(stats.averageSpeedKmh, 0, "км/год"))
            MetricRow("Сер. потужність", formatOrDash(stats.averagePowerKw, 1, "кВт"))
            MetricRow("Витрачено", formatMeasurement(stats.consumedKwh, 2, "кВт·год"))
            MetricRow("Повернуто", formatMeasurement(stats.recoveredKwh, 2, "кВт·год"))

            if (!hasOdometer) {
                Text(
                    text = "Пробіг із щитка не зчитано — витрата на 100 км недоступна",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!stats.isComplete && selected != ConsumptionWindow.Trip) {
                Text(
                    text = "Діапазон ще не набрався: показано за наявний пробіг",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun windowLabel(window: ConsumptionWindow) = when (window) {
    ConsumptionWindow.Trip -> "Поїздка"
    ConsumptionWindow.Last1Km -> "1 км"
    ConsumptionWindow.Last5Km -> "5 км"
    ConsumptionWindow.Last20Km -> "20 км"
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

private const val NO_VALUE = "--"
