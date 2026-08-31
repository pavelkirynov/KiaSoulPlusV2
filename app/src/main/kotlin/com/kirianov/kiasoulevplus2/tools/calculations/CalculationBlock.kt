// ====================================================================================
// БЛОК ОБЧИСЛЕНЬ (CalculationBlock)
//
// Слухає розібрані показники в GeneralData і кладе туди ж похідні величини.
// Він же ставить позначку лічильників енергії на початку поїздки та знімає її
// при від'єднанні — тобто одне підключення до авто дає одну поїздку.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.calculations

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.EnergySession
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class CalculationBlock {

    /** Вхідні дані перерахунку — окремим типом, щоб distinctUntilChanged порівнював саме їх. */
    private data class Readings(val bms: BmsData, val cells: CellData, val session: EnergySession)

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { Readings(it.bms, it.cells, it.energySession) }
            .distinctUntilChanged()
            .onEach { GeneralData.updateCalculated(CalculationEngine.calculate(it.bms, it.cells, it.session)) }
            .launchIn(scope)

        // Позначка ставиться на першому зчитуванні з лічильниками і тримається до від'єднання.
        GeneralData.state
            .map { SessionTrigger(it.connection, it.bms.hasEnergyCounters, it.energySession.isStarted) }
            .distinctUntilChanged()
            .onEach { trigger ->
                when {
                    trigger.connection == ConnectionState.Disconnected && trigger.sessionStarted ->
                        GeneralData.clearEnergySession()

                    trigger.connection == ConnectionState.Connected &&
                        trigger.hasCounters && !trigger.sessionStarted ->
                        GeneralData.startEnergySession(GeneralData.state.value.bms)
                }
            }
            .launchIn(scope)
    }

    private data class SessionTrigger(
        val connection: ConnectionState,
        val hasCounters: Boolean,
        val sessionStarted: Boolean,
    )
}
