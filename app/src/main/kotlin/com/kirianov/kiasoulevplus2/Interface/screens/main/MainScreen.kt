// UI головного екрана: статус підключення та показники ВВБ із GeneralData.state.

package com.kirianov.kiasoulevplus2.Interface.screens.main

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.PairedDevice
import com.kirianov.kiasoulevplus2.Data.State
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
        ConnectionCard(
            state = state,
            onConnectClick = mainViewModel::onConnectClick,
            onAutoConnectChange = mainViewModel::onAutoConnectChange,
        )

        BackgroundWorkCard()

        WakeOnCarCard(
            devices = state.pairedDevices,
            selected = state.settings.wakeOnDeviceAddress,
            onSelected = mainViewModel::onWakeDeviceChange,
        )

        // Усі картки на місці з першої секунди, ще до підключення: інакше екран
        // після запуску виглядає напівпорожнім, і незрозуміло, чи застосунок
        // щось умієе взагалі. Замість чисел — прочерки.
        BatteryCard(bms, calculated)

        ConsumptionCard(
            stats = calculated.window,
            selected = state.consumptionWindow,
            hasOdometer = state.vehicle.hasOdometer,
            onWindowSelected = mainViewModel::onWindowSelected,
        )

        LifetimeCountersCard(bms)

        ChargeCard(state.charge)

        VehicleCard(state.vehicle)

        CellsCard(calculated)
    }
}

@Composable
private fun ConnectionCard(
    state: State,
    onConnectClick: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
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
                    onClick = onConnectClick,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Автопідключення", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.settings.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                )
            }

            Text(
                text = "Статус: ${state.debugInfo}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BatteryCard(bms: BmsData, calculated: CalculatedData) {
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
}

@Composable
private fun LifetimeCountersCard(bms: BmsData) {
    val counters = bms.hasEnergyCounters

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Лічильники батареї за весь час", fontSize = 18.sp)

            MetricRow(
                "Віддано",
                if (counters) formatMeasurement(bms.cumulativeEnergyDischargedKwh, 1, "кВт·год") else NO_VALUE,
            )
            MetricRow(
                "Прийнято",
                if (counters) formatMeasurement(bms.cumulativeEnergyChargedKwh, 1, "кВт·год") else NO_VALUE,
            )

            // Ампер-години поруч навмисно: їх відношення до кВт·год дає середню
            // напругу пакета, і саме цим звіряється, що прочитані ті байти.
            // Раніше як кВт·год показувалися саме ці числа.
            MetricRow(
                "Віддано, заряд",
                if (counters) formatMeasurement(bms.cumulativeDischargedAh, 1, "А·год") else NO_VALUE,
            )
            MetricRow(
                "Прийнято, заряд",
                if (counters) formatMeasurement(bms.cumulativeChargedAh, 1, "А·год") else NO_VALUE,
            )
        }
    }
}

@Composable
private fun CellsCard(calculated: CalculatedData) {
    val known = calculated.maxCellVoltage > 0.0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Комірки", fontSize = 18.sp)
            MetricRow("Мінімальна", if (known) formatMeasurement(calculated.minCellVoltage, 3, "В") else NO_VALUE)
            MetricRow("Максимальна", if (known) formatMeasurement(calculated.maxCellVoltage, 3, "В") else NO_VALUE)
            MetricRow("Розбаланс ΔV", if (known) formatMeasurement(calculated.cellDeltaVolts, 3, "В") else NO_VALUE)
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
                    "що пройшли без телефона: якщо лічильник виріс, заряд піднявся, " +
                    "а віддано нічого не було — це зарядка.",
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


/**
 * Прохання до системи не присипляти застосунок.
 *
 * ЧОМУ ЦЕ ВЗАГАЛІ ПОТРІБНО. Служба переднього плану тримає ПРОЦЕС, але не
 * гарантує, що йому дадуть працювати. У журналі поїздки є проміжок на 634
 * секунди, за який номер зчитування шини зріс лише на два: з'єднання живе,
 * сповіщення висить, а опитування фактично стоїть. Так поводяться Doze і фірмові
 * «оптимізації» оболонок — на Xiaomi особливо охоче.
 *
 * Частковий wake lock у службі — половина ліки. Друга половина ось ця: попросити
 * систему винести застосунок з-під оптимізації батареї. Дозвіл дає користувач, і
 * без нього застосунок працює, просто з дірками у фоні.
 *
 * Картки немає, коли дозвіл уже є: місце на екрані дорожче за нагадування про
 * зроблене.
 */
@Composable
private fun BackgroundWorkCard() {
    val context = LocalContext.current
    // Лічильник перевірок: після повернення з системного діалога стан треба
    // прочитати наново, а сам PowerManager про зміну нікого не сповіщає.
    var checks by remember { mutableIntStateOf(0) }
    val allowed = remember(checks) { runsUnrestricted(context) }
    if (allowed) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "Робота у фоні обмежена", fontSize = 18.sp)
            Text(
                text = "Система присипляє застосунок зі згорнутим екраном, і опитування " +
                    "спиняється посеред поїздки — з'єднання при цьому виглядає живим. " +
                    "Дозвольте роботу без обмежень, щоб дані не мали дірок.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    askToRunUnrestricted(context)
                    checks++
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Дозволити роботу у фоні")
            }
        }
    }
}

private fun runsUnrestricted(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}

/**
 * Відкриває системний діалог. Якщо оболонка його не має — відкриваємо загальний
 * список оптимізації батареї, а якщо немає й того, мовчимо: краще нічого, ніж
 * падіння застосунку через чужу прошивку.
 */
private fun askToRunUnrestricted(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

/**
 * Вибір пристрою, поява якого будить застосунок.
 *
 * Задум простий: магнітола з'єднується з телефоном щоразу, коли ви сіли в авто, —
 * надійнішої ознаки «поїхали» в телефона просто немає. Сам ELM для цього годиться
 * гірше: клони не підіймають зв'язок самі, а чекають, поки під'єднаються до них,
 * тобто саме тоді, коли застосунок уже працює.
 *
 * Картка згорнута, поки пристрій не обрано або поки її не розкрили: список
 * спарованих пристроїв довгий, а звертаються до нього раз у житті.
 */
@Composable
private fun WakeOnCarCard(
    devices: List<PairedDevice>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    if (devices.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val chosen = devices.firstOrNull { it.address == selected }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Прокидатися в авто", fontSize = 18.sp)
                Text(
                    text = if (open) "згорнути" else "змінити",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { open = !open },
                )
            }

            Text(
                text = chosen?.let { "Будить: ${it.name}" }
                    ?: "Пристрій не обрано — застосунок доведеться відкривати вручну.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (open) {
                Text(
                    text = "Оберіть магнітолу авто: телефон з'єднується з нею щоразу, " +
                        "коли ви сідаєте за кермо. Для роботи потрібен дозвіл із картки вище.",
                    style = MaterialTheme.typography.bodySmall,
                )
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(if (device.address == selected) "" else device.address)
                                open = false
                            }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = device.name)
                        if (device.address == selected) {
                            Text(text = "обрано", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
