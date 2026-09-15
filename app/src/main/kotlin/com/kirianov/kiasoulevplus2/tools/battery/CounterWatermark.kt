// ====================================================================================
// СТОРОЖ ПОЖИТТЄВИХ ЛІЧИЛЬНИКІВ (CounterWatermark)
//
// Пожиттєвий лічильник не вміє зменшуватись — це вся його суть. Якщо він упав,
// перед нами зіпсоване читання, і пускати його далі не можна: на цих числах
// будуються і крива ємності, і облік зарядок, і модель прогнозу.
//
// ЧОМУ ЦЕ НЕ ПАРАНОЯ, І ЩО ВИЯВИЛОСЯ НАСПРАВДІ. Одного разу лічильники стрибнули
// з 27131 кВт·год на 5905, а пробіг із 189420 км на 113467 — і всі числа узгоджені
// між собою так, що kWh на Ah дає правдоподібні 363 В. Спершу здалося, що це
// зіпсоване читання. Журнал сказав інше: за півтори хвилини до того телефон
// з'єднався з ІНШОЮ магнітолою. Це була справжня друга машина, а не збій.
//
// ЗВІДСИ Й ПРАВИЛЬНА ПОВЕДІНКА. Падіння лічильника — не привід мовчати, а привід
// спитати «а хто це?»: перечитати VIN. Якщо авто справді інше, гараж перемкне теку
// сам, і дані підуть куди слід. Якщо VIN той самий, а лічильники все одно нижчі —
// це заміна блока BMS, і за кілька читань новий рівень приймається.
//
// Довго мовчати не можна саме тому, що найімовірніша причина падіння — інше авто,
// а не збій. Півгодини тиші там означали б півгодини втрачених даних.
//
// Чистий Kotlin зі своїм станом і без Android: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData

class CounterWatermark(
    /** Скільки читань поспіль має триматися нижчий рівень, щоб його прийняти. */
    private val acceptAfterReadings: Int = ACCEPT_AFTER_READINGS,
) {

    private var chargedKwh = 0.0
    private var dischargedKwh = 0.0
    private var chargedAh = 0.0
    private var dischargedAh = 0.0

    /** Скільки читань поспіль лічильник тримається нижче рівня. */
    private var fallenReadings = 0

    /**
     * Чи варто перечитати VIN.
     *
     * Ставиться на першому ж падінні й знімається, щойно про нього спитали:
     * питання ставиться один раз на подію, а не на кожне читання.
     */
    var wantsVinRecheck: Boolean = false
        private set

    fun vinRecheckAsked() { wantsVinRecheck = false }

    /** Чому останнє читання відкинули. Порожньо — усе гаразд. */
    var lastRejection: String = ""
        private set

    /**
     * Чи можна пускати це читання далі.
     *
     * Читання без лічильників (їх ще не прочитали) пропускаємо: там немає чому
     * падати, а відкидати перші кадри означало б не завестися взагалі.
     */
    fun accept(bms: BmsData): Boolean {
        if (!bms.hasData) return true
        if (bms.cumulativeEnergyChargedKwh <= 0.0 && bms.cumulativeEnergyDischargedKwh <= 0.0) {
            return true
        }

        val fell = bms.cumulativeEnergyChargedKwh < chargedKwh ||
            bms.cumulativeEnergyDischargedKwh < dischargedKwh ||
            bms.cumulativeChargedAh < chargedAh ||
            bms.cumulativeDischargedAh < dischargedAh

        if (!fell) {
            fallenReadings = 0
            lastRejection = ""
            remember(bms)
            return true
        }

        // Перше падіння — привід спитати, хто це взагалі. Далі питати вже нема сенсу:
        // відповідь або прийде, або ні.
        if (fallenReadings == 0) wantsVinRecheck = true
        fallenReadings++

        // Тримається кілька читань, а VIN той самий — отже це не інше авто, а
        // заміна блока BMS. Приймаємо новий рівень.
        if (fallenReadings >= acceptAfterReadings) {
            fallenReadings = 0
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
        fallenReadings = 0
        wantsVinRecheck = false
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
         * Скільки читань поспіль чекати, перш ніж прийняти нижчий рівень.
         *
         * П'ять — це близько чотирьох секунд опитування. Достатньо, щоб за цей час
         * устигла прийти відповідь на перечитаний VIN і гараж перемкнув теку, якщо
         * авто справді інше. І достатньо мало, щоб при заміні блока BMS застосунок
         * не мовчав помітно довго.
         */
        const val ACCEPT_AFTER_READINGS = 5
    }
}
