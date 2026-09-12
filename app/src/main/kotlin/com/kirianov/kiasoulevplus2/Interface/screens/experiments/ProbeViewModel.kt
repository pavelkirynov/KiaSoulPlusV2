// ====================================================================================
// VIEWMODEL ЕКРАНА «ЕКСПЕРИМЕНТИ» (ProbeViewModel)
//
// Ставить у GeneralData ручний запит до шини. Про блок Bluetooth не знає:
// той сам побачить запит і виконає його.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Interface.screens.experiments

import android.content.Context
import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.BusSlot
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.frames.CanCommand
import kotlinx.coroutines.flow.StateFlow

class ProbeViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    fun onJournalEnabled(enabled: Boolean) = GeneralData.setJournalEnabled(enabled)

    fun onJournalClear() = GeneralData.requestJournalClear()

    /** Відоме число зі щитка, яке шукаємо у відповідях. Порожній рядок знімає пошук. */
    fun onTargetChanged(text: String) =
        GeneralData.setProbeTarget(text.trim().toLongOrNull()?.takeIf { it > 0 })

    // --- Пошук невідомої ознаки на шині -----------------------------------------

    fun onSweep() = GeneralData.requestBusSweep()

    /** Записати шину протягом [seconds]: слухати без фільтра й вести журнал змін. */
    fun onRecord(seconds: Int) = GeneralData.requestBusRecord(seconds)

    /** Мітка під час запису: натискається рівно в мить дії (натиснув пульт). */
    fun onMark() = GeneralData.markBusRecording(System.currentTimeMillis())

    // --- Тест виводу на магнітолу ------------------------------------------------

    private val mediaTest = MediaTestController()

    val mediaTestRunning: Boolean get() = mediaTest.running

    fun onMediaTestStart(context: Context) = mediaTest.start(context)

    fun onMediaTestStop() = mediaTest.stop()

    /** Екран закрили — тиху доріжку й сесію треба прибрати, інакше грали б далі. */
    override fun onCleared() {
        mediaTest.stop()
        super.onCleared()
    }

    fun onCapture(slot: BusSlot, label: String) =
        GeneralData.captureBusSnapshot(slot, label.trim(), System.currentTimeMillis())

    fun onForgetFrames() = GeneralData.forgetBusFrames()

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
