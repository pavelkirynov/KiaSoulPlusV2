// ====================================================================================
// ОГЛЯД АВТО (CarOverviewScreen)
//
// Те, що застосунок знає про машину поза батареєю. Поки цього небагато — усе, що
// приходить широкомовними кадрами, — і саме тому екран чесно каже, звідки взялося
// кожне число і чого тут ще немає.
//
// Порожній екран із написом «скоро буде» гірший за короткий список: короткий список
// одразу показує, з чим працювати далі, і куди складати нові системи.
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal

@Composable
fun CarOverviewScreen() {
    val state by GeneralData.state.collectAsState()
    val vehicle = state.vehicle
    val car = state.garage.active

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
                Text(text = "Авто", fontSize = 18.sp)

                Line("Назва", car.name.ifEmpty { "без назви" })
                if (car.known) Line("VIN", car.vin)
                Line(
                    "Пробіг",
                    if (vehicle.hasOdometer) "${formatDecimal(vehicle.odometerKm, 1)} км" else "—",
                )
                Line(
                    "Швидкість",
                    if (vehicle.hasSpeed) "${formatDecimal(vehicle.speedKmh, 0)} км/год" else "—",
                )
                Line("Запас за панеллю", if (vehicle.hasRange) "${vehicle.rangeKm} км" else "—")
                Line(
                    "За бортом",
                    if (vehicle.hasAmbientTemp) {
                        "${formatDecimal(vehicle.ambientTempC, 1)} °C"
                    } else {
                        "—"
                    },
                )
                Line(
                    "Клімат забирає",
                    if (vehicle.hasClimateExtra) {
                        "${formatDecimal(vehicle.climateExtraKm, 1)} км запасу"
                    } else {
                        "—"
                    },
                )

                Text(
                    text = "Ці числа приходять широкомовними кадрами: авто говорить їх само, " +
                        "без запиту. Прочерк означає, що потрібний кадр ще не потрапив у вікно " +
                        "прослуховування, а не що показника немає.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "Що далі", fontSize = 16.sp)
                Text(
                    text = "Числа вище перевірені журналами й живлять розрахунки. Усе інше, що " +
                        "вдалося перенести з таблиці SoulEVSpy, лежить на сусідній вкладці " +
                        "«Системи» — там кожне поле стоїть із вердиктом: приходить і схоже на " +
                        "правду, приходить і не схоже, або не приходить зовсім.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Нові системи шукаються на вкладці «Експерименти»: там видно всі " +
                        "кадри шини, а різниця між двома знімками показує, який із них " +
                        "відповідає за що.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
