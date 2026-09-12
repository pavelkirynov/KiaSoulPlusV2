// ====================================================================================
// ЕКРАН КОМІРОК (CellsScreen)
//
// Показує 96 комірок трьома розкладками — рівною сіткою, блоками ВВБ і модулями
// 6s, — і кнопку зчитування. Значення, зчитані з авто, мають пріоритет; вручну
// введені зберігаються й показуються, доки з машини нічого не прийшло.
//
// ФАРБУЄ ВІДСТАВАННЯ ВІД НАЙКРАЩОЇ КОМІРКИ, а не саме значення, і в кожному режимі
// своїми порогами: у спокої комірки розходяться на одиниці мілівольт, під струмом
// на десятки. Досі колір брав лише вердикт тесту під навантаженням, і режими «ХХ»,
// «Відхилення» та «Ввід» стояли безбарвними — саме ті, в яких дивляться найчастіше.
// Порогами керує вікно з кнопки над сіткою; самі числа — в [CellReadings].
//
// ЛОГА ELM ТУТ БІЛЬШЕ НЕМА. Він займав сотню точок висоти на екрані, куди приходять
// дивитися на дев'яносто шість комірок, а те саме видно в журналі й на екрані
// «Експерименти».
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.cells

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.CellHistory
import com.kirianov.kiasoulevplus2.Data.CellLayout
import com.kirianov.kiasoulevplus2.Data.CellLevel
import com.kirianov.kiasoulevplus2.Data.CellPalette
import com.kirianov.kiasoulevplus2.Data.CellReadings
import com.kirianov.kiasoulevplus2.Data.CellRecord
import com.kirianov.kiasoulevplus2.Data.CellTestResult
import com.kirianov.kiasoulevplus2.Data.CellValueMode
import com.kirianov.kiasoulevplus2.Data.CellVerdict
import com.kirianov.kiasoulevplus2.Data.CellTestState
import com.kirianov.kiasoulevplus2.Data.ManualCells
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import com.kirianov.kiasoulevplus2.tools.format.parseDecimalInput
import com.kirianov.kiasoulevplus2.tools.format.formatMeasurement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GRID_COLUMNS = 8

/**
 * Клітинка нижча за квадрат, а шрифт у ній більший.
 *
 * Квадрат 34×34 витрачав висоту на порожнечу, а число в ньому стояло восьмим
 * кеглем — на ходу такі цифри не читаються, а дивляться на них саме на ходу, під
 * час тесту. Ширина лишається від сітки, висота падає, шрифт росте.
 */
private val GRID_CELL_HEIGHT = 28.dp

/**
 * Розмір числа в клітинці. Один на всі режими, включно з полем введення: різні
 * розміри на одній сітці читаються як різні дані.
 */
private val CELL_VALUE_SIZE = 14.sp

/** Номер комірки: помітно дрібніший за значення, але читаний на ходу. */
private val CELL_INDEX_SIZE = 9.sp

/** Дванадцять рядів по висоті клітинки плюс проміжки. */
private val GRID_HEIGHT = (GRID_CELL_HEIGHT + 3.dp) * 12

/** У блоках рядів удвічі більше, тож клітинка ще нижча. */
private val BLOCK_CELL_HEIGHT = 24.dp

/**
 * Модулі 6s: дванадцять комірок в рядок, тож клітинка вузька — і саме тому висока.
 *
 * Число в неї по горизонталі не влазить жодним кеглем: на 28 точок ширини «3.65»
 * доводиться або обрізати, або писати сьомим шрифтом. Тому текст тут повернутий на
 * 90°, і висота клітинки — це та довжина, якою число врешті й читається.
 */
private val SIX_CELL_HEIGHT = 66.dp

private enum class CellsViewMode { GRID, BLOCKS, SIX }

