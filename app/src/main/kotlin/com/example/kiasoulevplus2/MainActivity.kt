// ====================================================================================
// ГОЛОВНА АКТИВНІСТЬ ДОДАТКА (MainActivity)
//
// ПРИЗНАЧЕННЯ:
// Точка входу в Android-додаток. Точка ініціалізації менеджерів з'єднання
// та запуску Compose UI (AppNavigation).
//
// ЩО ВІН РОБИТЬ:
// 1. Створює єдині екземпляри ElmBluetoothManager та ConnectionManager через lazy.
// 2. Підписується на GeneralData.state та передає стан підключення в AppNavigation.
// 3. Обробляє клік по кнопці "Підключити / Відключити" через connectionManager.attemptConnect() / disconnect().
// 4. Безпечно розриває Bluetooth-з'єднання при знищенні Activity (onDestroy).
// ====================================================================================

package com.kirianov.kiasoulevplus2


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.example.kiasoulevplus2.Data.GeneralData
import com.example.kiasoulevplus2.Interface.AppNavigation
import com.example.kiasoulevplus2.services.bluetooth.ConnectionManager
import com.example.kiasoulevplus2.services.bluetooth.ElmBluetoothManager

class MainActivity : ComponentActivity() {

    private val bluetoothManager by lazy { ElmBluetoothManager() }
    private val connectionManager by lazy { ConnectionManager(bluetoothManager, lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by GeneralData.state.collectAsState()

            AppNavigation(
                connectionStatus = state.debugInfo,
                isConnected = state.isConnected,
                isConnecting = false,
                onConnectClick = {
                    if (state.isConnected) {
                        connectionManager.disconnect()
                    } else {
                        connectionManager.attemptConnect()
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.disconnect()
    }
}
