// ====================================================================================
// БЛОК АВТОПІДКЛЮЧЕННЯ (AutoConnectBlock)
//
// Сам просить з'єднання, поки його немає, — щоб моделі вчилися на щоденних
// поїздках, а не лише тоді, коли хтось згадав натиснути кнопку.
//
// ВАЖЛИВЕ ПРАВИЛО: явне «Відключити» поважається. Інакше кнопка відключення
// перестала б працювати — застосунок під'єднувався б назад через секунди, і це
// виглядало б як зламаний застосунок, а не як автопідключення. Вмикається знову
// натисканням «Підключити».
//
// Про Bluetooth блок не знає нічого: він лише ставить запит у GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.autoconnect

import com.kirianov.kiasoulevplus2.Data.AppRequest
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutoConnectBlock {

    /**
     * Чи користувач сам відключився. Не в GeneralData: це не дані про авто, а
     * пам'ять одного блока про останній намір користувача.
     */
    @Volatile
    private var userWantsOff = false

    fun start(scope: CoroutineScope) {
        watchIntent(scope)
        keepConnected(scope)
    }

    /** Слухаємо, чого просив користувач: його «відключити» скасовує автопідключення. */
    private fun watchIntent(scope: CoroutineScope) {
        GeneralData.state
            .map { it.request }
            .distinctUntilChanged()
            .onEach { request ->
                when (request) {
                    AppRequest.Disconnect -> userWantsOff = true
                    AppRequest.Connect -> userWantsOff = false
                    AppRequest.None -> Unit
                }
            }
            .launchIn(scope)
    }

    private fun keepConnected(scope: CoroutineScope) {
        scope.launch {
            var attempt = 0

            while (isActive) {
                val connection = GeneralData.state.value.connection

                if (connection == ConnectionState.Connected) {
                    // Вийшло: наступна втрата зв'язку починає відступ заново.
                    attempt = 0
                    delay(AutoConnectPolicy.IDLE_CHECK_MS)
                    continue
                }

                // Поки підключення в процесі, друга спроба лише перебила б першу.
                if (connection == ConnectionState.Connecting || userWantsOff) {
                    delay(AutoConnectPolicy.IDLE_CHECK_MS)
                    continue
                }

                attempt++
                GeneralData.requestConnect()
                delay(AutoConnectPolicy.delayAfter(attempt))
            }
        }
    }
}
