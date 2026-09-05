// ====================================================================================
// БЛОК ГАРАЖА (GarageBlock)
//
// Веде список авто, про які застосунок щось знає, і вирішує, за яке з них рахувати.
//
// НАВІЩО ВЗАГАЛІ. Доки застосунок жив на одному телефоні й одній машині, питання
// «а це те саме авто» не існувало. Щойно з'явився намір дати APK іншій людині —
// або просто підключитися до чужої машини заради цікавості, — воно стало
// найважливішим: без нього чужі поїздки тихо домішалися б до вашої моделі й
// зіпсували б обидві.
//
// Відповідь дає VIN: він приходить із шини на запит `09 02` і однозначно називає
// машину. Щойно він відомий — авто стає активним, а сховища перемикаються на його
// теку.
//
// ЧОМУ ВИБІР РУКАМИ ПОТРІБЕН, ХОЧ VIN І САМ СЕБЕ НАЗИВАЄ. Без зв'язку VIN узяти
// нізвідки, а подивитися накопичене хочеться й удома. Тому вибір руками є — але
// тільки поки зв'язку немає: при живому авто він плутав би перегляд із обліком.
//
// Блок не чіпає чужих сховищ: він лише кладе активний VIN у GeneralData, а кожен
// блок сам переводить СВОЄ сховище й перечитує СВОЇ дані.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.garage

import com.kirianov.kiasoulevplus2.Data.CarProfile
import com.kirianov.kiasoulevplus2.Data.Garage
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class GarageBlock(
    private val store: GarageStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            GeneralData.updateGarage { (store.load() ?: Garage()).copy(loaded = true) }
            adoptDetected(scope)
            persist(scope)
        }
    }

    /**
     * VIN із шини робить своє авто активним.
     *
     * Незнайоме авто заводиться саме, без питань: питати «чи додати цю машину»
     * означало б зупинити облік до відповіді, а людина за кермом. Помилитися тут
     * дешево — зайвий запис у списку видно й видаляється, а от втрачена поїздка не
     * повертається.
     */
    private fun adoptDetected(scope: CoroutineScope) {
        GeneralData.state
            .map { it.garage.detectedVin }
            .distinctUntilChanged()
            .onEach { vin ->
                if (vin.isEmpty()) return@onEach
                GeneralData.updateGarage { garage ->
                    val known = garage.cars.firstOrNull { it.vin == vin }
                    val car = (known ?: CarProfile(vin = vin)).copy(lastSeenAtMs = nowMs())
                    garage.copy(
                        activeVin = vin,
                        cars = if (known == null) {
                            garage.cars + car
                        } else {
                            garage.cars.map { if (it.vin == vin) car else it }
                        },
                    )
                }
            }
            .launchIn(scope)
    }

    private fun persist(scope: CoroutineScope) {
        GeneralData.state
            .map { it.garage }
            // Знімок «прочитано з диска» писати назад немає сенсу. Порівнюємо без
            // detectedVin: він живе одне підключення й на диск не належить.
            .map { it.copy(detectedVin = "", share = com.kirianov.kiasoulevplus2.Data.ShareState()) }
            .distinctUntilChanged()
            .drop(1)
            .onEach(store::save)
            .launchIn(scope)
    }
}
