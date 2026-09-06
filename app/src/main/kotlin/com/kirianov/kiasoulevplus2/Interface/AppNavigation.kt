// Навігація додатка: перемикання між екранами та запит дозволів Bluetooth.
// Стан і дії екрани беруть із GeneralData самостійно, тому сюди нічого передавати не треба.

package com.kirianov.kiasoulevplus2.Interface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
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
    //
    // ЧУЖЕ АВТО — ІНШИЙ КОЛІР, і це важливіше за обрив зв'язку. Обране руками авто
    // показує свої збережені дані, а числа з шини йдуть від тієї машини, до якої
    // ми під'єднані. Плутати ці два джерела, готуючи перепакування, — найдорожча
    // з можливих помилок, тож екран каже про це кольором, не чекаючи, поки
    // прочитають рядок.
    val state by GeneralData.state.collectAsState()
    val tint = when {
        state.garage.viewingOther -> OTHER_CAR_TINT
        !state.isConnected -> DISCONNECTED_TINT
        else -> Color.Transparent
    }

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
                    NameNewCarDialog(state.garage)

                    Column(modifier = Modifier.fillMaxSize()) {
                        if (state.garage.viewingOther) {
                            ViewingOtherCarBanner(
                                shown = state.garage.activeVin,
                                onBus = state.garage.detectedVin,
                            )
                        }
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
}

/**
 * Персиковий, і саме з прозорістю, а не суцільний: у темній темі суцільний
 * виглядав би засвіченим тлом, а так лишається теплим відтінком.
 */
private val DISCONNECTED_TINT = Color(0xFFFFCBA4).copy(alpha = 0.45f)

/**
 * Бузковий: відрізняється від персикового настільки, щоб їх не сплутати навіть
 * краєм ока, і не схожий на «помилку» — це не помилка, а свідомий перегляд.
 */
private val OTHER_CAR_TINT = Color(0xFFB39DDB).copy(alpha = 0.45f)

/**
 * НОВЕ АВТО ПРОСИТЬ ІМЕНІ.
 *
 * Машина заводиться сама, щойно назве VIN, — питати дозволу нема коли, людина за
 * кермом. А от імені без неї не взяти: хвіст VIN розрізняє машини, але нічого не
 * каже про те, яка з них яка, і в гаражі з двох авто це відчувається одразу.
 *
 * Питаємо один раз на появу авто. «Пізніше» закриває питання до наступного запуску:
 * повторювати його щохвилини означало б навчити натискати «Пізніше» не читаючи.
 */
@Composable
private fun NameNewCarDialog(garage: com.kirianov.kiasoulevplus2.Data.Garage) {
    val car = garage.active
    var skipped by remember { mutableStateOf("") }
    var name by remember(car.vin) { mutableStateOf("") }

    if (!garage.loaded || !car.known || car.name.isNotEmpty() || skipped == car.vin) return

    AlertDialog(
        onDismissRequest = { skipped = car.vin },
        title = { Text("Нове авто") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("VIN ...${car.vin.takeLast(6)}. Як його назвати?")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Soul EV") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    GeneralData.setCarName(name.trim())
                    skipped = car.vin
                },
                enabled = name.isNotBlank(),
            ) { Text("Назвати") }
        },
        dismissButton = {
            TextButton(onClick = { skipped = car.vin }) { Text("Пізніше") }
        },
    )
}

/**
 * Смужка вгорі: що показано й що на шині.
 *
 * Одного кольору мало. Колір каже «щось не так, як завжди», а цей рядок — що
 * саме, і без нього довелося б згадувати, який відтінок що означає.
 */
@Composable
private fun ViewingOtherCarBanner(shown: String, onBus: String) {
    Text(
        text = "Дивимось ...${shown.takeLast(6)}, на шині ...${onBus.takeLast(6)}. " +
            "Записи йдуть тільки в те авто, що на шині.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(OTHER_CAR_TINT)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
