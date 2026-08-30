// ====================================================================================
// ГОЛОВНА АКТИВНІСТЬ ДОДАТКА (MainActivity)
//
// Точка входу: створює менеджери з'єднання та запускає Compose-UI.
// Стан підключення читається безпосередньо з GeneralData всередині екранів,
// тому Activity лише передає далі дію «підключитися / відключитися».
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Interface.AppNavigation
import com.kirianov.kiasoulevplus2.services.bluetooth.ConnectionManager
import com.kirianov.kiasoulevplus2.services.bluetooth.ElmBluetoothManager

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { ElmBluetoothManager() }
    private val connectionManager by lazy { ConnectionManager(bluetoothManager, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppNavigation(
                onConnectClick = {
                    if (GeneralData.state.value.isConnected) {
                        connectionManager.disconnect()
                    } else {
                        connectionManager.attemptConnect()
                    }
                },
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.disconnect()
    }
}
