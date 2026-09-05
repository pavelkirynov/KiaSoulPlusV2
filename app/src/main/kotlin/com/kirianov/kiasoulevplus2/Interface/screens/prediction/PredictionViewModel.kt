// ViewModel екрана прогнозу: віддає стан із GeneralData і пише туди ж запити до блока.

package com.kirianov.kiasoulevplus2.Interface.screens.prediction

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.StateFlow

class PredictionViewModel : ViewModel() {

    val uiState: StateFlow<State> = GeneralData.state

    /**
     * Ставить запит у сховище. Екран не тримає посилання на блок прогнозу —
     * той сам побачить запит і виконає його.
     */
    fun onRetrainClick() = GeneralData.requestMlRetrain()

    fun onResetClick() = GeneralData.requestMlReset()

    /** Обнуляє відлік точності прогнозу: почати міряти заново з цієї миті. */
    fun onResetAccuracy() = GeneralData.resetRangeAccuracy()

    fun onResetCurve() = GeneralData.requestCurveReset()
}
