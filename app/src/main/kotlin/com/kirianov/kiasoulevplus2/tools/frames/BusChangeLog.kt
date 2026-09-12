// ====================================================================================
// ЖУРНАЛ ЗМІН ШИНИ (BusChangeLog)
//
// Перетворює потік кадрів на список ЗМІН. Записувати кожен кадр немає сенсу: на
// стоячому авто той самий кадр приходить сотні разів на секунду однаковим, і
// натискання кнопки в цьому потоці не знайти. А от «кадр 4B0 щойно став іншим» —
// це подія, і саме вона видає замок, поворотник чи наближення ключа.
//
// Стан — це останнє відоме значення кожного ID. Кадр породжує подію, коли:
//   • такого ID ще не було (перша поява), або
//   • байти змінилися проти минулого разу.
//
// Стан НЕ ховається всередині: він приходить аргументом і повертається назад, бо
// запис іде вікнами, і між вікнами останнє значення мусить пережити. Так само й
// перевіряється — без адаптера, чистими вхідними кадрами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

import com.kirianov.kiasoulevplus2.Data.BusEvent
import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame

object BusChangeLog {

    /** Останнє значення кожного ID плюс зміни, знайдені в цій порції кадрів. */
    data class Result(
        val seen: Map<String, List<Int>>,
        val events: List<BusEvent>,
    )

    /**
     * Домішує [frames] до вже відомого [seen] і повертає нові зміни.
     *
     * [atMs] проставляється всім подіям цієї порції: усередині одного вікна
     * монітора точнішого часу однаково немає — рядки приходять пачкою.
     */
    fun fold(seen: Map<String, List<Int>>, frames: List<CanBroadcastFrame>, atMs: Long): Result {
        if (frames.isEmpty()) return Result(seen, emptyList())

        val known = seen.toMutableMap()
        val events = mutableListOf<BusEvent>()
        for (frame in frames) {
            if (known[frame.id] == frame.bytes) continue
            known[frame.id] = frame.bytes
            events += BusEvent(atMs = atMs, id = frame.id, bytes = frame.bytes)
        }
        return Result(known, events)
    }
}
