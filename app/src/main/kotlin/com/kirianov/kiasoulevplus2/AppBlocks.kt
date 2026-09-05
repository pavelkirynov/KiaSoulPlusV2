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
import com.kirianov.kiasoulevplus2.tools.cells.CellTestBlock
import com.kirianov.kiasoulevplus2.tools.charging.ChargingBlock
import com.kirianov.kiasoulevplus2.tools.charging.FileChargeStore
import com.kirianov.kiasoulevplus2.tools.calculations.CalculationBlock
import com.kirianov.kiasoulevplus2.tools.energy.EnergyBlock
import com.kirianov.kiasoulevplus2.tools.energy.FileEnergyStore
import com.kirianov.kiasoulevplus2.tools.garage.FileGarageStore
import com.kirianov.kiasoulevplus2.tools.garage.GarageBlock
import com.kirianov.kiasoulevplus2.tools.garage.ShareBlock
import com.kirianov.kiasoulevplus2.tools.journal.FileJournalStore
import com.kirianov.kiasoulevplus2.tools.journal.JournalBlock
import com.kirianov.kiasoulevplus2.tools.ml.FileMlStore
import com.kirianov.kiasoulevplus2.tools.ml.MlBlock
import com.kirianov.kiasoulevplus2.tools.probe.ProbeBlock
import com.kirianov.kiasoulevplus2.tools.settings.FileSettingsStore
import com.kirianov.kiasoulevplus2.tools.settings.SettingsBlock
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
    private val settings = SettingsBlock(FileSettingsStore(context.applicationContext.filesDir))
    // Хто це авто. Від нього залежить, у якій теці лежать дані решти блоків.
    private val garage = GarageBlock(FileGarageStore(context.applicationContext.filesDir))
    private val autoConnect = AutoConnectBlock()
    private val chargeStore = FileChargeStore(context.applicationContext.filesDir)
    private val charging = ChargingBlock(chargeStore)
    private val storage = StorageBlock(SharedPreferencesCellStore(context.applicationContext))
    // Тест комірок під навантаженням: накопичує проходи й рахує підсумок.
    private val cellTest = CellTestBlock()
    // Каталог, а не Context: так сховище моделей лишається чистим Kotlin і
    // перевіряється тестами без емулятора, як і решта логіки проєкту.
    private val mlStore = FileMlStore(context.applicationContext.filesDir)
    private val prediction = MlBlock(mlStore)
    // Міряє криву ємності різницею пожиттєвих лічильників.
    private val energyStore = FileEnergyStore(context.applicationContext.filesDir)
    private val energy = EnergyBlock(energyStore)
    private val foreground = ForegroundBlock(context.applicationContext)

    /**
     * Обмін даними авто між телефонами.
     *
     * Сховища перелічені тут, а не всередині блока: точка збірки має право знати
     * всіх, а блок обміну бачить у них лише вміння віддати й прийняти.
     */
    private val share = ShareBlock(
        root = context.applicationContext.filesDir,
        cache = context.applicationContext.cacheDir,
        stores = listOf(mlStore, energyStore, chargeStore),
    )

    // Свідок усього, що відбувається: пише в файл те саме, що бачить екран.
    private val journal = JournalBlock(
        store = FileJournalStore(context.applicationContext.filesDir),
        appVersion = versionOf(context),
    )

    fun start(scope: CoroutineScope) {
        decoders.start(scope)
        calculations.start(scope)
        probe.start(scope)
        vehicle.start(scope)
        charging.start(scope)
        settings.start(scope)
        garage.start(scope)
        share.start(scope)
        autoConnect.start(scope)
        storage.start(scope)
        cellTest.start(scope)
        prediction.start(scope)
        energy.start(scope)
        foreground.start(scope)
        bluetooth.start(scope)
        // Останнім: так у журнал потрапляє вже піднятий стан, а не порожній.
        journal.start(scope)
    }

    private fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: "?"
    }.getOrDefault("?")
}
