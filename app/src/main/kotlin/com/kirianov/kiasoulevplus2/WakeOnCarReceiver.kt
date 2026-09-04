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
// ВАЖЛИВО ПРО ANDROID 12+: підняти службу переднього плану з фонового приймача
// дозволено не всім, а застосункам, які винесені з-під оптимізації батареї. Це
// той самий дозвіл, який просить картка на головному екрані. Без нього будильник
// спрацює, але служба може не піднятися — тоді опитування працюватиме, поки
// система дозволяє, без гарантій.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kirianov.kiasoulevplus2.services.foreground.ConnectionService
import com.kirianov.kiasoulevplus2.tools.settings.FileSettingsStore

class WakeOnCarReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        // Налаштування читаємо просто з файлу. Через блоки не вийде: блоки живуть
        // у процесі, а процесу в цю мить може ще не бути — його ж і піднімаємо.
        val wanted = runCatching {
            FileSettingsStore(context.applicationContext.filesDir).load()?.wakeOnDeviceAddress
        }.getOrNull().orEmpty()
        if (wanted.isEmpty()) return

        val device = deviceOf(intent) ?: return
        if (!wanted.equals(device.address, ignoreCase = true)) return

        val service = Intent(context, ConnectionService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service)
            } else {
                context.startService(service)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
