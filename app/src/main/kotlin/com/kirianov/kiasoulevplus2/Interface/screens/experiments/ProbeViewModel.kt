// ====================================================================================
// VIEWMODEL ЕКРАНА «ЕКСПЕРИМЕНТИ» (ProbeViewModel)
//
// Ставить у GeneralData ручний запит до шини. Про блок Bluetooth не знає:
// той сам побачить запит і виконає його.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.experiments

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.frames.CanCommand
import kotlinx.coroutines.flow.StateFlow

class ProbeViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    /** Відоме число зі щитка, яке шукаємо у відповідях. Порожній рядок знімає пошук. */
    fun onTargetChanged(text: String) =
        GeneralData.setProbeTarget(text.trim().toLongOrNull()?.takeIf { it > 0 })

    /** Повертає текст помилки або null, якщо запит поставлено. */
    fun onSend(header: String, command: String): String? {
        if (!CanCommand.isValidHeader(header)) {
            return "Заголовок має бути 3 або 8 шістнадцяткових цифр, наприклад 7C6"
        }

        CanCommand.rejectionReason(command)?.let { return it }

        GeneralData.requestProbe(
            header = CanCommand.normalize(header),
            command = CanCommand.normalize(command),
        )
        return null
    }
}
