package com.kirianov.kiasoulevplus2.Data

/**
 * Куди й чим шле телеметрію батареї.
 *
 * URL і ключ — Supabase-проєкту власника. Ключ САМЕ publishable: він за задумом
 * публічний, доступ обмежують правила RLS на сервері (анону дозволено лише
 * вставляти й читати таблицю знімків, не більше). Тому тримати його в коді
 * прийнятно; якщо колись знадобиться суворіший захист — запис виноситься в
 * серверну функцію з секретним токеном, і оце місце вже не буде «паролем».
 */
object TelemetryConfig {
    const val URL = "https://phdmlgpxevzyjhhcelqm.supabase.co"
    const val KEY = "sb_publishable_ox-BFOtarZSPJ_zeVtFXyg_a_RzbFe9"
    const val TABLE = "battery_snapshots"

    /** Чи налаштований сервер узагалі: без URL/ключа блок телеметрії просто спить. */
    val isConfigured: Boolean get() = URL.isNotBlank() && KEY.isNotBlank()
}
