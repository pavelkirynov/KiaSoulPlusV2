package com.kirianov.kiasoulevplus2.Data

import kotlin.math.abs

/**
 * ТЕСТ КОМІРОК ПІД НАВАНТАЖЕННЯМ: що з нього виходить.
 *
 * Слабку комірку в спокої не видно — напруга в неї така сама, як у сусідів.
 * Проявляється вона під струмом: власний опір більший, тож просідає вона глибше за
 * решту. Тут описано те, що з такого тесту виходить; як саме воно рахується — у
 * блоці tools/cells.
 */

/**
 * Один прохід по всіх комірках разом зі струмом, який при цьому був.
 *
 * Струм узятий ДВІЧІ — до проходу й після. Прохід триває до секунди, і за цей час
 * під розгоном струм устигає змінитися вдвічі; одне значення на початку означало б,
 * що останні тридцять комірок ми приписали чужій нагрузці. Розбіжність між двома
 * замірами й каже, чи можна цьому проходу вірити.
 */
data class CellSweep(
    val voltages: List<Double>,
    val currentBeforeA: Double,
    val currentAfterA: Double,
    val packVolts: Double,
    val atMs: Long,
) {
    val currentA: Double get() = (currentBeforeA + currentAfterA) / 2.0

    /** Наскільки струм поїхав за час проходу. */
    val currentDriftA: Double get() = abs(currentAfterA - currentBeforeA)

    val meanVolts: Double get() = if (voltages.isEmpty()) 0.0 else voltages.average()

    val powerKw: Double get() = abs(currentA) * packVolts / 1000.0
}

/**
 * Наскільки комірка гірша за сусідів.
 *
 * Висновок, а не колір. Де саме проходить межа «слабка» — питання про батарею, а не
 * про екран, тож вирішується воно там, де рахується опір, і перевіряється тестами.
 * Екранові лишається обрати, яким кольором це показати.
 */
enum class CellHealth {
    /** Струм не мінявся — сказати нічого не можна. */
    Unknown,
    Normal,
    Weak,
    Critical,
}

/** Що вийшло по одній комірці. */
data class CellVerdict(
    val index: Int,

    /** Напруга спокою: до чого крива приходить при нульовому струмі. */
    val restVolts: Double,

    /**
     * Надлишковий опір відносно середньої комірки, мОм. Додатний — комірка слабша
     * за середню. null — струм не мінявся, вивести нема з чого.
     */
    val excessMilliOhm: Double?,

    /** Найнижча напруга, яку комірка показала за тест. */
    val minVolts: Double,

    /** Найглибше відхилення від середнього по пакету за один прохід. */
    val worstDeviationVolts: Double,

    val health: CellHealth = CellHealth.Unknown,
)

/** Підсумок тесту. */
data class CellTestResult(
    val sweeps: Int = 0,
    val steadySweeps: Int = 0,
    val currentSpreadA: Double = 0.0,
    val averagePowerKw: Double = 0.0,
    val cells: List<CellVerdict> = emptyList(),

    /** Чи можна щось сказати про опір. Хибне — не привід ховати напруги. */
    val resistanceKnown: Boolean = false,

    /** Чому висновку немає, якщо його немає. Порожньо — усе гаразд. */
    val note: String = "",
) {
    val hasCells: Boolean get() = cells.isNotEmpty()

    /** Найгірша комірка за опором, якщо опір узагалі виведено. */
    val weakest: CellVerdict?
        get() = if (!resistanceKnown) null else cells.maxByOrNull { it.excessMilliOhm ?: 0.0 }
}

/** Що екран просить зробити з тестом комірок. */
enum class CellTestRequest { None, Start, Stop, Clear }

/**
 * Стан тесту.
 *
 * [sweeps] тримаються цілком, а не згорнутими в підсумок: підсумок перераховується
 * після кожного проходу, і зробити це можна лише з сирих даних. Пам'яті це коштує
 * копійки — сотня проходів по 96 чисел.
 */
data class CellTestState(
    val request: CellTestRequest = CellTestRequest.None,
    val running: Boolean = false,
    val sweeps: List<CellSweep> = emptyList(),

    /** Останній прохід: його кладе декодер, накопичує блок тесту. */
    val lastSweep: CellSweep? = null,

    val result: CellTestResult = CellTestResult(0, 0, 0.0, 0.0),
) {
    val hasSweeps: Boolean get() = sweeps.isNotEmpty()
}
