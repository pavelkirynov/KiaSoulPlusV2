// ====================================================================================
// БАНЕР «ОБЕРІТЬ АВТО» (SelectCarBanner)
//
// Коли ні VIN, ні неперервність лічильників не впізнали авто, а машин у гаражі
// кілька — застосунок не знає, чиї числа, і облік із прогнозом стоять. Раніше про це
// був лише тихий рядок у Налаштуваннях. Тепер — помітний банер прямо там, де це
// болить: на Головній і на Прогнозі. Вибір у банері — це СВІДОМЕ ПІДТВЕРДЖЕННЯ «я в
// цій машині»: облік і навчання одразу вмикаються.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.CarIdentity
import com.kirianov.kiasoulevplus2.Data.Garage

/**
 * Банер вибору авто. Малюється сам вирішує, чи показуватись: мовчить, поки авто
 * впізнане, поки ще питаємо VIN, поки це перегляд чужого, або поки авто одне.
 *
 * [odometerKm]/[kwhIn]/[kwhOut] — живі числа з шини: за ними біля авто, чий відбиток
 * геть не збігається, показуємо тихе застереження «числа не схожі». Вибір усе одно
 * дозволено — вирішує людина.
 */
@Composable
fun SelectCarBanner(
    garage: Garage,
    connected: Boolean,
    odometerKm: Double,
    kwhIn: Double,
    kwhOut: Double,
    onConfirm: (String) -> Unit,
) {
    // Показуємось лише коли справді не знаємо, чиї числа: підключені, авто не
    // впізнане, VIN уже спитали (не блимаємо в перші секунди), це не перегляд
    // чужого, і є з чого обирати.
    if (!connected || garage.identified || garage.vinPending || garage.viewingOther) return
    if (garage.cars.size < 2) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "Не можу впізнати авто", fontSize = 18.sp)
            Text(
                text = "VIN не відповів, а лічильники не збіглися з жодним авто. Оберіть, у " +
                    "якому ви зараз, — інакше облік і прогноз стоять.",
                style = MaterialTheme.typography.bodySmall,
            )
            garage.cars.sortedByDescending { it.lastSeenAtMs }.forEach { car ->
                Button(
                    onClick = { onConfirm(car.vin) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(car.name.ifEmpty { "...${car.vin.takeLast(6)}" })
                }
                // Тихе застереження, коли в авто вже є відбиток, а живі числа на нього
                // геть не схожі: найпевніше це не воно, але останнє слово за людиною.
                if (car.hasFingerprint && !CarIdentity.continues(car, odometerKm, kwhIn, kwhOut)) {
                    Text(
                        text = "числа не схожі на це авто",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
