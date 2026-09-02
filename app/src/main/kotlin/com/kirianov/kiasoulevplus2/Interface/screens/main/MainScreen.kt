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
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.Data.WindowStats
import com.kirianov.kiasoulevplus2.tools.format.formatAgo
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

                    // Ампер-години поруч навмисно: їх відношення до кВт·год дає
                    // середню напругу пакета, і саме цим звіряється, що прочитані
                    // ті байти. Раніше як кВт·год показувалися саме ці числа.
                    MetricRow(
                        "Віддано, заряд",
                        formatMeasurement(bms.cumulativeDischargedAh, 1, "А·год"),
                    )
                    MetricRow(
                        "Прийнято, заряд",
                        formatMeasurement(bms.cumulativeChargedAh, 1, "А·год"),
                    )
                }
            }

            ChargeCard(state.charge)

            VehicleCard(state.vehicle)
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

/**
 * Зарядки. Рахуються за пожиттєвим лічильником BMS, а не інтегруванням: зарядка
 * триває годинами, тож крок лічильника 0.1 кВт·год тут дає соті частки відсотка,
 * і головне — лічильник враховує те, що сталося без телефона.
 */
@Composable
private fun ChargeCard(charge: ChargeLog) {
    if (!charge.charging && !charge.hasLastSession && !charge.hasToday) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Зарядка", fontSize = 18.sp)

            if (charge.charging) {
                MetricRow(
                    "Зараз прийнято",
                    formatMeasurement(charge.sessionKwh, 1, "кВт·год"),
                )
            }

            MetricRow(
                "Остання зарядка",
                if (charge.hasLastSession) {
                    formatMeasurement(charge.lastSessionKwh, 1, "кВт·год")
                } else {
                    NO_VALUE
                },
            )
            if (charge.hasLastSession && charge.lastSessionEndedAtMs > 0L) {
                MetricRow(
                    "Закінчилася",
                    formatAgo(System.currentTimeMillis() - charge.lastSessionEndedAtMs),
                )
            }

            MetricRow("За добу", formatMeasurement(charge.todayKwh, 1, "кВт·год"))

            Text(
                text = "Рахується за лічильником BMS, тому враховує й ті зарядки, " +
                    "що пройшли без телефона.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VehicleCard(vehicle: VehicleData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Дані з шини авто", fontSize = 18.sp)

            MetricRow(
                "Пробіг",
                if (vehicle.hasOdometer) formatMeasurement(vehicle.odometerKm, 1, "км") else "--",
            )
            MetricRow(
                "Швидкість",
                if (vehicle.hasSpeed) formatMeasurement(vehicle.speedKmh, 0, "км/год") else "--",
            )
            MetricRow(
                "SOC панелі",
                if (vehicle.hasDisplaySoc) "${formatDecimal(vehicle.displaySocPercent, 1)}%" else "--",
            )
            MetricRow(
                "SOC точний",
                if (vehicle.hasPreciseSoc) "${formatDecimal(vehicle.preciseSocPercent, 1)}%" else "--",
            )
            MetricRow(
                "Запас ходу",
                if (vehicle.hasRange) formatMeasurement(vehicle.rangeKm.toDouble(), 0, "км") else "--",
            )
            MetricRow(
                "За бортом",
                if (vehicle.hasAmbientTemp) formatMeasurement(vehicle.ambientTempC, 1, "°C") else "--",
            )

            if (vehicle.charging.isCharging) {
                MetricRow(
                    "Заряджання",
                    formatMeasurement(vehicle.charging.powerKw, 1, "кВт"),
                )
            }

            if (!vehicle.hasOdometer) {
                Text(
                    text = "Пробіг і швидкість приходять широкомовними кадрами; " +
                        "додаток слухає шину раз на кілька секунд.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
