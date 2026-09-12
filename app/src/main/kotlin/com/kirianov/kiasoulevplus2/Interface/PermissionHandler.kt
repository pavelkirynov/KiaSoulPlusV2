// Composable-компонент для декларативного запиту системних дозволів Android (Bluetooth, Location)


package com.kirianov.kiasoulevplus2.Interface

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun RequestBluetoothPermissions() {
    val permissionsToRequest = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        // З Android 13 сповіщення служби переднього плану без цього дозволу не видно.
        // Сама служба працює й без нього — невидимою лишається тільки картка.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Результат запиту (можна обробити за потреби)
    }

    // Запускаємо запит один раз при появі екрана
    LaunchedEffect(Unit) {
        launcher.launch(permissionsToRequest)
    }
}
