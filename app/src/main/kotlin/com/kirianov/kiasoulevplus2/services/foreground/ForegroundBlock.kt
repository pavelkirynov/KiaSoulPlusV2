// ====================================================================================
// БЛОК ПЕРЕДНЬОГО ПЛАНУ (ForegroundBlock)
//
// Стежить за станом з'єднання і тримає службу рівно стільки, скільки триває
// з'єднання: під'єдналися — підняв, від'єдналися — прибрав. Сповіщення не висить
// просто так.
//
// Про блок ніхто не знає, і він не знає ні про кого: усе, що йому треба, лежить
// у GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.foreground

import android.content.Context
import android.content.Intent
import android.os.Build
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ForegroundBlock(private val context: Context) {

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { it.connection }
            .distinctUntilChanged()
            .onEach { connection ->
                when (connection) {
                    ConnectionState.Connecting, ConnectionState.Connected -> keepAlive()
                    ConnectionState.Disconnected -> letGo()
                }
            }
            .launchIn(scope)
    }

    /**
     * Підняти службу. Помилку тут навмисно проковтуємо: з Android 12 систему можна
     * попросити про службу переднього плану лише з переднього плану, а користувач
     * міг устигнути згорнути застосунок між натисканням і відповіддю адаптера.
     * Це прикро, але не привід валити застосунок — опитування працюватиме, поки
     * процес живий, просто без гарантії.
     */
    private fun keepAlive() {
        val intent = Intent(context, ConnectionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: IllegalStateException) {
            // Система не дозволила саме зараз. Наступне під'єднання спробує знову.
        } catch (_: SecurityException) {
            // Немає дозволу на службу переднього плану: працюємо як раніше.
        }
    }

    private fun letGo() {
        try {
            context.stopService(Intent(context, ConnectionService::class.java))
        } catch (_: IllegalStateException) {
            // Служби вже немає — саме те, чого й хотіли.
        }
    }
}
