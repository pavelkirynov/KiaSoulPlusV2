// ====================================================================================
// БЛОК ДАНИХ АВТО (VehicleBlock)
//
// Слухає сирі рядки, які блок Bluetooth зняв у режимі монітора, розбирає з них
// широкомовні кадри й кладе результат у GeneralData. Про Bluetooth не знає нічого.
//
// Тут же живе висновок «авто заряджається», якого на шині може не бути зовсім:
// кадр 581 належить бортовому зарядному й на швидкій зарядці мовчить. Див.
// [ChargeSense].
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.tools.frames.MonitorLineParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class VehicleBlock(
    private val sense: ChargeSense = ChargeSense(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    fun start(scope: CoroutineScope) {
        senseCharging(scope)
        GeneralData.state
            .map { it.can.monitor }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { capture ->
                val frames = capture.lines
                    .mapNotNull { MonitorLineParser.parse(it, capture.filterId) }
                    .filter { it.id in BroadcastDecoder.KNOWN_IDS }

                if (frames.isEmpty()) return@onEach
                GeneralData.updateVehicle(
                    BroadcastDecoder.merge(GeneralData.state.value.vehicle, frames),
                )
            }
            .launchIn(scope)
    }

    /**
     * Зарядка, про яку кадр 581 не оголошує.
     *
     * Такт задає номер зчитування шини, а не зміна струму: струм двічі поспіль
     * буває однаковим, і тоді сховище стану про «зміну» не сповістить — а нам
     * потрібен рівний хід часу, бо ознака ставиться саме за тривалістю.
     *
     * РОЗРИВ ЗВ'ЯЗКУ ЗНІМАЄ ОЗНАКУ ЗАРЯДКИ ЦІЛКОМ, і це окреме виправлення. Поки
     * вона переживала розрив, після зарядки виходило так: телефон від'єднався на
     * зарядці, авто поїхало, телефон під'єднався вже на ходу — і перше ж читання
     * лічильника прийнятої енергії, який за поїздку виріс від рекуперації,
     * зараховувалося у ту саму, досі відкриту сесію зарядки. Ознака заряджання
     * старіє швидше за все інше: підтвердити її можна лише живою шиною.
     */
    private fun senseCharging(scope: CoroutineScope) {
        GeneralData.state
            .map { state ->
                Tick(
                    connected = state.isConnected,
                    sequence = state.can.batteryFrames?.sequence ?: -1L,
                    currentA = state.bms.batteryCurrent,
                    moving = state.vehicle.hasSpeed && state.vehicle.speedKmh > 0.0,
                )
            }
            .distinctUntilChanged()
            .onEach { tick ->
                val sensed = if (tick.connected) {
                    sense.observe(tick.currentA, moving = tick.moving, nowMs = nowMs())
                } else {
                    sense.forget()
                    false
                }

                val vehicle = GeneralData.state.value.vehicle
                val charging = vehicle.charging
                val updated = when {
                    !tick.connected -> charging.copy(reported = false, sensed = false)
                    charging.sensed != sensed -> charging.copy(sensed = sensed)
                    else -> charging
                }
                if (updated != charging) GeneralData.updateVehicle(vehicle.copy(charging = updated))
            }
            .launchIn(scope)
    }

    private data class Tick(
        val connected: Boolean,
        val sequence: Long,
        val currentA: Double,
        val moving: Boolean,
    )
}
