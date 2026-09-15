// ====================================================================================
// БЛОК ТЕЛЕМЕТРІЇ (TelemetryBlock)
//
// Раз на хвилину бере поточний стан, будує знімок BMS і шле його на сервер власника
// (Supabase). Ввімкнено лише коли користувач сам дозволив це в налаштуваннях —
// дані батареї їдуть на чужий сервер, і це має бути свідомий вибір.
//
// Мережа ненадійна: у русі зв'язок то є, то нема. Тому невідправлені знімки
// складаються в чергу й доганяються наступними спробами, а не губляться. Черга
// обмежена — телеметрія не має права з'їсти пам'ять, якщо сервер довго недоступний.
//
// Про Bluetooth і BMS не знає нічого — лише про стан у GeneralData й про [uploader].
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.telemetry

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.TelemetryConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelemetryBlock(
    private val uploader: TelemetryUploader,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = 60_000L,
) {
    fun start(scope: CoroutineScope) {
        // Сервера не налаштували — блок просто не існує для застосунку.
        if (!TelemetryConfig.isConfigured) return

        scope.launch(Dispatchers.IO) {
            var lastCellsMs = 0L
            val queue = ArrayDeque<Map<String, Any?>>()

            while (isActive) {
                delay(intervalMs)

                val state = GeneralData.state.value
                // Дозвіл користувача — головна умова. Вимкнув — нічого не збираємо
                // й не шлемо; накопичену чергу теж не тримаємо.
                if (!state.settings.telemetry) {
                    queue.clear()
                    continue
                }

                val now = nowMs()
                val withCells = TelemetryReporter.cellsDue(lastCellsMs, now)
                val row = TelemetryReporter.buildRow(state, includeCells = withCells)
                if (row != null) {
                    queue.addLast(row)
                    // Ритм комірок тримаємо за фактом збірки, а не відправки: інакше
                    // при поганому зв'язку кожна хвилина додавала б важкий рядок.
                    if (withCells) lastCellsMs = now
                    while (queue.size > MAX_QUEUE) queue.removeFirst()
                }

                if (queue.isEmpty()) continue

                val batch = queue.toList()
                val sent = try {
                    uploader.send(TelemetryReporter.toJsonArray(batch))
                } catch (_: Exception) {
                    false
                }
                // Прийнято — черга порожніє; ні — лишаємо як є до наступної спроби.
                if (sent) queue.clear()
            }
        }
    }

    private companion object {
        /**
         * Скільки знімків тримати в черзі при недоступному сервері. Сто вісімдесят
         * хвилин головних знімків — три години офлайну; більше вже не варто пам'яті.
         */
        const val MAX_QUEUE = 180
    }
}
