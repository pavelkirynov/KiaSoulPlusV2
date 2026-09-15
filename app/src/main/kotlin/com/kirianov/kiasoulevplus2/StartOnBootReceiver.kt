// ====================================================================================
// ПІДНЯТИСЯ ПІСЛЯ ПЕРЕЗАВАНТАЖЕННЯ Й ПІСЛЯ ОНОВЛЕННЯ (StartOnBootReceiver)
//
// ЧОМУ ЦЬОГО НЕ ВИСТАЧАЛО. Будильник по магнітолі ([WakeOnCarReceiver]) ловить
// «водій сів в авто» — але щоб його почути, застосунок мусить бути НЕ В СТАНІ
// «зупинений». А Android ставить туди застосунок двічі: після перезавантаження
// телефона, поки його жодного разу не відкрили, і — головне — ОДРАЗУ ПІСЛЯ
// ВСТАНОВЛЕННЯ нового APK. Доки застосунок у цьому стані, до нього не доходить
// жоден широкомовний сигнал, і будильник мовчить. Саме це виглядало як
// «автозапуск не працює, поки не відкриєш руками», і саме це тут лікується.
//
// ACTION_MY_PACKAGE_REPLACED — ЄДИНИЙ СИГНАЛ, ЯКИЙ ДОХОДИТЬ ДО ЩОЙНО ОНОВЛЕНОГО
// ЗАСТОСУНКУ. Система надсилає його самому оновленому пакету й саме цим знімає з
// нього «зупинений» стан. Тобто після кожної нової збірки застосунок оживає сам, і
// відкривати його руками більше не треба.
//
// ЧОМУ ЦЕ НЕ ПОРУШУЄ ЗАБОРОНУ ANDROID 12+ на підняття служби з фону: усі три
// сигнали — завантаження, раннє завантаження й заміна пакета — стоять у списку
// винятків самої системи. Це не обхід, це передбачений випадок.
//
// ЩО ВІН НЕ РОБИТЬ: не під'єднується сам і не вирішує за користувача. Якщо
// автопідключення вимкнене, служба не піднімається взагалі — інакше вимкнений
// перемикач нічого б не означав.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kirianov.kiasoulevplus2.services.foreground.ConnectionService
import com.kirianov.kiasoulevplus2.tools.journal.FileJournalStore
import com.kirianov.kiasoulevplus2.tools.journal.JournalFormat
import com.kirianov.kiasoulevplus2.tools.settings.FileSettingsStore

class StartOnBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = reasonOf(intent.action) ?: return

        // Налаштування читаємо з файлу, а не зі сховища стану: процесу застосунку
        // в цю мить може ще не бути — його ж і піднімаємо.
        val settings = runCatching {
            FileSettingsStore(context.applicationContext.filesDir).load()
        }.getOrNull()

        if (settings != null && !settings.autoConnect) {
            note(context, "$reason, але автопідключення вимкнено — не піднімаємо")
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
            note(context, "$reason, службу піднято")
        } else {
            note(context, "$reason, але служба не піднялася: ${failure.javaClass.simpleName}")
        }
    }

    /** Людською мовою — що саме нас підняло. Потрібне журналу, більше нікому. */
    private fun reasonOf(action: String?): String? = when (action) {
        Intent.ACTION_BOOT_COMPLETED -> "телефон завантажився"
        Intent.ACTION_LOCKED_BOOT_COMPLETED -> "телефон завантажився (до розблокування)"
        Intent.ACTION_MY_PACKAGE_REPLACED -> "застосунок оновлено"
        else -> null
    }

    /**
     * Рядок у журнал повз усі блоки — з тієї самої причини, що й у будильника:
     * у цю мить писати більше нікому, а без рядка «чому не піднялося» відповісти
     * на скаргу неможливо.
     */
    private fun note(context: Context, text: String) {
        runCatching {
            val store = FileJournalStore(context.applicationContext.filesDir)
            store.append(listOf("${JournalFormat.stamp(System.currentTimeMillis())} boot «$text»"))
        }
    }
}
