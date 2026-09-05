// ====================================================================================
// ЕКРАН НАЛАШТУВАНЬ (SettingsScreen)
//
// З'явився тоді, коли вибір користувача перестав уміщатися в картки головного
// екрана. Тут зібрано те, що налаштовують раз і надовго: яке це авто, як
// під'єднуватися, чи вести журнал.
//
// ГОЛОВНЕ ТУТ — ЄМНІСТЬ ПАКЕТА, і не через складність, а через ціну помилки.
// Довго вона була константою: 50.88 кВт·год, тобто шістнадцять комірок CATL цього
// конкретного авто. Поки застосунок жив на одному телефоні й одній машині, це було
// чесно. Щойно з'явився намір дати APK іншій людині, константа стала небезпечною —
// на стоковому Soul EV з рідними 27 кВт·год застосунок упевнено обіцяв би вдвічі
// більший запас, і людина поїхала б за цією цифрою.
//
// Тому число задає власник авто, а поки не задав — береться РІДНИЙ пакет. Обережність
// тут однобока навмисно: занизити запас означає зайву зупинку на зарядці, завищити —
// зупинку на дорозі.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.Garage
import com.kirianov.kiasoulevplus2.Data.Pack
import com.kirianov.kiasoulevplus2.Data.PairedDevice
import com.kirianov.kiasoulevplus2.Data.Settings
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal

@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel) {
    val state by settingsViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CarProfileCard(
            garage = state.garage,
            connected = state.isConnected,
            onName = settingsViewModel::onCarNameChange,
            onPack = settingsViewModel::onPackKwhChange,
            onSelect = settingsViewModel::onCarSelected,
        )

        ConnectionCard(
            settings = state.settings,
            devices = state.pairedDevices,
            onAutoConnect = settingsViewModel::onAutoConnectChange,
            onWakeDevice = settingsViewModel::onWakeDeviceChange,
        )

        BackgroundWorkCard()

        JournalSwitchCard(
            enabled = state.settings.journal,
            onChange = settingsViewModel::onJournalChange,
        )
    }
}

/**
 * Профіль авто: як його звати й яка в ньому батарея.
 *
 * Ємність питається прямо, без здогадів. Вивести її з чогось на шині неможливо:
 * BMS рахує відсотки за паспортом РІДНОГО пакета й про заміну не знає — саме тому
 * шкала на перепакованій батареї й виходить нерівною. Знає про заміну лише той,
 * хто її робив.
 */
@Composable
private fun CarProfileCard(
    garage: Garage,
    connected: Boolean,
    onName: (String) -> Unit,
    onPack: (String) -> String?,
    onSelect: (String) -> Unit,
) {
    val car = garage.active
    var pack by remember(car.vin, car.packKwh) {
        mutableStateOf(if (car.packKnown) formatDecimal(car.packKwh, 2) else "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

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
                Text(text = "Профіль авто", fontSize = 18.sp)
                // Вибір руками має сенс лише без зв'язку: на шині VIN сам себе
                // назве, і давати можливість «дивитися чуже» при живому авто
                // означало б плутати перегляд із обліком.
                if (!connected && garage.cars.size > 1) {
                    Text(
                        text = if (picking) "згорнути" else "обрати авто",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { picking = !picking },
                    )
                }
            }

            Text(
                text = when {
                    car.known -> "VIN ...${car.vin.takeLast(6)}"
                    connected -> "VIN ще не прочитано з шини"
                    else -> "Авто не обрано"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (garage.mismatched) {
                Text(
                    text = "Увага: на шині інше авто (...${garage.detectedVin.takeLast(6)}). " +
                        "Облік ведеться за обраним, а не за під'єднаним.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (picking) {
                garage.cars.sortedByDescending { it.lastSeenAtMs }.forEach { known ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(known.vin)
                                picking = false
                            }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = known.name.ifEmpty { "...${known.vin.takeLast(6)}" })
                        if (known.vin == car.vin) {
                            Text(text = "обрано", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = car.name,
                onValueChange = onName,
                label = { Text("Назва") },
                placeholder = { Text("Soul EV") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = pack,
                onValueChange = {
                    pack = it
                    error = onPack(it)
                },
                label = { Text("Ємність батареї, кВт·год") },
                placeholder = { Text(formatDecimal(Pack.ORIGINAL_CAPACITY_KWH, 1)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = if (car.packKnown) {
                    "Рахуємо за ${formatDecimal(car.packKwh, 2)} кВт·год. " +
                        "Це число — старт: глибока зарядка з низьких відсотків його уточнить."
                } else {
                    "Не задано, тому рахуємо за рідним пакетом — " +
                        "${formatDecimal(Pack.ORIGINAL_CAPACITY_KWH, 1)} кВт·год. " +
                        "Якщо батарею міняли, впишіть справжню ємність: інакше запас ходу " +
                        "буде занижений у стільки ж разів, у скільки новий пакет більший."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = "Зміна ємності перезбирає модель прогнозу заново — журнал поїздок " +
                    "при цьому цілий, тож нічого не втрачається.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Як під'єднуватися: самостійно чи вручну, і що вважати ознакою «сів за кермо». */
@Composable
private fun ConnectionCard(
    settings: Settings,
    devices: List<PairedDevice>,
    onAutoConnect: (Boolean) -> Unit,
    onWakeDevice: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = devices.firstOrNull { it.address == settings.wakeOnDeviceAddress }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Підключення", fontSize = 18.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Підключатися самостійно")
                Switch(checked = settings.autoConnect, onCheckedChange = onAutoConnect)
            }

            Text(
                text = "Без цього моделі вчаться лише тоді, коли хтось згадав натиснути " +
                    "кнопку, а найцінніші дані — щоденні поїздки — просто не потрапляють у журнал.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (devices.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chosen?.let { "Будить: ${it.name}" } ?: "Пристрій для запуску не обрано",
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (open) "згорнути" else "змінити",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { open = !open },
                    )
                }

                if (open) {
                    Text(
                        text = "Оберіть магнітолу авто: телефон з'єднується з нею щоразу, " +
                            "коли ви сідаєте за кермо. Це найнадійніша ознака «поїхали» з усіх, " +
                            "що є в телефона, — сам адаптер так не вміє.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onWakeDevice(
                                        if (device.address == settings.wakeOnDeviceAddress) {
                                            ""
                                        } else {
                                            device.address
                                        },
                                    )
                                    open = false
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = device.name)
                            if (device.address == settings.wakeOnDeviceAddress) {
                                Text(text = "обрано", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Спарованих пристроїв ще не видно. Під'єднайтеся до авто хоч раз, " +
                        "щоб застосунок побачив список.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Дозвіл працювати у фоні без обмежень.
 *
 * Картка зникає сама, щойно дозвіл є: тримати на екрані пораду, яку вже виконали,
 * означає вчити людину не читати підказки.
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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

@Composable
private fun JournalSwitchCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
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
                Text(text = "Журнал діагностики", fontSize = 18.sp)
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Text(
                text = "Кілька мегабайтів на день у теці застосунку, старіші рядки витісняються. " +
                    "Саме журнал дозволяє відповісти «чому», а не гадати за знімком екрана. " +
                    "Поділитися ним можна на екрані «Експерименти».",
                style = MaterialTheme.typography.bodySmall,
            )
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
        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (runCatching { context.startActivity(direct) }.isSuccess) return
    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
