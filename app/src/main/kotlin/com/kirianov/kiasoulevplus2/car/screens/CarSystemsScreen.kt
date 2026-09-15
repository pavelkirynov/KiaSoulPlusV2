// ====================================================================================
// ПЕРЕНЕСЕНЕ З SOULEVSPY: ЩО ПРАЦЮЄ (CarSystemsScreen)
//
// Один екран на всі числа, взяті з таблиці SoulEVSpy, з вердиктом на кожному.
// Список і вердикти рахує [PortedValues]; тут — лише як це показати.
//
// ЦЕ ЕКРАН ПЕРЕВІРКИ, А НЕ ПРИЛАДОВА ПАНЕЛЬ. Тому він однаково детально показує і
// те, що працює, і те, що ні: рівно за цим списком і буде видно, що з
// перенесеного лишиться в застосунку, а що піде геть. Гарний вигляд — окремим
// кроком, коли стане ясно, що саме показувати.
// ====================================================================================

package com.kirianov.kiasoulevplus2.car.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirianov.kiasoulevplus2.Data.GeneralData

@Composable
fun CarSystemsScreen() {
    val state by GeneralData.state.collectAsState()
    val groups = PortedValues.groups(state)
    val all = groups.flatMap { it.values }

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
                Text(text = "Перенесене з SoulEVSpy", fontSize = 18.sp)
                Text(
                    text = "Працює: ${all.count { it.state == PortedState.Working }}   " +
                        "Не схоже на правду: ${all.count { it.state == PortedState.Suspect }}   " +
                        "Не приходило: ${all.count { it.state == PortedState.Missing }}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Числа взяті з чужої програми, писаної під рідну батарею 27 кВт·год, " +
                        "тож кожне тут із вердиктом. Вердикт — не «на око»: він рахується з " +
                        "суперечності, яку видно в самих даних. Дві межі потужності не бувають " +
                        "рівні, вісім температур модулів не бувають однакові разом із межами " +
                        "пакета, а швидкість із трьох різних кадрів мусить сходитися.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Прочерк означає «кадр ще не приходив», а не «показника немає»: " +
                        "нові кадри стоять у черзі прослуховування рідко, раз на одну-три " +
                        "хвилини, щоб не заважати пробігу й SOC.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        groups.forEach { group -> GroupCard(group) }
    }
}

@Composable
private fun GroupCard(group: PortedGroup) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = group.title, fontSize = 16.sp)
                Text(
                    text = group.source,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            group.values.forEach { value -> ValueRow(value) }
        }
    }
}

/**
 * Один рядок: кружечок стану, назва, значення — і під ними причина вердикту.
 *
 * Причина стоїть у рядку завжди, коли вона є, а не під питанням: саме вона й
 * відповідає на «чому це число не годиться», і ховати її під натискання означало б
 * зробити екран непридатним за призначенням.
 */
@Composable
private fun ValueRow(value: PortedValue) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(colorOf(value.state), CircleShape),
                )
                Text(text = value.label, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = value.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        if (value.note.isNotEmpty()) {
            Text(
                text = value.note,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/**
 * Кольори ті самі три, що й на сітці комірок: зелений — працює, оранжевий —
 * прийшло, але не схоже, сірий — не приходило.
 */
private fun colorOf(state: PortedState): Color = when (state) {
    PortedState.Working -> Color(0xFF2E7D32)
    PortedState.Suspect -> Color(0xFFEF6C00)
    PortedState.Missing -> Color(0xFF9E9E9E)
}
