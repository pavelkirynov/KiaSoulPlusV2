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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.FileProvider
import com.kirianov.kiasoulevplus2.Data.CarProfile
import com.kirianov.kiasoulevplus2.Data.Garage
import com.kirianov.kiasoulevplus2.Data.Pack
import com.kirianov.kiasoulevplus2.Data.PairedDevice
import com.kirianov.kiasoulevplus2.Data.Settings
import com.kirianov.kiasoulevplus2.Data.ShareState
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
            onSelect = settingsViewModel::onCarSelected,
            onEdit = settingsViewModel::onCarEdited,
            onDelete = settingsViewModel::onCarDeleted,
        )

        ShareCard(
            share = state.garage.share,
            carKnown = state.garage.active.known,
            onExport = settingsViewModel::onExport,
            onExportHandled = settingsViewModel::onExportHandled,
            onImport = settingsViewModel::onImport,
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
    onSelect: (String) -> Unit,
    onEdit: (String, String, Double) -> Unit,
    onDelete: (String) -> Unit,
) {
    val car = garage.active
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

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
                // Вибір руками доступний завжди — це ПЕРЕГЛЯД, а не перемикання
                // обліку. Сидячи в одній машині, буває треба зазирнути в дані
                // другої: порівняти комірки до перепакування й після, глянути
                // криву. Записи при цьому йдуть у те авто, що на шині, а екран
                // фарбується в інший колір, щоб їх не сплутати.
                Text(
                    text = if (picking) "згорнути" else "обрати авто",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { picking = !picking },
                )
            }

            Text(
                text = when {
                    car.name.isNotEmpty() -> car.name
                    car.known -> "Без назви"
                    connected -> "Авто ще не назвалося"
                    else -> "Авто не обрано"
                },
                fontSize = 16.sp,
            )

            if (car.known) {
                Text(
                    text = "VIN ...${car.vin.takeLast(6)} · " +
                        "${formatDecimal(car.effectivePackKwh, 2)} кВт·год" +
                        if (car.packKnown) "" else " (рідний пакет)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Поки авто не назвалося, облік стоїть — і людина має знати, чому
            // числа на екрані живі, а лічильник зарядки не рухається.
            if (connected && !garage.identified && !garage.viewingOther) {
                Text(
                    text = if (garage.vinPending) {
                        "Машина на шині ще не назвала VIN. Дані показуються, але " +
                            "нікуди не записуються: поки невідомо, чиї вони."
                    } else {
                        "Машина на шині не назвала VIN. Облік іде за обраним авто — " +
                            "перевірте, що це саме воно."
                    },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (garage.viewingOther) {
                Text(
                    text = "Показано це авто, а на шині ...${garage.detectedVin.takeLast(6)}. " +
                        "Живі числа йдуть від тієї машини, що на шині, і записуються " +
                        "теж їй. Сюди зараз нічого не пишеться.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (picking) {
                if (garage.cars.isEmpty()) {
                    Text(
                        text = "Гараж порожній. Авто заводиться саме, щойно назве VIN " +
                            "на шині.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                garage.cars.sortedByDescending { it.lastSeenAtMs }.forEach { known ->
                    CarRow(
                        car = known,
                        chosen = known.vin == car.vin,
                        onBus = known.vin == garage.detectedVin,
                        onSelect = {
                            onSelect(known.vin)
                            picking = false
                        },
                        onEdit = { editing = known.vin },
                        onDelete = { removing = known.vin },
                    )
                }
            }

            editing?.let { vin ->
                garage.cars.firstOrNull { it.vin == vin }?.let { target ->
                    EditCarDialog(
                        car = target,
                        onDismiss = { editing = null },
                        onSave = { name, kwh ->
                            onEdit(vin, name, kwh)
                            editing = null
                        },
                    )
                }
            }

            // Видалення питає підтвердження, і не з ввічливості: разом із авто
            // зникає його тека, а в ній тижні замірів, яких більше нізвідки взяти.
            removing?.let { vin ->
                val name = garage.cars.firstOrNull { it.vin == vin }?.name.orEmpty()
                AlertDialog(
                    onDismissRequest = { removing = null },
                    title = { Text("Видалити авто?") },
                    text = {
                        Text(
                            "${name.ifEmpty { "...${vin.takeLast(6)}" }} — разом із кривою " +
                                "ємності, обліком зарядок, моделлю прогнозу й історією комірок. " +
                                "Повернути це буде нізвідки.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            onDelete(vin)
                            removing = null
                        }) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { removing = null }) { Text("Скасувати") }
                    },
                )
            }

            Text(
                text = if (car.packKnown) {
                    "Рахуємо за ${formatDecimal(car.packKwh, 2)} кВт·год. " +
                        "Це число — старт: глибока зарядка з низьких відсотків його уточнить."
                } else {
                    "Ємність не задано, тому рахуємо за рідним пакетом — " +
                        "${formatDecimal(Pack.ORIGINAL_CAPACITY_KWH, 1)} кВт·год. " +
                        "Якщо батарею міняли, впишіть справжню через «обрати авто → змінити»: " +
                        "інакше запас ходу буде занижений у стільки ж разів, у скільки новий " +
                        "пакет більший."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Один рядок списку: назва, і поруч те, що з цим авто можна зробити. */
@Composable
private fun CarRow(
    car: CarProfile,
    chosen: Boolean,
    onBus: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = car.name.ifEmpty { "...${car.vin.takeLast(6)}" })
            Text(
                text = "...${car.vin.takeLast(6)} · ${formatDecimal(car.effectivePackKwh, 2)} кВт·год" +
                    if (chosen) " · обрано" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "змінити",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onEdit),
            )
            // Авто, яке зараз на шині, видалити не можна: воно з'явилося б назад
            // тієї ж миті, а дані вже пішли б.
            if (!onBus) {
                Text(
                    text = "видалити",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
        }
    }
}

/**
 * ПРАВКА АВТО ЖИВЕ У ВІКНІ, А НЕ ПРЯМО НА КАРТЦІ.
 *
 * Поля на картці стояли завжди відкритими, і кожен дотик до екрана міг тихо
 * змінити ємність пакета. Ціна такої випадковості несиметрична: зміна ємності
 * перезбирає модель прогнозу, і людина дізнається про це не одразу, а коли запас
 * ходу почне брехати.
 *
 * Вікно ж вимагає двох свідомих дій — відкрити й зберегти, — і поки воно не
 * збережене, у гаражі не змінюється нічого.
 */
@Composable
private fun EditCarDialog(
    car: CarProfile,
    onDismiss: () -> Unit,
    onSave: (String, Double) -> Unit,
) {
    var name by remember(car.vin) { mutableStateOf(car.name) }
    var pack by remember(car.vin) {
        mutableStateOf(if (car.packKnown) formatDecimal(car.packKwh, 2) else "")
    }

    val trimmed = pack.trim().replace(',', '.')
    val kwh = if (trimmed.isEmpty()) 0.0 else trimmed.toDoubleOrNull()
    val error = when {
        kwh == null -> "Не схоже на число"
        kwh < 0.0 -> "Ємність не буває від'ємною"
        kwh > MAX_PLAUSIBLE_KWH -> "Більше за $MAX_PLAUSIBLE_KWH кВт·год у це авто не влізе"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Авто ...${car.vin.takeLast(6)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва") },
                    placeholder = { Text("Soul EV") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pack,
                    onValueChange = { pack = it },
                    label = { Text("Ємність батареї, кВт·год") },
                    placeholder = { Text(formatDecimal(Pack.ORIGINAL_CAPACITY_KWH, 1)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = error != null,
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "Зміна ємності перезбирає модель прогнозу заново — журнал " +
                        "поїздок при цьому цілий, тож нічого не втрачається.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), kwh ?: 0.0) },
                enabled = error == null,
            ) { Text("Зберегти") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        },
    )
}

/**
 * Понад це в Soul EV не влізе фізично, і таке число майже напевно означає, що
 * людина ввела ват-години або промахнулася комою.
 */
private const val MAX_PLAUSIBLE_KWH = 120.0

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


/**
 * ОБМІН ДАНИМИ АВТО МІЖ ТЕЛЕФОНАМИ.
 *
 * Одна машина, два водії, два телефони: кожен бачить свою половину поїздок, і жодна
 * з половин сама по собі не дає повної картини. Сервера в застосунку немає — а от
 * файл переслати вміє будь-хто.
 *
 * ЗЛИТИ, А НЕ ЗАМІНИТИ, і саме тому кнопка називається «Прийняти», а не
 * «Відновити». Журнал поїздок об'єднується без повторів, суми кривої додаються,
 * облік зарядок береться свіжіший. Обидва телефони після обміну знають те саме.
 *
 * Один і той самий файл двічі не приймається: суми кривої додаються, і повторне
 * прийняття тихо порахувало б ті самі проходи двічі.
 */
@Composable
private fun ShareCard(
    share: ShareState,
    carKnown: Boolean,
    onExport: () -> Unit,
    onExportHandled: () -> Unit,
    onImport: (String) -> Unit,
) {
    val context = LocalContext.current

    // Готовий пакунок віддається системі одразу, щойно блок його зібрав. Тримати
    // на екрані ще одну кнопку «а тепер надіслати» немає сенсу: людина вже
    // натиснула «Поділитися».
    LaunchedEffect(share.exportedPath) {
        if (share.exportedPath.isNotEmpty()) {
            shareBundle(context, share.exportedPath)
            onExportHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Копіюємо до себе: обраний документ живе за чужим дозволом, який діє лише
        // поки триває ця дія, а прийняти файл треба вже у фоновому блоці.
        copyToCache(context, uri)?.let(onImport)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Обмін даними авто", fontSize = 18.sp)

            Text(
                text = "Якщо цією машиною їздять із двох телефонів, кожен бачить лише " +
                    "свою половину поїздок. Файл нижче переносить накопичене з одного " +
                    "телефона на інший — і не замінює тамтешнє, а зливається з ним.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onExport,
                    enabled = carKnown,
                    modifier = Modifier.weight(1f),
                ) { Text("Поділитися") }
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = carKnown,
                    modifier = Modifier.weight(1f),
                ) { Text("Прийняти") }
            }

            if (!carKnown) {
                Text(
                    text = "Спершу під'єднайтеся до авто: обмін прив'язаний до VIN, " +
                        "щоб дані однієї машини не потрапили в іншу.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (share.note.isNotEmpty()) {
                Text(text = share.note, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Віддає пакунок системі. Через FileProvider: тека застосунку іншим застосункам
 * напряму недоступна, і «Поділитися» без content:// просто нічого не відкриє.
 */
private fun shareBundle(context: Context, path: String) {
    runCatching {
        val file = java.io.File(path)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Дані авто").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** @return шлях до копії обраного файлу, або null, якщо прочитати його не вдалося. */
private fun copyToCache(context: Context, uri: Uri): String? = runCatching {
    val target = java.io.File(context.cacheDir, "incoming.kiasoul")
    context.contentResolver.openInputStream(uri)!!.use { input ->
        target.outputStream().use { input.copyTo(it) }
    }
    target.absolutePath
}.getOrNull()
