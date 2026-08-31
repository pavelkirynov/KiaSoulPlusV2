// ====================================================================================
// БЛОК СХОВИЩА (StorageBlock)
//
// На старті піднімає збережені ручні напруги в GeneralData, далі зберігає кожну зміну.
// Інтерфейс про сховище не знає: він лише пише значення в GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.storage

import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class StorageBlock(private val store: ManualCellStore) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            GeneralData.updateManualCells(store.load())

            GeneralData.state
                .map { it.manualCells.voltages }
                .distinctUntilChanged()
                // Перше значення — те, що ми щойно завантажили; писати його назад немає сенсу.
                .drop(1)
                .collect(store::save)
        }
    }
}
