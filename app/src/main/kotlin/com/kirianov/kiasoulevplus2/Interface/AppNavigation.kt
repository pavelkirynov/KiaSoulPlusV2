// Навігація додатка: перемикання між екранами та запит дозволів Bluetooth.
// Стан і дії екрани беруть із GeneralData самостійно, тому сюди нічого передавати не треба.

package com.kirianov.kiasoulevplus2.Interface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirianov.kiasoulevplus2.Interface.screens.cells.CellsScreen
import com.kirianov.kiasoulevplus2.Interface.screens.cells.CellsViewModel
import com.kirianov.kiasoulevplus2.Interface.screens.experiments.ProbeScreen
import com.kirianov.kiasoulevplus2.Interface.screens.experiments.ProbeViewModel
import com.kirianov.kiasoulevplus2.Interface.screens.main.MainScreen

@Composable
fun AppNavigation() {
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
                            icon = { },
                        )
                    }
                }
            },
        ) { paddingValues ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                when (currentScreen) {
                    AppScreen.MAIN -> MainScreen()
                    AppScreen.CELLS -> CellsScreen(cellsViewModel = viewModel<CellsViewModel>())
                    AppScreen.EXPERIMENTS -> ProbeScreen(probeViewModel = viewModel<ProbeViewModel>())
                    AppScreen.SETTINGS -> ScreenPlaceholder("Калібрування")
                }
            }
        }
    }
}

@Composable
private fun ScreenPlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, fontSize = 18.sp)
    }
}
