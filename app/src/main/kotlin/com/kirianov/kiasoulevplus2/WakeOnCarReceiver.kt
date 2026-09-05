// ====================================================================================
// БУДИЛЬНИК ПО МАГНІТОЛІ (WakeOnCarReceiver)
//
// ЩО ВІН РОБИТЬ: прокидається, коли телефон з'єднався з обраним Bluetooth-пристроєм
// — магнітолою авто, — і піднімає службу застосунку. Далі все йде саме: служба
// створює процес, блоки стартують, автопідключення знаходить адаптер.
//
// ЧОМУ САМЕ МАГНІТОЛА, А НЕ САМ ELM. З'єднання з магнітолою телефон встановлює
// щоразу, коли ви сіли в авто, — надійнішої ознаки «поїхали» в нього просто немає.
// Клон ELM так не вміє: він чекає, поки під'єднаються до нього, тобто саме тоді,
// коли застосунок уже працює. Будити застосунок «появою ELM» означало б будити
// його тим, що без нього й не станеться.
//
// ЧОМУ ВІН ЛЕЖИТЬ У КОРЕНІ ПАКЕТА, А НЕ В БЛОЦІ. Це точка входу системи, як
// MainActivity: його створює Android, і його справа — зібрати те, що треба
// підняти. Блоки одне про одного не знають, а точки входу знають про всіх — на
// те вони й точки входу.
//
// ЧОГО ВІН НЕ РОБИТЬ: не вирішує, під'єднуватися чи ні, і навіть не читає стан.
// Його справа — оживити процес; далі рішення ухвалює блок автопідключення за
// налаштуванням користувача.
//
// ЧОМУ ВІН ПИШЕ В ЖУРНАЛ САМ. Коли будильник не спрацював, у журналі про це немає
// ні рядка — і не могло бути: застосунок не працював, писати не було кому. Тому
// приймач веде власний короткий протокол просто у файл журналу: спрацював, від
// якого пристрою, і чому нічого не підняв. Без цього «автозапуск не працює»
// неможливо ні підтвердити, ні спростувати.
//
// ВАЖЛИВО ПРО ANDROID 12+: підняти службу переднього плану з фонового приймача
// дозволено не всім, а застосункам, які винесені з-під оптимізації батареї. Це
// той самий дозвіл, який просить картка на головному екрані. Без нього будильник
// спрацює, але служба може не піднятися — тоді опитування працюватиме, поки
// система дозволяє, без гарантій.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kirianov.kiasoulevplus2.services.foreground.ConnectionService
import com.kirianov.kiasoulevplus2.tools.journal.FileJournalStore
import com.kirianov.kiasoulevplus2.tools.journal.JournalFormat
import com.kirianov.kiasoulevplus2.tools.settings.FileSettingsStore

class WakeOnCarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isConnection(intent)) return

        // Налаштування читаємо просто з файлу. Через блоки не вийде: блоки живуть
        // у процесі, а процесу в цю мить може ще не бути — його ж і піднімаємо.
        val wanted = runCatching {
            FileSettingsStore(context.applicationContext.filesDir).load()?.wakeOnDeviceAddress
        }.getOrNull().orEmpty()

        val device = deviceOf(intent)
        val address = runCatching { device?.address }.getOrNull().orEmpty()

        if (wanted.isEmpty()) {
            note(context, "спрацював від $address, але пристрій для запуску не обрано")
            return
        }
        if (device == null || !wanted.equals(address, ignoreCase = true)) {
            note(context, "спрацював від $address — це не $wanted, пропускаємо")
            return
        }

        val service = Intent(context, ConnectionService::class.java)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        }
        val failure = started.exceptionOrNull()
        if (failure == null) {
            note(context, "спрацював від $address, службу піднято")
        } else {
            // Найімовірніша причина — заборона піднімати службу з фону на
            // Android 12+. Саме її знімає картка «Робота у фоні».
            note(context, "спрацював від $address, але служба не піднялася: ${failure.javaClass.simpleName}")
        }
    }

    /**
     * Рядок у журнал повз усі блоки: у цю мить процесу застосунку може ще не бути,
     * а сказати про себе треба саме звідси.
     */
    private fun note(context: Context, text: String) {
        runCatching {
            val store = FileJournalStore(context.applicationContext.filesDir)
            store.append(listOf("${JournalFormat.stamp(System.currentTimeMillis())} wake «$text»"))
        }
    }

    /**
     * Чи це подія «пристрій з'єднався».
     *
     * ACL_CONNECTED приходить сама по собі; профільні події натомість приходять на
     * будь-яку зміну стану, тож у них треба ще перевірити, що стан саме
     * «з'єднано», — інакше будильник спрацьовував би й на роз'єднання.
     */
    private fun isConnection(intent: Intent): Boolean = when (intent.action) {
        BluetoothDevice.ACTION_ACL_CONNECTED -> true
        A2DP_STATE, HEADSET_STATE ->
            intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        const val HEADSET_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
    }
}
