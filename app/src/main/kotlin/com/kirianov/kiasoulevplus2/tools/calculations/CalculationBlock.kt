// ====================================================================================
// БЛОК ОБЧИСЛЕНЬ (CalculationBlock)
//
// Слухає розібрані показники в GeneralData і кладе туди ж похідні величини.
// Раніше цей перерахунок робило саме сховище — тепер воно лишається пасивним.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CalculationBlock {

    /** Вхідні дані перерахунку — окремим типом, щоб distinctUntilChanged порівнював саме їх. */
    private data class Readings(val bms: BmsData, val cells: CellData)

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { Readings(it.bms, it.cells) }
            .distinctUntilChanged()
            .onEach { GeneralData.updateCalculated(CalculationEngine.calculate(it.bms, it.cells)) }
            .launchIn(scope)
    }
}
