// ====================================================================================
// БЛОК ОБЛІКУ ЗАРЯДОК (ChargingBlock)
//
// Слухає пожиттєвий лічильник прийнятої енергії та ознаку заряджання, веде з них
// облік зарядок і кладе результат у GeneralData. Про Bluetooth і про BMS не знає
// нічого — тільки про два числа зі сховища стану.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.charging

import com.kirianov.kiasoulevplus2.Data.ChargeLog
import com.kirianov.kiasoulevplus2.Data.ChargeRequest
import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.VehicleData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ChargingBlock(
    private val store: ChargeStore,
    /** Годинник на стіні: потрібен, щоб знати добу й час завершення зарядки. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val dayKey: () -> String = ::localDayKey,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var log = store.load() ?: ChargeLog()
            GeneralData.updateChargeLog(log)

            // Змінилося авто — перечитати облік із його теки. Лічильники в різних
            // машин свої, і продовжувати чужий означало б записати різницю між
            // двома батареями як зарядку.
            launch {
                GeneralData.state
                    .map { it.garage.activeVin }
                    .filter { it.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { vin ->
                        store.useCar(vin)
                        log = store.load() ?: ChargeLog()
                        GeneralData.updateChargeLog(log)
                    }
            }

            GeneralData.state
                .map {
                    // Усі три числа батареї — з одного кадру 21 01. Саме тому їх
                    // можна порівнювати між собою після будь-якої паузи: у них
                    // однаковий вік. Ознака заряджання приходить окремим кадром,
                    // але вона потрібна лише «прямо зараз», а не в порівнянні.
                    Reading(
                        counterKwh = it.bms.cumulativeEnergyChargedKwh,
                        dischargedKwh = it.bms.cumulativeEnergyDischargedKwh,
                        socPercent = it.bms.displaySoc,
                        isCharging = it.vehicle.charging.isCharging,
                        ignitionOn = ignitionOn(it.vehicle),
                        // ТРИ СТАНИ, А НЕ ДВА. «Роз'єму немає» закриває сесію, а
                        // «не знаю» мусить лишати її відкритою: без зв'язку або без
                        // кадру батареї ми не знаємо навіть того, чи авто ще стоїть
                        // на зарядці, і закривати її на цій підставі — та сама
                        // помилка, через яку колись пропала ціла ніч.
                        plugged = if (it.isConnected && it.bms.hasData) {
                            it.vehicle.charging.plugged
                        } else {
                            null
                        },
                        // Пробіг — третій свідок «авто стояло»: див. пояснення в
                        // ChargeTracker.observe. Тут він передається як є, разом із
                        // «не знаємо»: жодних припущень замість нього.
                        odometerKm = it.vehicle.odometerKm.takeIf { km -> km > 0.0 },
                        request = it.charge.request,
                        carKnown = it.carAccounting,
                    )
                }
                .distinctUntilChanged()
                .collect { reading ->
                    val now = nowMs()
                    val day = dayKey()

                    // Хто на шині — ще не з'ясовано. Ці числа може давати сусідня
                    // машина, і зарахувати їх активному авто означає збити його
                    // відкриту сесію: рівно так одна нічна зарядка й почалася з
                    // нуля. Мовчати про пропуск теж не можна — інакше цього не
                    // видно ані в журналі, ані на екрані.
                    if (!reading.carKnown) {
                        if (log.lastDecision != UNKNOWN_CAR) {
                            log = log.copy(lastDecision = UNKNOWN_CAR)
                            GeneralData.updateChargeLog(log)
                        }
                        return@collect
                    }

                    // Прохання з екрана виконуємо першим і окремо: у нього свої
                    // правила — жодних порогів, бо зарядку бачила людина.
                    if (reading.request == ChargeRequest.FinishSession) {
                        GeneralData.clearChargeRequest()
                        val finished = ChargeTracker.finishManually(
                            log = log,
                            counterKwh = reading.counterKwh,
                            dischargedKwh = reading.dischargedKwh,
                            socPercent = reading.socPercent,
                            nowMs = now,
                            dayKey = day,
                        )
                        if (finished != log) {
                            log = finished
                            GeneralData.updateChargeLog(finished)
                            store.save(finished)
                        }
                        return@collect
                    }

                    val updated = ChargeTracker.observe(
                        log = log,
                        counterKwh = reading.counterKwh,
                        dischargedKwh = reading.dischargedKwh,
                        socPercent = reading.socPercent,
                        isCharging = reading.isCharging,
                        nowMs = now,
                        dayKey = day,
                        ignitionOn = reading.ignitionOn,
                        plugged = reading.plugged,
                        odometerKm = reading.odometerKm,
                    )
                    if (updated == log) return@collect

                    log = updated
                    GeneralData.updateChargeLog(updated)
                    store.save(updated)
                }
        }
    }

    private data class Reading(
        val counterKwh: Double,
        val dischargedKwh: Double,
        val socPercent: Double,
        val isCharging: Boolean,
        val ignitionOn: Boolean,

        /** Чи вставлений роз'єм; null — не знаємо. */
        val plugged: Boolean?,

        /** Пробіг, км; null — кадр 4F0 ще не приходив. */
        val odometerKm: Double?,
        val request: ChargeRequest,

        /** Чи підтверджено, що числа дає саме активне авто. */
        val carKnown: Boolean,
    )

    /**
     * Чи авто ввімкнене.
     *
     * Прямої ознаки запалювання шина не передає, тож беремо єдине беззаперечне
     * свідчення — рух. Авто, що їде, точно не стоїть на зарядці.
     *
     * ЧОМУ НЕ СТРУМ, хоч він і напрошується. У журналі зарядка від розетки дає
     * +10…14 А, а розгін — +57…78 А: за модулем це один і той самий бік шкали.
     * Поріг «великий струм — отже, увімкнене» спрацював би посеред живої зарядки,
     * якби ознака 581 на мить блимнула. Ціна такої помилки несиметрична: закрита
     * зарядка більше не поповнюється, і решта ночі пропаде — рівно та біда, яку
     * ми тут і лікуємо. Тому струм не бере участі.
     *
     * Випадок «зарядка скінчилась, а авто ще стоїть» ця перевірка не ловить — і не
     * мусить. Його ловить пошук пропущеної зарядки за паузою, і після того, як
     * базовий показ став зберігатися цілком, він нарешті працює.
     */
    private fun ignitionOn(vehicle: VehicleData): Boolean =
        vehicle.hasSpeed && vehicle.speedKmh > 0.0

    private companion object {
        const val UNKNOWN_CAR = "авто ще не назвало VIN — читання не зараховані"
    }
}

/**
 * «рррр-мм-дд» за місцевим годинником. Через Calendar, а не java.time: minSdk 23,
 * і тягнути десугарінг заради однієї дати не варто.
 */
internal fun localDayKey(): String {
    val now = java.util.Calendar.getInstance()
    return "%04d-%02d-%02d".format(
        now.get(java.util.Calendar.YEAR),
        now.get(java.util.Calendar.MONTH) + 1,
        now.get(java.util.Calendar.DAY_OF_MONTH),
    )
}
