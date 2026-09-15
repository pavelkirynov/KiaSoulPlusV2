// UI головного екрана: статус підключення та показники ВВБ із GeneralData.state.

package com.kirianov.kiasoulevplus2.Interface.screens.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CalculatedData
import com.kirianov.kiasoulevplus2.Interface.SelectCarBanner
import com.kirianov.kiasoulevplus2.Data.ChargeConnector
import com.kirianov.kiasoulevplus2.Data.ChargeHistory
import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ChargeSession
import com.kirianov.kiasoulevplus2.Data.ChargingState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.ConsumptionWindow
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.Data.WindowStats
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

        // Не впізнали авто — просимо обрати прямо тут: без цього облік і прогноз
        // стоять, а причина ховалася б у Налаштуваннях.
        SelectCarBanner(
            garage = state.garage,
            connected = state.isConnected,
            odometerKm = state.vehicle.odometerKm,
            kwhIn = bms.cumulativeEnergyChargedKwh,
            kwhOut = bms.cumulativeEnergyDischargedKwh,
            onConfirm = GeneralData::confirmActiveCarManually,
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

        // ЧОМУ ТУТ НЕМАЄ МЕЖ ПОТУЖНОСТІ Й ЗНОСУ ЗА ВЕРСІЄЮ BMS. Вони переїхали в
        // розділ «Авто → Системи», і не для порядку: перша ж поїздка показала, що
        // межі читаються як «90 кВт брати, 90 кВт вливати», а знос — як нуль на
        // всіх дев'яноста шести комірках. Неперевіреному числу не місце поруч із
        // тими, на яких стоять розрахунки: на головній воно читається як факт.
        LifetimeCountersCard(bms)

        ChargeCard(
            charge = state.charge,
            charging = state.vehicle.charging,
            bms = bms,
            packKwh = state.garage.active.effectivePackKwh,
            priceUahPerKwh = state.settings.trip.priceUahPerKwh,
            onFinish = GeneralData::requestChargeFinish,
        )

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
                // Це температура ПЕРШОГО модуля, а не максимум по пакету: байт 16
                // кадру 21 01 — початок списку восьми модулів. Справжні межі
                // по пакету йдуть кадром 21 05 і разом із рештою перенесеного
                // лежать у розділі «Авто → Системи».
                label = "Температура модуля 1",
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
private fun ChargeCard(
    charge: ChargeLog,
    charging: ChargingState,
    bms: BmsData,
    packKwh: Double,
    priceUahPerKwh: Double,
    onFinish: () -> Unit,
) {
    if (!charge.hasBaseline && !charge.hasLastSession && !charge.hasToday) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Зарядка", fontSize = 18.sp)

            // РОЗ'ЄМ — тепер головна межа зарядки, тож він і стоїть першим рядком.
            // Кадр 21 01 повідомляє про нього щосекунди й однаково на змінному та
            // постійному струмі, тобто це те саме свідчення, яке має людина, коли
            // вставляє пістолет. Прочерк тут означає, що кадр батареї ще не
            // прийшов, а не що роз'єму немає.
            MetricRow(
                "Роз'єм",
                when {
                    charging.plugCharging -> "вставлений, іде зарядка"
                    charging.plugged -> "вставлений, зарядка не йде"
                    else -> "не вставлений"
                },
            )

            if (charge.charging) {
                MetricRow(
                    "Зараз прийнято",
                    formatMeasurement(charge.sessionEnergyKwh(packKwh), 1, "кВт·год"),
                )
                // Жива швидкість і струм: потужність = напруга × струм. Струм на
                // зарядці додатний (у батарею); знак уже виправлено в декодері.
                if (bms.hasData) {
                    MetricRow(
                        "Швидкість зараз",
                        formatMeasurement(bms.batteryVoltage * bms.batteryCurrent / 1000.0, 1, "кВт"),
                    )
                    MetricRow("Струм", formatMeasurement(bms.batteryCurrent, 1, "А"))
                }
                val liveConnector = ChargeConnector.of(bms.j1772Plugged, bms.chademoPlugged)
                if (liveConnector != ChargeConnector.UNKNOWN) {
                    MetricRow("Тип", liveConnector.label)
                }
            }

            MetricRow(
                "Остання зарядка",
                if (charge.hasLastSession) {
                    formatMeasurement(charge.lastSessionEnergyKwh(packKwh), 1, "кВт·год")
                } else {
                    NO_VALUE
                },
            )
            // ДВА ЧИСЛА НА ОДНУ ЗАРЯДКУ, і це не надмірність. Головне рахується
            // приростом заряду на корисну ємність — тією самою міркою, якою
            // рахується запас ходу, і саме воно зійшлося з настінником: 77.8 % ×
            // 44 кВт·год = 34.2 при 37.87 на розетці, тобто різниця рівно на
            // втрати зарядного. Лічильник BMS на цій машині дає 22.3 за ту саму
            // зарядку: він прив'язаний до РІДНОГО пакета й про заміну не знає.
            // Прибрати його не можна — він єдиний працює, коли шкала заряду не
            // зрушила, — але й вірити йому як головному теж не можна.
            if (charge.hasLastSession && charge.lastSessionSocRise > 0.0) {
                MetricRow(
                    "Заряд піднявся на",
                    "${formatDecimal(charge.lastSessionSocRise, 1)} %",
                )
                MetricRow(
                    "За лічильником BMS",
                    formatMeasurement(charge.lastSessionKwh, 1, "кВт·год"),
                )
            }
            if (charge.hasLastSession && charge.lastSessionEndedAtMs > 0L) {
                MetricRow(
                    "Закінчилася",
                    formatAgo(System.currentTimeMillis() - charge.lastSessionEndedAtMs),
                )
            }

            MetricRow("За добу", formatMeasurement(charge.todayEnergyKwh(packKwh), 1, "кВт·год"))

            if (charge.lastDecision.isNotEmpty()) {
                MetricRow("Рішення", charge.lastDecision)
            }

            // ЖУРНАЛ ЗАРЯДОК зі згортанням і вибором періоду для підсумків.
            ChargeHistorySection(
                sessions = charge.sessions,
                packKwh = packKwh,
                priceUahPerKwh = priceUahPerKwh,
            )

            Text(
                text = "Головне число — приріст заряду на корисну ємність пакета: саме " +
                    "воно зійшлося з настінником. Лічильник BMS показано поруч, і він " +
                    "занижує майже вдвічі, бо рахує проти рідного пакета. Зарядки без " +
                    "телефона теж потрапляють в облік: якщо пробіг не зріс, а заряд " +
                    "піднявся — авто стояло на зарядці.",
                style = MaterialTheme.typography.bodySmall,
            )

            // Остання лінія оборони. Кінець зарядки автоматика бачить не завжди —
            // телефона в авто в ту мить може просто не бути. Кнопка бере різницю
            // пожиттєвого лічильника від початку зарядки й закриває сесію нею.
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("Кінець зарядки")
            }
            Text(
                text = "Натисніть, якщо зарядка скінчилась, а застосунок цього не " +
                    "помітив: різниця пожиттєвого лічильника від її початку піде в облік.",
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

/** Періоди підсумку історії зарядок. «Період» — свій діапазон дат. */
private enum class HistoryRange(val label: String) {
    TODAY("Сьогодні"),
    DAYS_7("7 днів"),
    DAYS_30("30 днів"),
    ALL("Весь час"),
    CUSTOM("Період"),
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Початок доби (місцевий) для позначки часу. */
private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Кінець доби (місцевий): щоб діапазон «до» включав увесь обраний день. */
private fun endOfDay(ms: Long): Long = startOfDay(ms) + DAY_MS - 1

private fun dateLabel(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(ms))

/** Межі [від, до] обраного періоду. */
private fun rangeBounds(range: HistoryRange, now: Long, from: Long?, to: Long?): Pair<Long, Long> =
    when (range) {
        HistoryRange.TODAY -> startOfDay(now) to now
        HistoryRange.DAYS_7 -> now - 7 * DAY_MS to now
        HistoryRange.DAYS_30 -> now - 30 * DAY_MS to now
        HistoryRange.ALL -> 0L to now
        HistoryRange.CUSTOM ->
            (from?.let { startOfDay(it) } ?: 0L) to (to?.let { endOfDay(it) } ?: now)
    }

/**
 * ЖУРНАЛ ЗАРЯДОК зі згортанням і вибором періоду для сумарного підрахунку.
 *
 * За замовчуванням згорнутий — щоб не тиснути на головну картку. Розгорнувши,
 * можна вибрати період (сьогодні / 7 / 30 днів / весь час / свій діапазон) і
 * побачити суму: скільки зарядок, кВт·год за шкалою, і — якщо задана ціна
 * електрики — гривні. Нижче — список зарядок цього періоду.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChargeHistorySection(
    sessions: List<ChargeSession>,
    packKwh: Double,
    priceUahPerKwh: Double,
) {
    if (sessions.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var range by remember { mutableStateOf(HistoryRange.DAYS_30) }
    var customFrom by remember { mutableStateOf<Long?>(null) }
    var customTo by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Журнал зарядок", style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (expanded) "згорнути" else "розгорнути",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (!expanded) return

    val now = System.currentTimeMillis()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HistoryRange.entries.forEach { option ->
            FilterChip(
                selected = range == option,
                onClick = { range = option },
                label = { Text(option.label) },
            )
        }
    }

    if (range == HistoryRange.CUSTOM) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) {
                Text("Від: " + (customFrom?.let { dateLabel(it) } ?: "—"))
            }
            OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) {
                Text("До: " + (customTo?.let { dateLabel(it) } ?: "—"))
            }
        }
    }

    val (fromMs, toMs) = rangeBounds(range, now, customFrom, customTo)
    val totals = ChargeHistory.totals(sessions, packKwh, fromMs, toMs)
    val cost = if (priceUahPerKwh > 0.0) totals.energyKwh * priceUahPerKwh else 0.0

    MetricRow(
        "За період",
        "${totals.count} зар. · " + formatMeasurement(totals.energyKwh, 1, "кВт·год"),
    )
    if (cost > 0.0) {
        MetricRow("Вартість", formatMeasurement(cost, 0, "грн"))
    }
    if (!totals.isEmpty) {
        Text(
            text = "за лічильником ${formatDecimal(totals.counterKwh, 1)} кВт·год · " +
                "+${formatDecimal(totals.socRise, 0)} %",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val shown = ChargeHistory.inRange(sessions, fromMs, toMs)
    if (shown.isEmpty()) {
        Text(
            text = "За цей період зарядок немає.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        shown.forEach { session ->
            ChargeSessionRow(session = session, packKwh = packKwh, nowMs = now)
        }
    }

    picking?.let { which ->
        val initial = (if (which == "from") customFrom else customTo) ?: now
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { chosen ->
                        if (which == "from") customFrom = chosen else customTo = chosen
                    }
                    picking = null
                }) { Text("Ок") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("Скасувати") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Один рядок журналу зарядок: коли й скільки, а нижче дрібним — приріст заряду й
 * чим сесію закрито. Головне число — за корисною ємністю, як і всюди в картці.
 * Час відносний ([formatAgo]), бо годинник авто збитий, а дата з телефона поруч
 * із даними машини лише плутала б.
 */
@Composable
private fun ChargeSessionRow(session: ChargeSession, packKwh: Double, nowMs: Long) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (session.endedAtMs > 0L) formatAgo(nowMs - session.endedAtMs) else "--",
                fontSize = 15.sp,
            )
            Text(
                text = formatMeasurement(session.energyKwh(packKwh), 1, "кВт·год"),
                fontSize = 15.sp,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        val subtitle = buildList {
            add(session.connector.label)
            if (session.socRise > 0.0) add("+${formatDecimal(session.socRise, 1)} %")
            session.averageKw(packKwh)?.let { add("~${formatDecimal(it, 1)} кВт") }
            if (session.durationMs > 0L) add(formatDuration(session.durationMs))
            if (session.cause.isNotEmpty()) add(session.cause)
        }.joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val NO_VALUE = "--"
