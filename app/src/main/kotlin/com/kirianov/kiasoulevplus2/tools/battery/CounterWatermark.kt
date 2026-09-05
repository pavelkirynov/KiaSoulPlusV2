// ====================================================================================
// СТОРОЖ ПОЖИТТЄВИХ ЛІЧИЛЬНИКІВ (CounterWatermark)
//
// Пожиттєвий лічильник не вміє зменшуватись — це вся його суть. Якщо він упав,
// перед нами зіпсоване читання, і пускати його далі не можна: на цих числах
// будуються і крива ємності, і облік зарядок, і модель прогнозу.
//
// ЧОМУ ЦЕ НЕ ПАРАНОЯ. Одного разу на шині на три хвилини з'явився ЗВ'ЯЗНИЙ набір
// чужих чисел: 5905 кВт·год замість 27131, 113467 км замість 189420, і всі
// лічильники узгоджені між собою так, ніби це справді інше авто — kWh на Ah дає
// правдоподібні 363 В. Не сміття, яке видно з першого погляду, а цілком осмислені
// числа. Застосунок прийняв їх за нову точку відліку й утратив відкриту сесію
// зарядки.
//
// ЧОМУ ПРОСТО «ВІДКИНУТИ» НЕДОСИТЬ. Лічильник МОЖЕ обнулитися по-справжньому —
// заміна блока BMS, скидання пам'яті. Тоді застосунок, який відкидає все нижче
// колишнього рівня, замовк би назавжди. Тому падіння приймається, але лише коли
// воно тримається довго: збій тривав три хвилини, а справжнє обнулення не мине
// ніколи. Півгодини впевнено розрізняють ці два випадки.
//
// Чистий Kotlin зі своїм станом і без Android: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData

class CounterWatermark(
    /** Скільки падіння має протриматись, щоб повірити в справжнє обнулення. */
    private val acceptAfterMs: Long = ACCEPT_AFTER_MS,
) {

    private var chargedKwh = 0.0
    private var dischargedKwh = 0.0
    private var chargedAh = 0.0
    private var dischargedAh = 0.0

    /** Коли вперше побачили падіння, яке ще не прийняли. Нуль — падіння немає. */
    private var droppedSinceMs = 0L

    /** Чому останнє читання відкинули. Порожньо — усе гаразд. */
    var lastRejection: String = ""
        private set

    /**
     * Чи можна пускати це читання далі.
     *
     * Читання без лічильників (їх ще не прочитали) пропускаємо: там немає чому
     * падати, а відкидати перші кадри означало б не завестися взагалі.
     */
    fun accept(bms: BmsData, nowMs: Long): Boolean {
        if (!bms.hasData) return true
        if (bms.cumulativeEnergyChargedKwh <= 0.0 && bms.cumulativeEnergyDischargedKwh <= 0.0) {
            return true
        }

        val fell = bms.cumulativeEnergyChargedKwh < chargedKwh ||
            bms.cumulativeEnergyDischargedKwh < dischargedKwh ||
            bms.cumulativeChargedAh < chargedAh ||
            bms.cumulativeDischargedAh < dischargedAh

        if (!fell) {
            droppedSinceMs = 0L
            lastRejection = ""
            remember(bms)
            return true
        }

        if (droppedSinceMs == 0L) droppedSinceMs = nowMs

        // Тримається довго — отже це не збій, а справжнє обнулення лічильників.
        if (nowMs - droppedSinceMs >= acceptAfterMs) {
            droppedSinceMs = 0L
            lastRejection = ""
            reset(bms)
            return true
        }

        lastRejection = "лічильник упав із ${round(dischargedKwh)} до " +
            "${round(bms.cumulativeEnergyDischargedKwh)} кВт·год — читання відкинуто"
        return false
    }

    /** Інше авто — інші лічильники. Падіння тут законне й миттєве. */
    fun forgetCar() {
        chargedKwh = 0.0
        dischargedKwh = 0.0
        chargedAh = 0.0
        dischargedAh = 0.0
        droppedSinceMs = 0L
        lastRejection = ""
    }

    private fun remember(bms: BmsData) {
        chargedKwh = maxOf(chargedKwh, bms.cumulativeEnergyChargedKwh)
        dischargedKwh = maxOf(dischargedKwh, bms.cumulativeEnergyDischargedKwh)
        chargedAh = maxOf(chargedAh, bms.cumulativeChargedAh)
        dischargedAh = maxOf(dischargedAh, bms.cumulativeDischargedAh)
    }

    private fun reset(bms: BmsData) {
        chargedKwh = bms.cumulativeEnergyChargedKwh
        dischargedKwh = bms.cumulativeEnergyDischargedKwh
        chargedAh = bms.cumulativeChargedAh
        dischargedAh = bms.cumulativeDischargedAh
    }

    private fun round(value: Double): String = (kotlin.math.round(value * 10.0) / 10.0).toString()

    companion object {
        /**
         * Півгодини. Збій у журналі тривав три хвилини; справжнє обнулення не мине
         * ніколи. Між цими двома півгодини лежать із великим запасом з обох боків.
         */
        const val ACCEPT_AFTER_MS = 30 * 60 * 1000L
    }
}
