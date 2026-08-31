// ====================================================================================
// ЗАПУСК БЛОКІВ (AppBlocks)
//
// Єдине місце, де перелічені всі блоки додатка. Вони не створюють один одного і не
// обмінюються посиланнями — кожен просто підписується на GeneralData.
// Додати новий блок = створити його тут і не чіпати жоден інший файл.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.content.Context
import com.kirianov.kiasoulevplus2.services.bluetooth.BluetoothBlock
import com.kirianov.kiasoulevplus2.services.bluetooth.ElmBluetoothManager
import com.kirianov.kiasoulevplus2.tools.battery.DecoderBlock
import com.kirianov.kiasoulevplus2.tools.calculations.CalculationBlock
import com.kirianov.kiasoulevplus2.tools.storage.SharedPreferencesCellStore
import com.kirianov.kiasoulevplus2.tools.storage.StorageBlock
import kotlinx.coroutines.CoroutineScope

class AppBlocks(context: Context) {

    private val bluetooth = BluetoothBlock(ElmBluetoothManager())
    private val decoders = DecoderBlock()
    private val calculations = CalculationBlock()
    private val storage = StorageBlock(SharedPreferencesCellStore(context.applicationContext))

    fun start(scope: CoroutineScope) {
        decoders.start(scope)
        calculations.start(scope)
        storage.start(scope)
        bluetooth.start(scope)
    }

    fun stop() {
        bluetooth.disconnect()
    }
}
