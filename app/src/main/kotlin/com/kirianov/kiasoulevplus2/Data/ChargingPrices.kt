package com.kirianov.kiasoulevplus2.Data

/**
 * Дефолтна ціна зарядки, окремо для кожного роз'єму.
 *
 * Роз'єм — головна межа зарядки (див. [ChargeConnector]): CHAdeMO зазвичай платна
 * швидка станція, Type 1 найчастіше домашня розетка чи повільний зарядний, і ціни в
 * них не мають нічого спільного. Тому цін дві, а не одна.
 *
 * ЦЕ ЛИШЕ ЗАМОВЧУВАННЯ. Ціна конкретної сесії фіксується в [ChargeSession] у момент
 * її закриття (див. [ChargeTracker]) і далі не залежить від зміни цих чисел заднім
 * числом — інакше правка ціни в налаштуваннях переписувала б вартість уже минулих
 * зарядок.
 */
data class ChargingPrices(
    val chademoUahPerKwh: Double = 0.0,
    val type1UahPerKwh: Double = 0.0,
) {
    /**
     * Дефолтна ціна для роз'єму сесії.
     *
     * [ChargeConnector.BOTH] і [ChargeConnector.UNKNOWN] дають 0.0 — не вгадуємо,
     * якою саме ціною рахувати, коли сесія зачепила обидва роз'єми чи роз'єм так і
     * не побачили. Чесніше показати «вартість невідома» й дати поправити вручну в
     * журналі, ніж мовчки взяти одну з двох цін навмання.
     */
    fun forConnector(connector: ChargeConnector): Double = when (connector) {
        ChargeConnector.CHADEMO -> chademoUahPerKwh
        ChargeConnector.TYPE1 -> type1UahPerKwh
        ChargeConnector.BOTH, ChargeConnector.UNKNOWN -> 0.0
    }
}
