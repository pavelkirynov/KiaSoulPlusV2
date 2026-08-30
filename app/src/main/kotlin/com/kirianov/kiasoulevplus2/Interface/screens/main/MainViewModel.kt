// ViewModel головного екрана: віддає екрану єдине джерело правди.

package com.kirianov.kiasoulevplus2.Interface.screens.main

import androidx.lifecycle.ViewModel
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.State
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    val uiState: StateFlow<State> = GeneralData.state
}
