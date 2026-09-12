// ====================================================================================
// СЛУЖБА ПЕРЕДНЬОГО ПЛАНУ (ConnectionService)
//
// НАВІЩО ВОНА ПОТРІБНА
//
// Блоки живуть у `App`, а не в активності, тож логічно опитування мало б тривати й
// зі згорнутим застосунком. Але Android так не працює: у процесу без жодного
// видимого компонента немає підстав жити. Система спершу заморожує його, а потім
// вивантажує, щойно знадобиться пам'ять, — і цикл опитування просто зупиняється.
// З вимкненим екраном додається ще й Doze.
//
// Досі процес тримався лише тоді, коли до нього прив'язувався Android Auto через
// медіа-браузер. У машині це працювало; варто було від'єднати магнітолу — і
// навчання тихо припинялося, а водій про це не дізнавався.
//
// Служба переднього плану — єдиний передбачений Android спосіб сказати: цей процес
// робить свою справу, поки триває з'єднання. Ціна — постійне сповіщення, і воно ж
// корисне: у ньому видно запас ходу, не відкриваючи застосунок.
//
// Служба нічого не рахує і нічим не керує. Її єдина робота — існувати, поки є
// з'єднання, і показувати те, що вже лежить у GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.foreground

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Будильник процесора на час з'єднання.
     *
     * Служби переднього плану виявилося замало. У журналі поїздки є проміжок на
     * 634 секунди, за який номер зчитування шини зріс лише на два: з'єднання
     * живе, сповіщення висить, а потоки сплять. Це Doze і фірмові «оптимізації»
     * оболонки — вони приспиняють саме роботу, а не компонент.
     *
     * Частковий wake lock тримає процесор, лишаючи екран вимкненим. Береться
     * рівно на час з'єднання і віддається разом зі службою, тож розряджати
     * телефон просто так йому нічим.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Сповіщення мусить з'явитися одразу: Android дає на це п'ять секунд і
        // вбиває службу, якщо не дочекався.
        startForeground(NOTIFICATION_ID, buildNotification(GeneralData.state.value))

        GeneralData.state
            .map(::summaryOf)
            .distinctUntilChanged()
            .onEach { notifyWith(it) }
            .launchIn(scope)

        // Перезапускати без з'єднання немає сенсу: адаптер усе одно доведеться
        // під'єднувати заново, і сповіщення висіло б без діла.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /** Рядок сповіщення. Окремо від стану, щоб не перемальовувати його щосекунди. */
    private fun summaryOf(state: State): String = when {
        state.connection == ConnectionState.Connecting -> "Під'єднуюсь до адаптера..."
        !state.isConnected -> "Немає з'єднання"
        else -> state.ml.prediction?.let { prediction ->
            "≈${prediction.rangeKm.toInt()} км · ${prediction.realPercent.toInt()} %"
        } ?: "Збираю дані..."
    }

    private fun notifyWith(summary: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(summary))
    }

    private fun buildNotification(state: State): Notification = buildNotification(summaryOf(state))

    private fun buildNotification(summary: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle("Прогноз запасу ходу")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)

        // Натиск на сповіщення відкриває застосунок. Якщо запускати нема чого —
        // сповіщення лишається без переходу, але службу через це не валимо.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }

        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Опитування адаптера",
            // Тихо і без звуку: це не подія, а ознака того, що робота триває.
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Тег видно в звіті про батарею — хай там буде зрозуміло, хто не спить. */
        private const val WAKE_LOCK_TAG = "KiaSoulEVPlus:polling"

        private const val CHANNEL_ID = "obd-polling"
        private const val NOTIFICATION_ID = 1
    }
}
