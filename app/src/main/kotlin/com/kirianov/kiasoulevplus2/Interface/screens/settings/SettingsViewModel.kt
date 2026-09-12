// ====================================================================================
// VIEWMODEL ЕКРАНА НАЛАШТУВАНЬ (SettingsViewModel)
//
// Кладе вибір користувача в GeneralData. Хто як на нього реагує — справа блоків.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.settings

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    fun onAutoConnectChange(enabled: Boolean) = GeneralData.setAutoConnect(enabled)

    fun onJournalChange(enabled: Boolean) = GeneralData.setJournalEnabled(enabled)

    fun onWakeDeviceChange(address: String) = GeneralData.toggleWakeOnDevice(address)

    /** Правка авто зі списку: назва і ємність разом, за одну дію. */
    fun onCarEdited(vin: String, name: String, packKwh: Double) =
        GeneralData.editCar(vin, name, packKwh)

    /**
     * Обрати авто вручну — це ПЕРЕГЛЯД, а не перемикання обліку: записи далі йдуть
     * у ту машину, яку назвав VIN на шині.
     */
    fun onCarSelected(vin: String) = GeneralData.selectCar(vin)

    fun onCarDeleted(vin: String) = GeneralData.requestCarDelete(vin)

    // --- Обмін даними авто --------------------------------------------------------

    fun onExport() = GeneralData.requestCarExport()

    fun onExportHandled() = GeneralData.clearExportedPath()

    fun onImport(path: String) = GeneralData.requestCarImport(path)
}
