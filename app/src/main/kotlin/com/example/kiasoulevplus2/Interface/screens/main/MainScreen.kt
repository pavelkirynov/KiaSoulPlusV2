// UI головного екрана додатка (MainScreen).
// Відображає статус підключення та параметри ВВБ із GeneralData.state.

package com.example.kiasoulevplus2.Interface.screens.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@Composable
fun MainScreen(
    onConnectClick: () -> Unit = {},
    mainViewModel: MainViewModel = viewModel()
) {
    val state by mainViewModel.uiState.collectAsState()
    val bms = state.bms

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Блок керування підключенням
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { mainViewModel.onConnectClick(onConnectClick) }
                    ) {
                        Text(if (state.isConnected) "Відключити" else "Підключити")
                    }

                    Text(
                        text = if (state.isConnected) "З'єднано" else "Відключено",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                Text(
                    text = "Статус: ${state.debugInfo}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Основні параметри ВВБ
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "Показники батареї", fontSize = 18.sp)
                
                val powerKw = (bms.batteryVoltage * bms.batteryCurrent) / 1000f
                val socText = if (bms.displaySoc >= 0) "${String.format(Locale.US, "%.1f", bms.displaySoc)}%" else "--"
                
                Text(text = "Заряд (SOC): $socText", fontSize = 16.sp)
                Text(text = "Напруга ВВБ: ${String.format(Locale.US, "%.1f", bms.batteryVoltage)} В", fontSize = 16.sp)
                Text(text = "Струм ВВБ: ${String.format(Locale.US, "%.1f", bms.batteryCurrent)} А", fontSize = 16.sp)
                Text(text = "Потужність: ${String.format(Locale.US, "%.2f", powerKw)} кВт", fontSize = 16.sp)
                Text(text = "Температура ВВБ: ${String.format(Locale.US, "%.1f", bms.batteryTempC)} °C", fontSize = 16.sp)
            }
        }
    }
}
