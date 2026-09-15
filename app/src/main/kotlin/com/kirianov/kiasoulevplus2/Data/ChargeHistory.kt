// ====================================================================================
// ПІДСУМКИ ІСТОРІЇ ЗАРЯДОК (ChargeHistory)
//
// Чиста логіка без стану: відфільтрувати зарядки за проміжком часу й скласти суми.
// За нею екран рахує «за подорож», «за місяць» чи будь-який власний діапазон. UI
// лише вибирає межі [fromMs, toMs] і ціну; арифметика — тут, під тестами.
//
// Живе в Data, бо це чиста логіка над [ChargeSession] і потрібна вона екрану:
// у Data це спільне надбання, а не імпорт чужого блока.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Data

object ChargeHistory {

    /**
     * Підсумок за проміжок. [energyKwh] — головне число (за шкалою заряду на корисну
     * ємність), [counterKwh] — за лічильником BMS (занижений), [socRise] — сумарний
     * приріст заряду у відсоткових пунктах.
     *
     * [costUah] — сума ВЖЕ ЗАПИСАНИХ у сесіях цін ([ChargeSession.pricePerKwh]), а
     * не energyKwh × одна ціна на весь період: у CHAdeMO й Type 1 ціни різні, тож
     * єдина ціна на весь проміжок означала б неправильну суму щойно в ньому
     * трапилися обидва роз'єми.
     */
    data class Totals(
        val count: Int,
        val energyKwh: Double,
        val counterKwh: Double,
        val socRise: Double,
        val costUah: Double,
    ) {
        val isEmpty: Boolean get() = count == 0
    }

    /** Зарядки, що завершилися в проміжку [fromMs, toMs] включно, у наявному порядку. */
    fun inRange(sessions: List<ChargeSession>, fromMs: Long, toMs: Long): List<ChargeSession> =
        sessions.filter { it.endedAtMs in fromMs..toMs }

    /** Суми по зарядках проміжку. [capacityKwh] — корисна ємність активного авто. */
    fun totals(
        sessions: List<ChargeSession>,
        capacityKwh: Double,
        fromMs: Long,
        toMs: Long,
    ): Totals {
        val range = inRange(sessions, fromMs, toMs)
        return Totals(
            count = range.size,
            energyKwh = range.sumOf { it.energyKwh(capacityKwh) },
            counterKwh = range.sumOf { it.kwh },
            socRise = range.sumOf { it.socRise },
            costUah = range.sumOf { it.costUah(capacityKwh) },
        )
    }
}
