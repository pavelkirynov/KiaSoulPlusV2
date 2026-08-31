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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.ProbeResult

/** Команди, з яких варто почати пошук. Усі — лише читання. */
private val presets = listOf(
    Preset("Пробіг: щиток", "7C6", "22 B0 02"),
    Preset("Пробіг: щиток, варіант", "7C6", "22 B0 01"),
    Preset("VMCU", "7E2", "21 01"),
    Preset("Швидкість OBD", "7E0", "01 0D"),
    Preset("BMS (перевірка зв'язку)", "7E4", "21 01"),
)

private data class Preset(val label: String, val header: String, val command: String)

@Composable
fun ProbeScreen(probeViewModel: ProbeViewModel) {
    val state by probeViewModel.uiState.collectAsState()

    var header by remember { mutableStateOf("7C6") }
    var command by remember { mutableStateOf("22 B0 02") }
    var error by remember { mutableStateOf<String?>(null) }

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
    }
}

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