@Composable
fun CellsScreen(cellsViewModel: CellsViewModel) {
    var viewMode by remember { mutableStateOf(CellsViewMode.GRID) }

    /** Час заміру, який зараз відкритий. Нуль — дивимось живі комірки. */
    var shownAtMs by remember { mutableStateOf(0L) }

    /**
     * Історія згорнута, поки її не попросили.
     *
     * Двадцять записів по два рядки — це пів екрана над сіткою, і щоразу, коли
     * треба глянути на комірки, доводилося їх прокручувати.
     */
    var historyOpen by remember { mutableStateOf(false) }

    /** Чи відкрите вікно порогів фарбування. */
    var paletteOpen by remember { mutableStateOf(false) }

    val appState by cellsViewModel.uiState.collectAsState()
    val cellData = appState.cells
    val manualCells = appState.manualCells

    // ПОРОЖНЯ СІТКА — ПОГАНА ВІДПОВІДЬ. Перемкнувся на друге авто, живі напруги
    // погасли (вони від першого), і дивитися нема на що, хоч заміри в нього є.
    // Тому коли своїх чисел на екрані немає, сітка сама відкриває найсвіжіший
    // збережений замір цієї машини.
    val newest = appState.cellHistory.records.firstOrNull()
    val fallback = if (cellData.cellVoltages.isEmpty()) newest else null
    val shownRecord = appState.cellHistory.records.firstOrNull { it.atMs == shownAtMs } ?: fallback
    val shownCells = shownRecord?.let { cellsOf(it) } ?: cellData
    val shownTest = shownRecord?.let { testOf(it, appState.cellTest) } ?: appState.cellTest

    // Індикатор виводиться прямо з прапорця запиту, тому розсинхрону бути не може.
    val isLoading = appState.inputBms.scanCellsRequested

    val storedVoltages = manualCells.voltages.values.filter { it > 0.0 }
    val minVoltage = cellData.minVoltage.takeIf { it > 0.0 } ?: (storedVoltages.minOrNull() ?: 0.0)
    val maxVoltage = cellData.maxVoltage.takeIf { it > 0.0 } ?: (storedVoltages.maxOrNull() ?: 0.0)
    val delta = cellData.deltaVoltage.takeIf { it > 0.0 }
        ?: if (maxVoltage > 0.0 && minVoltage > 0.0) maxVoltage - minVoltage else 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatItem(label = "Мін.", value = minVoltage)
            StatItem(label = "Макс.", value = maxVoltage)
            StatItem(label = "ΔV", value = delta)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Button(
            onClick = { cellsViewModel.onRequestReadCells() },
            enabled = !isLoading && !appState.cellTest.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isLoading) "Зчитую..." else "Зчитати комірки з авто")
        }

        Spacer(modifier = Modifier.height(6.dp))

        LoadTestCard(
            test = appState.cellTest,
            onToggle = cellsViewModel::onLoadTestToggle,
            onClear = cellsViewModel::onLoadTestClear,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = viewMode == CellsViewMode.GRID,
                onClick = { viewMode = CellsViewMode.GRID },
                label = { Text("Сітка") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == CellsViewMode.BLOCKS,
                onClick = { viewMode = CellsViewMode.BLOCKS },
                label = { Text("Блоки ВВБ") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == CellsViewMode.SIX,
                onClick = { viewMode = CellsViewMode.SIX },
                label = { Text("Модулі 6s") },
                modifier = Modifier.weight(1f),
            )
        }

        // Що показувати в клітинках. З'являється лише коли тест уже щось намірив:
        // до того перемикати нема між чим, а порожні чипи вчать не читати підказки.
        if (appState.cellTest.result.hasCells) {
            Spacer(modifier = Modifier.height(6.dp))
            ValueModeChips(
                selected = appState.cellTest.valueMode,
                resistanceKnown = appState.cellTest.result.resistanceKnown,
                onSelect = cellsViewModel::onValueModeChange,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        HistoryCard(
            history = appState.cellHistory,
            shown = shownRecord,
            open = historyOpen,
            canSave = cellData.cellVoltages.isNotEmpty(),
            onToggleOpen = { historyOpen = !historyOpen },
            onSave = cellsViewModel::onSaveSnapshot,
            onShow = { shownAtMs = if (shownAtMs == it) 0L else it },
            onDelete = cellsViewModel::onDeleteRecord,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // ЧИМ ФАРБУЄМО. Пороги — свої для кожного режиму значень, а відставання
        // рахується від найкращої комірки в пакеті: див. [CellReadings].
        val valueMode = shownTest.valueMode
        val palette = appState.settings.cellPalettes.of(valueMode)
        val readings = CellReadings.allOf(valueMode, shownCells, manualCells, shownTest)
        val reference = CellReadings.referenceOf(readings, valueMode)

        ColorLegend(
            unit = appState.settings.cellPalettes.unitOf(valueMode),
            palette = palette,
            counts = CellReadings.countOf(readings, valueMode, palette),
            onOpenSettings = { paletteOpen = true },
        )

        Spacer(modifier = Modifier.height(6.dp))

        val paint: (Int) -> Color? = { index ->
            colorOf(CellReadings.levelOf(readings.getOrNull(index), reference, palette))
        }

        when (viewMode) {
            CellsViewMode.GRID ->
                CompactGridView(cellsViewModel, shownCells, manualCells, shownTest, paint)
            CellsViewMode.BLOCKS ->
                BlocksView(cellsViewModel, shownCells, manualCells, shownTest, paint)
            CellsViewMode.SIX ->
                SixModulesView(cellsViewModel, shownCells, manualCells, shownTest, paint)
        }
    }

    if (paletteOpen) {
        PaletteDialog(
            title = titleOf(shownTest.valueMode),
            unit = appState.settings.cellPalettes.unitOf(shownTest.valueMode),
            palette = appState.settings.cellPalettes.of(shownTest.valueMode),
            onDismiss = { paletteOpen = false },
            onApply = { palette ->
                cellsViewModel.onPaletteChange(shownTest.valueMode, palette)
                paletteOpen = false
            },
        )
    }
}

/**
 * Збережений замір показується тією самою сіткою, що й живі комірки.
 *
 * Для цього запис перетворюється назад на ті самі дві речі, які сітка вміє
 * малювати, — напруги й підсумок тесту. Другої сітки «для історії» не з'являється,
 * а отже й не буває так, що одна з них уміє щось, чого не вміє інша.
 */
private fun cellsOf(record: CellRecord) = CellData(
    cellVoltages = record.restVolts,
    minVoltage = record.restVolts.minOrNull() ?: 0.0,
    maxVoltage = record.restVolts.maxOrNull() ?: 0.0,
    deltaVoltage = record.spreadVolts,
)

private fun testOf(record: CellRecord, mode: CellTestState): CellTestState {
    if (!record.fromLoadTest) return CellTestState(valueMode = CellValueMode.Rest)
    val cells = record.restVolts.indices.map { index ->
        CellVerdict(
            index = index,
            restVolts = record.restVolts[index],
            excessMilliOhm = record.excessMilliOhm.getOrNull(index),
            minVolts = record.minVolts.getOrNull(index) ?: 0.0,
            worstDeviationVolts = 0.0,
        )
    }
    return CellTestState(
        colorMode = mode.colorMode,
        valueMode = mode.valueMode,
        result = CellTestResult(
            sweeps = record.sweeps,
            currentSpreadA = record.currentSpreadA,
            peakLoadKw = record.peakLoadKw,
            cells = cells,
            resistanceKnown = record.excessMilliOhm.isNotEmpty(),
        ),
    )
}

/**
 * Список збережених замірів.
 *
 * Кожен рядок — дата, пробіг, заряд і розкид напруг. Саме розкид тут головне число:
 * воно одне описує пакет коротше за всі дев'яносто шість комірок, і по ньому видно,
 * чи є сенс відкривати замір узагалі.
 */
@Composable
private fun HistoryCard(
    history: CellHistory,
    shown: CellRecord?,
    open: Boolean,
    canSave: Boolean,
    onToggleOpen: () -> Unit,
    onSave: () -> Unit,
    onShow: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ДВІ КНОПКИ В РЯД, А НЕ СПИСОК НА ПІВЕКРАНА. Заміри потрібні рідко, а
        // комірки — щоразу; список над сіткою доводилося прокручувати кожного
        // разу, коли треба було глянути на пакет.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = onSave, enabled = canSave, modifier = Modifier.weight(1f)) {
                Text("Зберегти")
            }
            Button(onClick = onToggleOpen, modifier = Modifier.weight(1f)) {
                Text(if (open) "Сховати" else "Показати (${history.records.size})")
            }
        }

        // Замір, який зараз на сітці, видно й зі згорнутою історією: інакше
        // незрозуміло, чому числа не змінюються, коли авто на зв'язку.
        if (shown != null) {
            Text(
                text = "Сітка показує замір від ${stampOf(shown.atMs)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (!open) return@Column

        if (history.isEmpty) {
            Text(
                text = "Порожньо. Закінчений тест під навантаженням лягає сюди сам, " +
                    "а простий замір напруг — кнопкою «Зберегти». Порівнювати можна " +
                    "буде з другого запису.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        history.records.forEach { record ->
            val open = record.atMs == shown?.atMs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onShow(record.atMs) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stampOf(record.atMs) +
                            (if (record.fromLoadTest) " · тест" else " · спокій"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (open) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    )
                    Text(
                        text = "${formatDecimal(record.odometerKm, 0)} км · " +
                            "${formatDecimal(record.socPercent, 0)} % · " +
                            "${formatDecimal(record.batteryTempC, 0)} °C · " +
                            "розкид ${formatDecimal(record.spreadVolts * 1000, 0)} мВ" +
                            // Навантаження — лише в тестових записах: у простому
                            // замірі його немає, і нуль там читався б як «міряли
                            // без нагрузки», що неправда.
                            if (record.peakLoadKw > 0.0) {
                                " · до ${formatDecimal(record.peakLoadKw, 0)} кВт"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "видалити",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { onDelete(record.atMs) },
                )
            }
        }

        if (shown != null) {
            Text(
                text = "Натисніть на замір ще раз, щоб повернутися до живих комірок.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** «06.09 08:46» — коротко, бо порівнюють заміри в межах місяців, а не років. */
private fun stampOf(atMs: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(atMs))

@Composable
private fun StatItem(label: String, value: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, style = MaterialTheme.typography.bodySmall)
        Text(
            text = formatMeasurement(value, 3, "В"),
            fontSize = 13.sp,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun CompactCellCell(
    index: Int,
    cellsViewModel: CellsViewModel,
    cellData: CellData,
    manualCells: ManualCells,
    modifier: Modifier,
    loadColor: Color? = null,
    test: CellTestState? = null,
    /**
     * Текст боком: у модулях 6s дванадцять клітинок в рядок, і число в них по
     * горизонталі не влазить жодним читаним кеглем.
     */
    vertical: Boolean = false,
) {
    val canVoltage = cellData.cellVoltages.getOrElse(index) { 0.0 }
    val activeVoltage = if (canVoltage > 0.0) canVoltage else manualCells.voltageAt(index)

    var textValue by remember(activeVoltage) {
        mutableStateOf(if (activeVoltage > 0.0) formatDecimal(activeVoltage, 2) else "")
    }
    // ПОРОЖНЄ ЧИСЛО — ЦЕ НЕ ЧИСЛО. Коли в обраному режимі для цієї комірки нічого
    // немає, показуємо напругу в полі введення, а не порожню клітинку: збережений
    // простий замір (у ньому є напруги, але немає тесту) показував саме порожні.
    val reading = test?.let { cellTextOf(index, it).takeIf { text -> text.isNotEmpty() } }

    Box(
        modifier = modifier
            // Заливка від тесту під навантаженням, якщо він щось знайшов. Рамка
            // лишається на місці: колір тут доповнює число, а не замінює його.
            .let { base ->
                if (loadColor == null) base else base.background(loadColor, RoundedCornerShape(3.dp))
            }
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${index + 1}",
            fontSize = CELL_INDEX_SIZE,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(1.dp),
        )
        if (vertical) {
            // У ВУЗЬКІЙ КЛІТИНЦІ ПОЛЯ ВВЕДЕННЯ НЕМАЄ, і це не пропуск.
            //
            // Поле розкладається по ширині — двадцять вісім точок, — і число в
            // ньому обрізається до смужки незалежно від того, повернуте воно чи
            // ні: поворот міняє те, як текст намальовано, а не те, скільки місця
            // йому виміряли. Тому тут завжди текст, і завжди повернутий; правити
            // напруги руками є де — на рівній сітці.
            //
            // Повернутий текст НЕ обмежується шириною клітинки: місце йому дає
            // висота, а поворот відбувається вже після вимірювання.
            Text(
                text = reading ?: textValue,
                fontSize = CELL_VALUE_SIZE,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.rotate(-90f),
            )
        } else if (reading != null) {
            // Готове число з тесту: правити його руками немає сенсу, тож і поля
            // введення тут немає — просто текст.
            Text(
                text = reading,
                fontSize = CELL_VALUE_SIZE,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
            )
        } else {
            BasicTextField(
                value = textValue,
                onValueChange = { newValue ->
                    textValue = newValue
                    cellsViewModel.onManualVoltageEntered(index, newValue)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                // ТОЙ САМИЙ РОЗМІР, ЩО Й У РЕШТІ РЕЖИМІВ. Поле введення має свій
                // стиль, і поки він жив окремо, «Ввід» показував ті самі числа
                // помітно дрібнішими за «ХХ» чи «Під навант.» — на тому самому
                // екрані, тією самою сіткою.
                textStyle = TextStyle(
                    fontSize = CELL_VALUE_SIZE,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun CompactGridView(
    cellsViewModel: CellsViewModel,
    cellData: CellData,
    manualCells: ManualCells,
    test: CellTestState,
    paint: (Int) -> Color?,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(GRID_HEIGHT),
    ) {
        items(CellData.TOTAL_CELLS) { index ->
            CompactCellCell(
                index = index,
                cellsViewModel = cellsViewModel,
                cellData = cellData,
                manualCells = manualCells,
                modifier = Modifier.fillMaxWidth().height(GRID_CELL_HEIGHT),
                loadColor = paint(index),
                test = test,
            )
        }
    }
}

@Composable
private fun BlocksView(
    cellsViewModel: CellsViewModel,
    cellData: CellData,
    manualCells: ManualCells,
    test: CellTestState,
    paint: (Int) -> Color?,
) {
    val spacing = 1.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalColumns = 7
        val cellWidth = (maxWidth - spacing * (totalColumns - 1)) / totalColumns

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Порядок рядів — не порядок опитування: див. [CellLayout].
            CellLayout.blocks.forEach { block ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    CellLayout.runsOf(block).forEach { run ->
                        // Ряди по п'ять коротші за ряди по сім і притиснуті вліво:
                        // лівий край скрізь має лишатися верхом стопки.
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            run.forEach { index ->
                                CompactCellCell(
                                    index = index,
                                    cellsViewModel = cellsViewModel,
                                    cellData = cellData,
                                    manualCells = manualCells,
                                    modifier = Modifier.width(cellWidth).height(BLOCK_CELL_HEIGHT),
                                    // Колір і значення тут такі самі, як у сітці.
                                    // Без них друга вкладка показувала лише порожні
                                    // клітинки — рівно там, куди дивляться, коли
                                    // шукають, який бік пакета просів.
                                    loadColor = paint(index),
                                    test = test,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * ТЕСТ ПІД НАВАНТАЖЕННЯМ.
 *
 * Слабку комірку в спокої не видно: напруга в неї така сама, як у сусідів. Вона
 * проявляється під струмом — власний опір більший, тож просідає вона глибше за
 * решту. Тест і потрібен, щоб цю різницю виміряти, а не вгадати.
 *
 * ЯК КОРИСТУВАТИСЯ: натиснути «Почати», проїхати кілька хвилин ЗІ ЗМІННИМ
 * навантаженням — розгони й гальмування, а не рівний хід, — і натиснути «Спинити».
 * Рівний хід або стоянка дадуть проходи, з яких опір не виводиться, і тест про це
 * прямо скаже.
 *
 * ЧОМУ ТУТ ПОКАЗАНО РОЗМАХ СТРУМУ. Це єдине число, за яким видно, чи тест узагалі
 * набирає щось корисне. Дивитися на нього треба ПІД ЧАС тесту: тоді ще можна
 * розігнатися, а після — уже ні.
 */
@Composable
private fun LoadTestCard(
    test: CellTestState,
    onToggle: () -> Unit,
    onClear: () -> Unit,
) {
    val result = test.result

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "Тест під навантаженням", fontSize = 16.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onToggle, modifier = Modifier.weight(1f)) {
                Text(if (test.running) "Спинити тест" else "Почати тест")
            }
            if (test.hasSweeps && !test.running) {
                Button(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("Очистити")
                }
            }
        }

        if (!test.hasSweeps && !test.running) {
            Text(
                text = "Натисніть «Почати» і проїдьте кілька хвилин зі змінним " +
                    "навантаженням — розгони й гальмування. Рівний хід чи стоянка " +
                    "нічого не покажуть: слабка комірка видно лише під струмом.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        Text(
            text = "Проходів ${result.sweeps}, придатних ${result.steadySweeps}. " +
                "Розмах струму ${formatDecimal(result.currentSpreadA, 0)} А, " +
                "середня потужність ${formatDecimal(result.averagePowerKw, 1)} кВт.",
            style = MaterialTheme.typography.bodySmall,
        )

        // ПІД ЯКИМ НАВАНТАЖЕННЯМ МІРЯЛИ. Без цього числа мінімуми не значать
        // нічого: «3.41 В» під десятьма кіловатами й під сімдесятьма — два різні
        // висновки про ту саму комірку. Точність тут не потрібна, потрібен порядок.
        Text(
            text = "Найбільше навантаження за тест: " +
                "${formatDecimal(result.peakLoadKw, 0)} кВт — під ним і знято " +
                "найглибші просадки.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (result.note.isNotEmpty()) {
            Text(
                text = result.note,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ДВА ПОГЛЯДИ НА ОДНІ Й ТІ САМІ ДАНІ, поруч і навмисно.
        //
        // Опір точніший: він відповідає на питання «чим ця комірка відрізняється»,
        // і струм у ньому скорочується. Мінімум простіший і зрозуміліший: «ось ця
        // просіла найнижче», — і працює навіть тоді, коли розгонів не було. Зате
        // мінімуми різних комірок узяті в різні миті проходу, тож він грубіший.
        //
        // Який корисніший на практиці — покажуть живі тести, а не міркування. Тому
        // обидва списки видно одночасно: якщо вони називають ті самі комірки,
        // достатньо простішого; якщо різні — цікаве саме те, чим вони різні.
        Text(
            text = "Найгірші за опором: " + worstLine(result.worstByResistance) { verdict ->
                "+${formatDecimal(verdict.excessMilliOhm ?: 0.0, 2)} мОм"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Найгірші за мінімумом: " + worstLine(result.worstByMinimum) { verdict ->
                "${formatDecimal(verdict.minVolts, 3)} В"
            },
            style = MaterialTheme.typography.bodyMedium,
        )

    }
}

/**
 * Колір за градацією. Норма не фарбується взагалі: заливка на всіх дев'яноста
 * шести клітинках не виділяє нічого, а лише робить числа гіршими для читання.
 */
private fun colorOf(level: CellLevel): Color? = when (level) {
    CellLevel.Bad -> Color(0xFFFF6347)
    CellLevel.Alert -> Color(0xFFFF9F1C)
    CellLevel.Warn -> Color(0xFFFFC43D)
    CellLevel.Normal -> null
}

/** Назва режиму значень у вікні порогів — та сама, що на чипах над сіткою. */
private fun titleOf(mode: CellValueMode): String = when (mode) {
    CellValueMode.Entry -> "Ввід"
    CellValueMode.Rest -> "ХХ"
    CellValueMode.UnderLoad -> "Під навантаженням"
    CellValueMode.Deviation -> "Відхилення"
    CellValueMode.Resistance -> "Опір"
}

/**
 * Рядок під чипами: чим саме зараз пофарбовано і скільки комірок вибилося.
 *
 * Без нього заливка — загадка: жовтий і червоний нічого не означають, поки не
 * сказано, від чого відлік і з якої різниці починається колір.
 */
@Composable
private fun ColorLegend(
    unit: String,
    palette: CellPalette,
    counts: Map<CellLevel, Int>,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Мілівольти читаються цілими, мілооми — ні: слабка комірка вибивається
        // на десяті частки, і «0» замість «0.25» означало б інше.
        val decimals = if (unit == "мОм") 2 else 0
        val steps = palette.steps
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Колір — відставання від найкращої комірки: жовтий від " +
                    "${formatDecimal(steps[0], decimals)}, оранжевий від " +
                    "${formatDecimal(steps[1], decimals)}, червоний від " +
                    "${formatDecimal(steps[2], decimals)} $unit.",
                style = MaterialTheme.typography.bodySmall,
            )
            val warn = counts[CellLevel.Warn] ?: 0
            val alert = counts[CellLevel.Alert] ?: 0
            val bad = counts[CellLevel.Bad] ?: 0
            Text(
                text = if (warn + alert + bad == 0) {
                    "За порогами не вибилася жодна комірка."
                } else {
                    "Вибилося: жовтих $warn, оранжевих $alert, червоних $bad."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenSettings) { Text("Пороги") }
    }
}

/**
 * Вікно порогів для ОДНОГО режиму значень.
 *
 * Один режим за раз, а не всі п'ять списком: пороги в них живуть у різних
 * порядках величин, і поставлені поруч вони читалися б як помилка. Правиться той,
 * що зараз на екрані, — і результат видно одразу, не закриваючи вікна двічі.
 */
@Composable
private fun PaletteDialog(
    title: String,
    unit: String,
    palette: CellPalette,
    onDismiss: () -> Unit,
    onApply: (CellPalette) -> Unit,
) {
    val decimals = if (unit == "мОм") 2 else 0
    var warn by remember(palette) { mutableStateOf(formatDecimal(palette.warnAt, decimals)) }
    var alert by remember(palette) { mutableStateOf(formatDecimal(palette.alertAt, decimals)) }
    var bad by remember(palette) { mutableStateOf(formatDecimal(palette.badAt, decimals)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Пороги кольору · $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Від якого відставання від НАЙКРАЩОЇ комірки фарбувати, " +
                        "$unit. Найкращі в пакеті здорові за визначенням — вони й " +
                        "показують, на що ця хімія здатна на цьому заряді.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (unit == "мВ") {
                    // Без цього рядка пороги нижче 40 мВ виглядають розумно, а
                    // фарбують увесь пакет: різниці в один крок BMS не буває
                    // менше, і майже нічого не означає.
                    Text(
                        text = "BMS віддає напруги кроками по 20 мВ, тож різниця в " +
                            "один крок нічого не означає. Порог нижче 40 мВ " +
                            "пофарбує всі комірки, крім найкращої.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = warn,
                    onValueChange = { warn = it },
                    label = { Text("Жовтий від, $unit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = alert,
                    onValueChange = { alert = it },
                    label = { Text("Оранжевий від, $unit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bad,
                    onValueChange = { bad = it },
                    label = { Text("Червоний від, $unit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Нерозібране число не міняє нічого: порожній порог пофарбував
                    // би весь пакет, а це гірше, ніж не зберегти правку.
                    onApply(
                        CellPalette(
                            warnAt = parseDecimalInput(warn) ?: palette.warnAt,
                            alertAt = parseDecimalInput(alert) ?: palette.alertAt,
                            badAt = parseDecimalInput(bad) ?: palette.badAt,
                        ),
                    )
                },
            ) { Text("Зберегти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } },
    )
}

/**
 * МОДУЛІ 6s: як батарея стоїть під підлогою насправді.
 *
 * Шістнадцять модулів по шість комірок, двома рядами по вісім; правий ряд іде
 * знизу вгору — див. [CellLayout.sixCellRows]. Клітинки тут вузькі, бо їх у рядок
 * дванадцять, і саме тому високі: число в них написане боком.
 */
@Composable
private fun SixModulesView(
    cellsViewModel: CellsViewModel,
    cellData: CellData,
    manualCells: ManualCells,
    test: CellTestState,
    paint: (Int) -> Color?,
) {
    val spacing = 1.dp
    val moduleGap = 6.dp
    val rows = CellLayout.sixCellRows()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // ШИРИНУ ТРЕБА ВІДРАХУВАТИ ТОЧНО, інакше остання колонка зрізається.
        //
        // Проміжків тут два різних: між комірками всередині модуля і між самими
        // модулями. Спершу з ширини відніматься лише перші — і дванадцята колонка
        // не влазила рівно на розмір другого.
        val columns = CellLayout.SIX * 2
        val used = moduleGap + spacing * ((CellLayout.SIX - 1) * 2)
        val cellWidth = (maxWidth - used) / columns

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(moduleGap)) {
                    pair.forEach { module ->
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            module.forEach { index ->
                                CompactCellCell(
                                    index = index,
                                    cellsViewModel = cellsViewModel,
                                    cellData = cellData,
                                    manualCells = manualCells,
                                    modifier = Modifier
                                        .width(cellWidth)
                                        .height(SIX_CELL_HEIGHT),
                                    loadColor = paint(index),
                                    test = test,
                                    vertical = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Трійка найгірших одним рядком.
 *
 * Три, а не десять: список, у якому половина пакета, читається як «усе погано» і не
 * допомагає нічому. А порожній список чесніше назвати словами, ніж лишити порожнє
 * місце — воно виглядає як недомальований екран.
 */
private fun worstLine(cells: List<CellVerdict>, value: (CellVerdict) -> String): String {
    val top = cells.take(WORST_SHOWN)
    if (top.isEmpty()) return "поки нема з чого судити"
    return top.joinToString(", ") { "№${it.index + 1} (${value(it)})" }
}

private const val WORST_SHOWN = 3


/**
 * Перемикач того, що стоїть у клітинках сітки.
 *
 * Чотири різні числа з одного тесту, і кожне відповідає на своє питання. «Опір»
 * доступний лише тоді, коли струм за тест справді мінявся: показати шкалу, за якою
 * нічого не порахували, гірше, ніж не показати її взагалі.
 */
@Composable
private fun ValueModeChips(
    selected: CellValueMode,
    resistanceKnown: Boolean,
    onSelect: (CellValueMode) -> Unit,
) {
    val modes = listOf(
        CellValueMode.Entry to "Ввід",
        CellValueMode.Rest to "ХХ",
        CellValueMode.UnderLoad to "Під навант.",
        CellValueMode.Deviation to "Відхилення",
        CellValueMode.Resistance to "Опір",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modes.forEach { (mode, title) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                enabled = mode != CellValueMode.Resistance || resistanceKnown,
                label = { Text(title) },
            )
        }
    }
}

/**
 * Що написати в клітинці комірки.
 *
 * Порожньо означає «цього числа немає», і це чесніше за нуль: нуль вольт і
 * «не міряли» на екрані виглядали б однаково, а означають протилежне.
 */
private fun cellTextOf(index: Int, test: CellTestState): String {
    val verdict = test.result.cells.getOrNull(index) ?: return ""
    return when (test.valueMode) {
        CellValueMode.Entry -> ""
        CellValueMode.Rest -> if (verdict.restVolts > 0.0) formatDecimal(verdict.restVolts, 2) else ""
        CellValueMode.UnderLoad -> if (verdict.minVolts > 0.0) formatDecimal(verdict.minVolts, 2) else ""
        // Мілівольти, а не вольти: відхилення живуть у сотих частках вольта, і в
        // клітинці «-0.03» читається гірше за «-30».
        CellValueMode.Deviation -> formatDecimal(verdict.worstDeviationVolts * 1000.0, 0)
        CellValueMode.Resistance -> verdict.excessMilliOhm?.let { formatDecimal(it, 2) } ?: ""
    }
}
