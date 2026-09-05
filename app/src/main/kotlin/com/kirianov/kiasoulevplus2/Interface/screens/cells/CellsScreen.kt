// ====================================================================================
// ЕКРАН КОМІРОК (CellsScreen)
//
// Показує 96 комірок сіткою або за реальними блоками ВВБ, лог ELM327 та кнопку
// зчитування. Значення, зчитані з авто, мають пріоритет; вручну введені зберігаються
// в SharedPreferences і показуються, доки з машини нічого не прийшло.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.cells

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.CellColorMode
import com.kirianov.kiasoulevplus2.Data.CellHealth
import com.kirianov.kiasoulevplus2.Data.CellValueMode
import com.kirianov.kiasoulevplus2.Data.CellVerdict
import com.kirianov.kiasoulevplus2.Data.CellTestState
import com.kirianov.kiasoulevplus2.Data.ManualCells
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import com.kirianov.kiasoulevplus2.tools.format.formatMeasurement

private const val GRID_COLUMNS = 8

/** Реальна розбивка батареї Kia Soul EV на блоки. */
private data class CellBlock(val startIndex: Int, val count: Int)

private val cellBlocks = listOf(
    CellBlock(0, 14), CellBlock(14, 10), CellBlock(24, 10), CellBlock(34, 14),
    CellBlock(48, 14), CellBlock(62, 10), CellBlock(72, 10), CellBlock(82, 14),
)

private enum class CellsViewMode { GRID, BLOCKS }

@Composable
fun CellsScreen(cellsViewModel: CellsViewModel) {
    var viewMode by remember { mutableStateOf(CellsViewMode.GRID) }

    val appState by cellsViewModel.uiState.collectAsState()
    val cellData = appState.cells
    val manualCells = appState.manualCells

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
            onMode = cellsViewModel::onColorModeChange,
        )

        Spacer(modifier = Modifier.height(6.dp))

        CanLog(
            text = cellData.debugInfo.ifEmpty { appState.debugInfo.ifEmpty { "Логи порожні." } },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = viewMode == CellsViewMode.GRID,
                onClick = { viewMode = CellsViewMode.GRID },
                label = { Text("Сітка (8x12)") },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = viewMode == CellsViewMode.BLOCKS,
                onClick = { viewMode = CellsViewMode.BLOCKS },
                label = { Text("По блоках ВВБ") },
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

        when (viewMode) {
            CellsViewMode.GRID -> CompactGridView(cellsViewModel, cellData, manualCells, appState.cellTest)
            CellsViewMode.BLOCKS -> BlocksView(cellsViewModel, cellData, manualCells)
        }
    }
}

@Composable
private fun CanLog(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = "CAN / ELM Log:",
            color = Color(0xFFAAAAAA),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 100.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = text,
                color = Color(0xFF00FF66),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 15.sp,
            )
        }
    }
}

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
) {
    val canVoltage = cellData.cellVoltages.getOrElse(index) { 0.0 }
    val activeVoltage = if (canVoltage > 0.0) canVoltage else manualCells.voltageAt(index)

    var textValue by remember(activeVoltage) {
        mutableStateOf(if (activeVoltage > 0.0) formatDecimal(activeVoltage, 2) else "")
    }
    val reading = if (test == null || test.valueMode == CellValueMode.Entry) null else cellTextOf(index, test)

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
            fontSize = 6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(1.dp),
        )
        if (reading != null) {
            // Готове число з тесту: правити його руками немає сенсу, тож і поля
            // введення тут немає — просто текст.
            Text(
                text = reading,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp),
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
                textStyle = TextStyle(fontSize = 8.sp, textAlign = TextAlign.Center),
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
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
    ) {
        items(CellData.TOTAL_CELLS) { index ->
            CompactCellCell(
                index = index,
                cellsViewModel = cellsViewModel,
                cellData = cellData,
                manualCells = manualCells,
                modifier = Modifier.size(34.dp),
                loadColor = loadColorOf(index, test),
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
) {
    val cellHeight = 22.dp
    val spacing = 1.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalColumns = 7
        val cellWidth = (maxWidth - spacing * (totalColumns - 1)) / totalColumns

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            cellBlocks.forEach { block ->
                val columns = if (block.count == 14) 7 else 5
                val rows = (block.count + columns - 1) / columns

                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    for (row in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            for (col in 0 until columns) {
                                val offset = row * columns + col
                                if (offset < block.count) {
                                    CompactCellCell(
                                        index = block.startIndex + offset,
                                        cellsViewModel = cellsViewModel,
                                        cellData = cellData,
                                        manualCells = manualCells,
                                        modifier = Modifier.width(cellWidth).height(cellHeight),
                                    )
                                }
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
    onMode: (CellColorMode) -> Unit,
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = test.colorMode == CellColorMode.Resistance,
                onClick = { onMode(CellColorMode.Resistance) },
                label = { Text("Фарбувати за опором") },
            )
            FilterChip(
                selected = test.colorMode == CellColorMode.Minimum,
                onClick = { onMode(CellColorMode.Minimum) },
                label = { Text("за мінімумом") },
            )
        }
    }
}

/**
 * Колір комірки за результатом тесту, або null, коли фарбувати нема за чим.
 *
 * Фарбуємо за ОПОРОМ, а не за вольтами. У спокої напруга слабкої комірки нормальна,
 * і розфарбувати за нею означало б показати різнобій там, де його немає, і не
 * показати там, де він є.
 */
private fun loadColorOf(index: Int, test: CellTestState): Color? {
    val verdict = test.result.cells.getOrNull(index) ?: return null
    val health = when (test.colorMode) {
        CellColorMode.Resistance -> verdict.health
        CellColorMode.Minimum -> verdict.minHealth
    }
    return when (health) {
        CellHealth.Critical -> Color(0xFFFF6347)
        CellHealth.Weak -> Color(0xFFFFC43D)
        else -> null
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
