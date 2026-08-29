// Головна навігаційна система додатку: перемикання між екранами (Main, Cells, Experiments, Settings),
// запит дозволів Bluetooth та передача подій підключення до MainScreen.

package com.example.kiasoulevplus2.Interface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kiasoulevplus2.Interface.screens.cells.CellsScreen
import com.example.kiasoulevplus2.Interface.screens.cells.CellsViewModel
import com.example.kiasoulevplus2.Interface.screens.main.MainScreen

@Composable
fun AppNavigation(
    connectionStatus: String,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnectClick: () -> Unit
) {
    // Автоматичний запит дозволів Bluetooth при старті
    RequestBluetoothPermissions()

    var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    AppScreen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            label = { Text(screen.title) },
                            icon = { }
                        )
                    }
                }
            }
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (currentScreen) {
                    AppScreen.MAIN -> MainScreen(onConnectClick = onConnectClick)
                    AppScreen.CELLS -> {
                        val cellsViewModel: CellsViewModel = viewModel()
                        CellsScreen(cellsViewModel = cellsViewModel)
                    }
                    AppScreen.EXPERIMENTS -> ScreenPlaceholder("Експерименти")
                    AppScreen.SETTINGS -> ScreenPlaceholder("Калібрування")
                }
            }
        }
    }
}

@Composable
private fun ScreenPlaceholder(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 18.sp)
    }
}
