// ====================================================================================
// БЛОК ЖУРНАЛУ (JournalBlock)
//
// ЩО ВІН РОБИТЬ:
// 1. Дивиться на GeneralData і дописує у файл рядки про те, що змінилося.
// 2. Раз на кілька секунд пише зріз показників, щоб було видно й те, що НЕ
//    змінювалося.
// 3. Тримає в GeneralData розмір і шлях файлу — для екрана «Експерименти».
// 4. Виконує запит «очистити».
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ звертається до інших блоків: усе, що йому треба, лежить у GeneralData.
//   Саме тому він бачить рівно те саме, що й екран, — і журнал не бреше.
// - НЕ вирішує, що правильно, а що ні. Він свідок, а не суддя.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.journal

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.JournalRequest
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class JournalBlock(
    private val store: JournalStore,
    private val appVersion: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun start(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            var before: State? = null
            var lastSnapshotMs = 0L
            var written = 0L

            GeneralData.updateJournal { it.copy(path = store.path(), sizeBytes = store.sizeBytes()) }

            GeneralData.state.collect { state ->
                if (state.journal.request == JournalRequest.Clear) {
                    GeneralData.clearJournalRequest()
                    store.clear()
                    written = 0L
                    before = null
                    lastSnapshotMs = 0L
                    GeneralData.updateJournal { it.copy(sizeBytes = 0L, lines = 0L) }
                    return@collect
                }

                if (!state.settings.journal) {
                    // Вимкнули запис: попередній стан більше не орієнтир, інакше
                    // після вмикання посипалися б події за весь час мовчання.
                    before = null
                    return@collect
                }

                val at = nowMs()
                val previous = before
                before = state

                val lines = mutableListOf<String>()
                if (previous == null) {
                    lines += JournalFormat.opened(at, appVersion)
                } else {
                    lines += JournalFormat.events(previous, state, at)
                }

                // Зріз пишемо, лише поки є з'єднання: у відключеному стані нічого
                // не змінюється, і сотні однакових рядків витіснили б корисне.
                if (state.isConnected && at - lastSnapshotMs >= SNAPSHOT_EVERY_MS) {
                    lastSnapshotMs = at
                    lines += JournalFormat.snapshot(state, at)
                }

                if (lines.isEmpty()) return@collect

                val size = store.append(lines)
                written += lines.size
                val count = written
                GeneralData.updateJournal { it.copy(sizeBytes = size, lines = count) }
            }
        }
    }

    private companion object {
        /**
         * Один зріз на п'ять секунд. Частіше — і файл росте вдесятеро заради
         * майже однакових рядків; рідше — і коротка пауза в опитуванні (а саме
         * вона й цікава) провалюється між зрізами.
         */
        const val SNAPSHOT_EVERY_MS = 5_000L
    }
}
