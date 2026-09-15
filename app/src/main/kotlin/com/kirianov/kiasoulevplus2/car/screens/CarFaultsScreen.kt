// ====================================================================================
// ПОМИЛКИ БЛОКІВ (CarFaultsScreen)
//
// Перший екран розділу «Авто» і найпряміша відповідь, яку взагалі вміє дати шина:
// що саме блок вважає несправним. Не байти, які треба тлумачити, а код і його стан.
//
// ПОКАЗУЄМО Й ТИХ, ХТО ПРОМОВЧАВ. Список блоків — це здогади про адреси, а не
// паспорт машини. Блок, який не відповів, і блок, у якого немає помилок, — різні
// відповіді, і зливати їх в одне «все гаразд» означало б обіцяти те, чого ми не
// перевіряли.
//
// СТЕРТИ ПОМИЛКИ ЗАСТОСУНОК НЕ ВМІЄ. Це свідомо: стерта помилка — не полагоджена
// машина, а загублена підказка. Та й служба стирання — це запис у блок, а сюди
// ходять лише читання.
// ====================================================================================

package com.kirianov.kiasoulevplus2.car.screens

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.EcuFaults
import com.kirianov.kiasoulevplus2.Data.Fault
import com.kirianov.kiasoulevplus2.Data.GeneralData

@Composable
fun CarFaultsScreen() {
    val state by GeneralData.state.collectAsState()
    val faults = state.faults

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Помилки блоків", fontSize = 18.sp)

                Button(
                    onClick = { GeneralData.requestFaultScan() },
                    enabled = state.isConnected && !faults.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            faults.running -> "Опитую блоки..."
                            !state.isConnected -> "Спершу підключіться до авто"
                            else -> "Опитати блоки"
                        },
                    )
                }

                if (faults.running && faults.total > 0) {
                    LinearProgressIndicator(
                        progress = { faults.done.toFloat() / faults.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${faults.done} із ${faults.total}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(text = summaryOf(faults.results, faults.hasResults, faults.running))

                Text(
                    text = "Питаємо кожен блок його ж адресою, і двічі: новою мовою " +
                        "(19 02) і старою (18 02). Половина блоків цієї машини на нову " +
                        "відповідає «не знаю такої послуги», а на 21 01 відповідає " +
                        "залюбки — тож стару перевіряємо теж. Обидва запити — читання: " +
                        "нічого в машині не міняється. Стирати помилки застосунок не вміє " +
                        "навмисно — стерта помилка це не полагоджена машина, а загублена " +
                        "підказка.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Спершу ті, у кого щось є: заради них сюди й заходять.
        faults.results
            .sortedByDescending { it.faults.size }
            .forEach { EcuCard(it) }
    }
}

private fun summaryOf(results: List<EcuFaults>, hasResults: Boolean, running: Boolean): String {
    if (!hasResults) {
        return if (running) {
            "Опитування триває."
        } else {
            "Ще не питали. Опитування дев'яти блоків триває близько десяти секунд."
        }
    }
    val answered = results.count { it.answered }
    val codes = results.sumOf { it.faults.size }
    val active = results.sumOf { it.active }
    return when {
        codes == 0 -> "Відповіли $answered блоки з ${results.size}, помилок у них немає."
        active > 0 -> "Кодів $codes, з них активних зараз $active. Відповіли $answered із ${results.size}."
        else -> "Кодів $codes, усі — сліди в пам'яті. Відповіли $answered із ${results.size}."
    }
}

@Composable
private fun EcuCard(result: EcuFaults) {
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
                Text(text = result.ecu.name, fontSize = 16.sp)
                Text(
                    text = result.ecu.header,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (result.note.isNotEmpty()) {
                Text(
                    text = result.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.answered) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }

            result.faults.forEach { FaultRow(it) }
        }
    }
}

@Composable
private fun FaultRow(fault: Fault) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fault.code,
            fontSize = 16.sp,
            // Живу несправність видно кольором: у списку з десятка кодів рядок
            // стану читають не всі, а колір помічають одразу.
            color = if (fault.failingNow) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            text = fault.stateText + if (fault.warningLight) " · лампа" else "",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
