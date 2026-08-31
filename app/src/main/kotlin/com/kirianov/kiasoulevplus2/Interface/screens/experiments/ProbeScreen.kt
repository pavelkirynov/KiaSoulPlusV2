// ====================================================================================
// ЕКРАН «ЕКСПЕРИМЕНТИ» (ProbeScreen)
//
// Надсилає в шину довільну команду читання і показує відповідь: сирий текст,
// байти з індексами і підбір кандидатів. Саме тут зсув величини знаходиться
// звіркою з приладовою панеллю, а не вгадуванням.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.experiments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.MonitorCapture
import com.kirianov.kiasoulevplus2.Data.ProbeResult
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.frames.MonitorLineParser

/** Команди, з яких варто почати пошук. Усі — лише читання. */
private val presets = listOf(
    // 7C6 / 22 B0 01 прибрано: на цьому авто відповідь порожня.
    Preset("Пробіг: OBD 01 A6", "7DF", "01 A6"),
    Preset("Щиток 21 01", "7C6", "21 01"),
    Preset("Щиток 22 B0 02", "7C6", "22 B0 02"),
    Preset("VMCU 21 01", "7E2", "21 01"),
    Preset("Швидкість OBD", "7DF", "01 0D"),
    Preset("BMS (перевірка зв'язку)", "7E4", "21 01"),
)

private data class Preset(val label: String, val header: String, val command: String)

@Composable
fun ProbeScreen(probeViewModel: ProbeViewModel) {
    val state by probeViewModel.uiState.collectAsState()

    var header by remember { mutableStateOf("7C6") }
    var command by remember { mutableStateOf("22 B0 02") }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Ручний запит до шини", fontSize = 18.sp)
        Text(
            text = "Дозволені лише сервіси читання: 01, 02, 09, 19, 21, 22. " +
                "Запис у блоки авто застосунок не робить.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = header,
                onValueChange = { header = it },
                label = { Text("Заголовок") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Команда") },
                singleLine = true,
                modifier = Modifier.weight(1.4f),
            )
        }

        Button(
            onClick = { error = probeViewModel.onSend(header, command) },
            enabled = state.isConnected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isConnected) "Надіслати" else "Спершу підключіться до авто")
        }

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedTextField(
            value = target,
            onValueChange = {
                target = it.filter(Char::isDigit)
                probeViewModel.onTargetChanged(target)
            },
            label = { Text("Відоме значення, напр. пробіг зі щитка") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "Якщо число є у відповіді, застосунок покаже, на якому зсуві воно лежить.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(text = "Швидкий вибір", style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            presets.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { preset ->
                        AssistChip(
                            onClick = {
                                header = preset.header
                                command = preset.command
                                error = null
                            },
                            label = { Text(preset.label, fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Text(text = "Відповіді", style = MaterialTheme.typography.titleSmall)
        if (state.probe.results.isEmpty()) {
            Text(text = "Ще нічого не надсилали.", style = MaterialTheme.typography.bodyMedium)
        }
        state.probe.results.forEach { ResultCard(it) }

        BroadcastCard(monitor = state.can.monitor, vehicle = state.vehicle)
    }
}

/**
 * Сирі рядки останнього вікна монітора поруч із тим, що з них вийшло.
 *
 * Це головний спосіб звірити пробіг зі щитком: якщо число не збігається,
 * видно і кадр, з якого воно рахувалося, і чи взагалі кадр 4F0 доїхав.
 */
@Composable
private fun BroadcastCard(monitor: MonitorCapture?, vehicle: VehicleData) {
    Text(text = "Широкомовні кадри", style = MaterialTheme.typography.titleSmall)

    if (monitor == null) {
        Text(
            text = "Шину ще не слухали. Кадри знімаються раз на кілька циклів опитування.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (vehicle.hasOdometer) {
                    "Пробіг з кадру 4F0: ${vehicle.odometerKm} км"
                } else {
                    "Кадр 4F0 ще не розібрано"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            // Показуються ВСІ ID, а не лише відомі: саме так на екрані знаходяться
            // нові кадри — наприклад лічильники поїздок A і B, яких ще немає в декодері.
            val frames = monitor.lines
                .mapNotNull { MonitorLineParser.parse(it, monitor.filterId) }
                .distinctBy { it.id }
                .sortedBy { it.id }

            MonoBlock(
                title = "Фільтр ${monitor.filterId}: " +
                    "${frames.size} різних ID у ${monitor.lines.size} рядках",
                text = frames.take(FRAMES_SHOWN).joinToString("\n") { frame ->
                    frame.id + "  " + frame.bytes.joinToString(" ") { "%02X".format(it) }
                }.ifEmpty { "жодного кадру не розібрано" },
            )

            MonoBlock(
                title = "Сирі рядки, перші $RAW_LINES_SHOWN",
                text = monitor.lines.take(RAW_LINES_SHOWN).joinToString("\n")
                    .ifEmpty { "порожньо" },
            )
        }
    }
}

/** Більше рядків на екрані все одно не прочитати, а гальмує помітно. */
private const val RAW_LINES_SHOWN = 20

/** Скільки різних ID показувати: на шині їх десятки. */
private const val FRAMES_SHOWN = 25

@Composable
private fun ResultCard(result: ProbeResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${result.header} → ${result.command}",
                style = MaterialTheme.typography.titleSmall,
            )

            result.error?.let {
                Text(
                    text = "Помилка: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            MonoBlock(
                title = "Сира відповідь",
                text = result.rawResponse.replace('\r', ' ').replace('\n', ' ').trim()
                    .ifEmpty { "[порожньо]" },
            )

            if (!result.hasBytes) return@Column

            MonoBlock(
                title = "Байти (${result.bytes.size})",
                text = result.bytes.withIndex().joinToString(" ") { (index, byte) ->
                    "$index:%02X".format(byte)
                },
            )

            if (result.matches.isNotEmpty()) {
                MonoBlock(
                    title = "ЗНАЙДЕНО відоме значення",
                    text = result.matches.joinToString("\n") { match ->
                        val order = if (match.bigEndian) "прямий" else "зворотний"
                        val scale = if (match.divisor == 1) "" else ", масштаб 1/${match.divisor}"
                        "зсув ${match.index}, ${match.width} б, порядок $order$scale"
                    },
                )
            }

            MonoBlock(
                title = "Схоже на пробіг (зсув × ширина = значення)",
                text = if (result.odometerCandidates.isEmpty()) {
                    "нічого в межах 1..2 000 000"
                } else {
                    result.odometerCandidates.joinToString("\n") {
                        "зсув ${it.index}, ${it.width} б -> ${it.value} км"
                    }
                },
            )
        }
    }
}

@Composable
private fun MonoBlock(title: String, text: String) {
    Column {
        Text(text = title, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(2.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(6.dp))
                .padding(8.dp),
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
