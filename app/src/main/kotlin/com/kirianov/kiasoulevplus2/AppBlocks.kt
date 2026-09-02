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
import com.kirianov.kiasoulevplus2.services.foreground.ForegroundBlock
import com.kirianov.kiasoulevplus2.tools.autoconnect.AutoConnectBlock
import com.kirianov.kiasoulevplus2.tools.battery.DecoderBlock
import com.kirianov.kiasoulevplus2.tools.charging.ChargingBlock
import com.kirianov.kiasoulevplus2.tools.charging.FileChargeStore
import com.kirianov.kiasoulevplus2.tools.calculations.CalculationBlock
import com.kirianov.kiasoulevplus2.tools.ml.FileMlStore
import com.kirianov.kiasoulevplus2.tools.ml.MlBlock
import com.kirianov.kiasoulevplus2.tools.probe.ProbeBlock
import com.kirianov.kiasoulevplus2.tools.storage.SharedPreferencesCellStore
import com.kirianov.kiasoulevplus2.tools.storage.StorageBlock
import com.kirianov.kiasoulevplus2.tools.vehicle.VehicleBlock
import kotlinx.coroutines.CoroutineScope

class AppBlocks(context: Context) {

    private val bluetooth = BluetoothBlock(ElmBluetoothManager())
    private val decoders = DecoderBlock()
    private val calculations = CalculationBlock()
    private val probe = ProbeBlock()
    private val vehicle = VehicleBlock()
    private val autoConnect = AutoConnectBlock()
    private val charging = ChargingBlock(FileChargeStore(context.applicationContext.filesDir))
    private val storage = StorageBlock(SharedPreferencesCellStore(context.applicationContext))
    // Каталог, а не Context: так сховище моделей лишається чистим Kotlin і
    // перевіряється тестами без емулятора, як і решта логіки проєкту.
    private val prediction = MlBlock(FileMlStore(context.applicationContext.filesDir))
    private val foreground = ForegroundBlock(context.applicationContext)

    fun start(scope: CoroutineScope) {
        decoders.start(scope)
        calculations.start(scope)
        probe.start(scope)
        vehicle.start(scope)
        charging.start(scope)
        autoConnect.start(scope)
        storage.start(scope)
        prediction.start(scope)
        foreground.start(scope)
        bluetooth.start(scope)
    }
}
