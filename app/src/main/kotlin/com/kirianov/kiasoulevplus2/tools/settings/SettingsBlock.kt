// ====================================================================================
// БЛОК НАЛАШТУВАНЬ (SettingsBlock)
//
// Завантажує налаштування при старті й зберігає кожну зміну. Більше нічого:
// хто як реагує на налаштування — справа тих блоків, а не цього.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.settings

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SettingsBlock(private val store: SettingsStore) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            GeneralData.updateSettings(store.load() ?: Settings())

            GeneralData.state
                .map { it.settings }
                .distinctUntilChanged()
                // Перше значення — те, що ми щойно завантажили; писати його назад немає сенсу.
                .drop(1)
                .collect(store::save)
        }
    }
}
