package com.example.kiasoulevplus2.Interface.screens.cells

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kiasoulevplus2.Data.CellData

object CellVoltagePrefs {
    private const val PREFS_NAME = "cell_voltage_prefs"
    const val CELL_COUNT = 96
    const val COLUMNS = 8

    val cellVoltages = mutableStateMapOf<Int, Double>()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (index in 0 until CELL_COUNT) {
            cellVoltages[index] = prefs.getFloat("cell_$index", 0f).toDouble()
        }
    }

    fun saveCell(context: Context, index: Int, value: Double) {
        cellVoltages[index] = value
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat("cell_$index", value.toFloat()).apply()
    }
}

private data class CellBlock(val startIndex: Int, val count: Int)

private val cellBlocks = listOf(
    CellBlock(0, 14),
    CellBlock(14, 10),
    CellBlock(24, 10),
    CellBlock(34, 14),
    CellBlock(48, 14),
    CellBlock(62, 10),
    CellBlock(72, 10),
    CellBlock(82, 14)
)

private enum class CellsViewMode { GRID, BLOCKS }

@Composable
fun CellsScreen(cellsViewModel: CellsViewModel) {
    val context = LocalContext.current
    var viewMode by remember { mutableStateOf(CellsViewMode.GRID) }

    LaunchedEffect(Unit) {
        CellVoltagePrefs.load(context)
    }

    val appState by cellsViewModel.uiState.collectAsState()
    val isLoading by cellsViewModel.isLoading.collectAsState()
    val cellData = appState.cells

    // Скидаємо прапорець завантаження, коли отримано дані з CAN
    LaunchedEffect(appState.inputBms.scanCellsRequested) {
        if (!appState.inputBms.scanCellsRequested && isLoading) {
            cellsViewModel.onCellsLoaded()
        }
    }

    // Мін/Макс/Дельта з CAN або з локально збережених значень
    val storedMin = CellVoltagePrefs.cellVoltages.values.filter { (it ?: 0.0) > 0.0 }.minOrNull() ?: 0.0
    val storedMax = CellVoltagePrefs.cellVoltages.values.filter { (it ?: 0.0) > 0.0 }.maxOrNull() ?: 0.0

    val minVoltage = if (cellData.minVoltage > 0.0) cellData.minVoltage else storedMin
    val maxVoltage = if (cellData.maxVoltage > 0.0) cellData.maxVoltage else storedMax
    val delta = if (cellData.deltaVoltage > 0.0) cellData.deltaVoltage else (if (maxVoltage > 0.0 && minVoltage > 0.0) maxVoltage - minVoltage else 0.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(label = "Мін.", value = minVoltage)
            StatItem(label = "Макс.", value = maxVoltage)
            StatItem(label = "ΔV", value = delta)
        }

        Spacer(modifier = Modifier.height(6.dp))

        Button(
            onClick = { 
                cellsViewModel.onRequestReadCells()
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Зчитую..." else "Зчитати комірки з авто")
        }

        Spacer(modifier = Modifier.height(6.dp))

        val debugString = appState.debugInfo.ifEmpty { 
            if (cellData.debugInfo.isEmpty()) "Логи порожні." else cellData.debugInfo 
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text(
                text = "CAN / ELM Log:",
                color = Color(0xFFAAAAAA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 100.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = debugString,
                    color = Color(0xFF00FF66),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = (viewMode == CellsViewMode.GRID),
                onClick = { viewMode = CellsViewMode.GRID },
                label = { Text("Сітка (8x12)") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = (viewMode == CellsViewMode.BLOCKS),
                onClick = { viewMode = CellsViewMode.BLOCKS },
                label = { Text("По блоках ВВБ") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(modifier = Modifier.weight(1f, fill = false)) {
            when (viewMode) {
                CellsViewMode.GRID -> CompactGridView(context, cellData)
                CellsViewMode.BLOCKS -> BlocksView(context, cellData)
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: Double) {
    val formattedValue = try {
        String.format("%.3f В", value)
    } catch (e: Exception) {
        "$value В"
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, style = MaterialTheme.typography.bodySmall)
        Text(text = formattedValue, fontSize = 13.sp, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun CompactCellCell(index: Int, context: Context, cellData: CellData, modifier: Modifier) {
    val canVoltage = if (index < cellData.cellVoltages.size) cellData.cellVoltages[index] else 0.0
    val storedVoltage = CellVoltagePrefs.cellVoltages[index] ?: 0.0
    val activeVoltage = if (canVoltage > 0.0) canVoltage else storedVoltage

    var textValue by remember(activeVoltage) {
        mutableStateOf(if (activeVoltage > 0.0) String.format("%.2f", activeVoltage) else "")
    }

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${index + 1}",
            fontSize = 6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(1.dp)
        )
        BasicTextField(
            value = textValue,
            onValueChange = { newValue ->
                textValue = newValue
                val parsed = newValue.toDoubleOrNull()
                if (parsed != null) {
                    CellVoltagePrefs.saveCell(context, index, parsed)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = TextStyle(fontSize = 8.sp, textAlign = TextAlign.Center),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
        )
    }
}

@Composable
private fun CompactGridView(context: Context, cellData: CellData) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(CellVoltagePrefs.COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
    ) {
        items(CellVoltagePrefs.CELL_COUNT) { index ->
            CompactCellCell(index = index, context = context, cellData = cellData, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun BlocksView(context: Context, cellData: CellData) {
    val cellHeight = 22.dp
    val spacing = 1.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalColumns = 7
        val cellWidth = (maxWidth - spacing * (totalColumns - 1)) / totalColumns

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            cellBlocks.forEach { block ->
                val columns = if (block.count == 14) 7 else 5
                val rows = (block.count + columns - 1) / columns

                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    for (row in 0 until rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            for (col in 0 until columns) {
                                val offset = row * columns + col
                                if (offset < block.count) {
                                    CompactCellCell(
                                        index = block.startIndex + offset,
                                        context = context,
                                        cellData = cellData,
                                        modifier = Modifier
                                            .width(cellWidth)
                                            .height(cellHeight)
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
