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

    fun onWakeDeviceChange(address: String) = GeneralData.setWakeOnDevice(address)

    fun onCarNameChange(name: String) = GeneralData.setCarName(name)

    /** Обрати авто вручну. Має сенс лише без зв'язку: на шині VIN сам себе назве. */
    fun onCarSelected(vin: String) = GeneralData.selectCar(vin)

    // --- Обмін даними авто --------------------------------------------------------

    fun onExport() = GeneralData.requestCarExport()

    fun onExportHandled() = GeneralData.clearExportedPath()

    fun onImport(path: String) = GeneralData.requestCarImport(path)

    /**
     * Ємність пакета, кВт·год.
     *
     * Порожній рядок і нуль означають «не задано» — тоді застосунок бере рідний
     * пакет. Верхня межа не від скнарості: більше сотні кіловат-годин у Soul EV не
     * влізе фізично, і таке число майже напевно означає, що людина ввела ват-години
     * або промахнулася комою.
     */
    fun onPackKwhChange(text: String): String? {
        val trimmed = text.trim().replace(',', '.')
        if (trimmed.isEmpty()) {
            GeneralData.setPackKwh(0.0)
            return null
        }
        val value = trimmed.toDoubleOrNull() ?: return "Не схоже на число"
        if (value < 0.0) return "Ємність не буває від'ємною"
        if (value > MAX_PLAUSIBLE_KWH) return "Більше за $MAX_PLAUSIBLE_KWH кВт·год у це авто не влізе"
        GeneralData.setPackKwh(value)
        return null
    }

    private companion object {
        const val MAX_PLAUSIBLE_KWH = 120.0
    }
}
