// Навігація додатка: перемикання між екранами та запит дозволів Bluetooth.
// Стан і дії екрани беруть із GeneralData самостійно, тому сюди нічого передавати не треба.

package com.kirianov.kiasoulevplus2.Interface

import androidx.compose.foundation.background
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Interface.screens.cells.CellsScreen
import com.kirianov.kiasoulevplus2.Interface.screens.cells.CellsViewModel
import com.kirianov.kiasoulevplus2.Interface.screens.experiments.ProbeScreen
import com.kirianov.kiasoulevplus2.Interface.screens.experiments.ProbeViewModel
import com.kirianov.kiasoulevplus2.Interface.screens.main.MainScreen
import com.kirianov.kiasoulevplus2.Interface.screens.prediction.PredictionScreen
import com.kirianov.kiasoulevplus2.Interface.screens.prediction.PredictionViewModel
import com.kirianov.kiasoulevplus2.Interface.screens.settings.SettingsScreen
import com.kirianov.kiasoulevplus2.Interface.screens.settings.SettingsViewModel

@Composable
fun AppNavigation() {
    RequestBluetoothPermissions()

    var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }

    // Обрив зв'язку видно за кольором, не вчитуючись у рядок статусу: за кермом
    // читати нема коли. Фарбуємо тут, а не на головному екрані, бо втрата
    // зв'язку однаково стосується всіх сторінок — на «Прогнозі» навіть більше,
    // ніж на «Головній»: там числа живуть довше й виглядають свіжими.
    val connected by GeneralData.state.collectAsState()
    val tint = if (connected.isConnected) Color.Transparent else DISCONNECTED_TINT

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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tint),
                ) {
                    when (currentScreen) {
                        AppScreen.MAIN -> MainScreen()
                        AppScreen.PREDICTION ->
                            PredictionScreen(predictionViewModel = viewModel<PredictionViewModel>())
                        AppScreen.CELLS -> CellsScreen(cellsViewModel = viewModel<CellsViewModel>())
                        AppScreen.EXPERIMENTS -> ProbeScreen(probeViewModel = viewModel<ProbeViewModel>())
                        AppScreen.SETTINGS ->
                            SettingsScreen(settingsViewModel = viewModel<SettingsViewModel>())
                    }
                }
            }
        }
    }
}

/**
 * Персиковий, і саме з прозорістю, а не суцільний: у темній темі суцільний
 * виглядав би засвіченим тлом, а так лишається теплим відтінком.
 */
private val DISCONNECTED_TINT = Color(0xFFFFCBA4).copy(alpha = 0.45f)
