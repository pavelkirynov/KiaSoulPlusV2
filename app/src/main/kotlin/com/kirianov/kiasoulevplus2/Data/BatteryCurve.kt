package com.kirianov.kiasoulevplus2.Data

/**
 * Крива «скільки кВт·год у батареї на цьому відсотку», зміряна по факту.
 *
 * ЧОМУ ЦЕ ОКРЕМА РІЧ, А НЕ ЧАСТИНА ПРОГНОЗУ. Прогноз запасу ходу вчиться на
 * відрізках: інтегрує миттєву потужність і вимагає неперервних даних, довжини й
 * покриття. Крива ємності таких вимог не потребує взагалі, бо міряється зовсім
 * інакше — РІЗНИЦЕЮ ПОЖИТТЄВИХ ЛІЧИЛЬНИКІВ BMS.
 *
 * Лічильник відданої енергії — абсолютне число, яке веде сама батарея. Йому
 * байдуже, чи стояв застосунок у той момент, чи обірвався Bluetooth, чи з яким
 * знаком ми прочитали струм. Якщо на 93.7 % лічильник показував X, а на 91.2 %
 * став X+0.5, то ці 2.5 % шкали містили 0.5 кВт·год — і це вимір, а не оцінка.
 *
 * Ціна такої простоти — крок лічильника 0.1 кВт·год. Тому один замір беремо не
 * раніше, ніж набіжить близько кіловат-години, а повторні проходи тим самим
 * відсотком усереднюються: що більше поїздок, то точніша крива.
 */
data class BatteryCurve(
    /**
     * Крива A — за різницею пожиттєвих лічильників, ΔkWhOut − ΔkWhIn.
     *
     * Точки через 1 % шкали. [CurvePoint.measured] каже, вимір це чи доведення.
     */
    val counterPoints: List<CurvePoint> = emptyList(),

    /**
     * Крива B — віддане за лічильником мінус рекуперація за інтегралом струму.
     *
     * Точніша, але вимоглива: інтегралу потрібен неперервний шматок спостережень,
     * тож обрив зв'язку замір рве. Порожня, поки таких шматків не набралося.
     */
    val powerPoints: List<CurvePoint> = emptyList(),

    /** Кілометри на відсоток шкали: скільки насправді проїхано на цьому відсотку. */
    val distancePoints: List<DistancePoint> = emptyList(),

    /** Межі виміряної ділянки шкали. null — не міряли ще нічого. */
    val measuredFromPercent: Double? = null,
    val measuredToPercent: Double? = null,

    /** Яку частину шкали вже виміряно, у відсотках. */
    val coveredPercent: Double = 0.0,

    /**
     * Заявлена ємність пакета, кВт·год — та, що стоїть у налаштуваннях авто.
     *
     * ЦЕ ВЕРХНЯ ТОЧКА КРИВОЇ, А НЕ ЇЇ СУМА. Крива починається звідси на ста
     * відсотках і йде вниз по виміряних нахилах; куди вона прийде на нулі — те й
     * буде зміряною ємністю ([counterCapacityKwh], [powerCapacityKwh]).
     *
     * Раніше це число було сумою кривої: невиміряне тиснулося коефіцієнтом, щоб
     * зійтися з ним. Така крива не могла суперечити заявленому — і тому нічого
     * про нього не казала.
     */
    val totalKwh: Double = 0.0,

    /** Чи зміряна повна ємність зарядкою, чи це поки аксіома. */
    val totalMeasured: Boolean = false,

    /** Скільки глибоких зарядок увійшло у вимір повної ємності. */
    val fullChargeSamples: Int = 0,

    /**
     * Крива НАПРУГИ по тій самій шкалі: напруга спокою, з якої прибрано просадку
     * під струмом.
     *
     * Навіщо вона поруч із ємністю. Саме напруга пояснює, ЧОМУ шкала нерівна:
     * BMS розкладає відсотки за заводською таблицею напруг, а комірки стоять
     * інші. Дві криві на одному полотні показують це прямо — там, де напруга йде
     * рівно, а кіловат-години ні, і сидить уся розбіжність.
     */
    val voltagePoints: List<VoltagePoint> = emptyList(),

    /** Скільки замірів форми увійшло в криву. */
    val samples: Int = 0,

    /** Скільки замірів увійшло в криву B і в криву кілометрів. */
    val powerSamples: Int = 0,
    val distanceSamples: Int = 0,

    /**
     * Скільки кВт·год набралося по всій шкалі — тобто скільки в пакеті НАСПРАВДІ,
     * якщо вірити замірам. Порівнюється з [totalKwh], заявленою в налаштуваннях.
     */
    val counterCapacityKwh: Double = 0.0,
    val powerCapacityKwh: Double? = null,

    val request: CurveRequest = CurveRequest.None,
) {
    /**
     * Крива, якій вірить решта застосунку.
     *
     * Береться B, поки в ній є заміри, інакше A. Обидві намальовані на екрані для
     * порівняння, але прогнозу потрібна одна, і це та, що не залежить від
     * лічильника прийнятої енергії.
     */
    val points: List<CurvePoint> get() = powerPoints.ifEmpty { counterPoints }

    val hasMeasurements: Boolean get() = samples > 0 && measuredFromPercent != null

    /** Скільки кВт·год лишається на заданому відсотку. */
    fun energyAt(socPercent: Double): Double? = energyAt(points, socPercent)

    /**
     * Скільки кВт·год лишається ДО НУЛЯ шкали.
     *
     * Не те саме, що [energyAt]. Крива будується згори — від заявленої ємності — і
     * на нулі шкали сідає туди, куди привели заміри, а не обов'язково в нуль. Той
     * залишок і є розбіжність між заявленим і зміряним; їхати на ньому не можна,
     * тож прогнозу дістається лише різниця.
     */
    fun usableAt(socPercent: Double): Double? {
        val here = energyAt(socPercent) ?: return null
        val bottom = points.firstOrNull()?.energyKwh ?: return null
        return (here - bottom).coerceAtLeast(0.0)
    }

    private fun energyAt(points: List<CurvePoint>, socPercent: Double): Double? {
        if (points.isEmpty()) return null
        val below = points.lastOrNull { it.socPercent <= socPercent } ?: return points.first().energyKwh
        val above = points.firstOrNull { it.socPercent >= socPercent } ?: return points.last().energyKwh
        if (above.socPercent == below.socPercent) return below.energyKwh
        val share = (socPercent - below.socPercent) / (above.socPercent - below.socPercent)
        return below.energyKwh + share * (above.energyKwh - below.energyKwh)
    }
}

/**
 * Точка кривої.
 *
 * [measured] відрізняє виміряне від доведеного. Це не косметика: на невиміряній
 * ділянці шкали крива йде середнім нахилом, і показувати її так само, як
 * зміряну, означало б брехати про те, чого не знаємо.
 */
data class CurvePoint(
    val socPercent: Double,
    val energyKwh: Double,
    val measured: Boolean,
)

/**
 * Точка кривої напруги: скільки вольтів тримає пакет на цьому відсотку шкали
 * БЕЗ навантаження.
 *
 * Просадка під струмом прибрана прямою U = U0 − I·R по замірах кошика, тому
 * точки різних поїздок можна порівнювати між собою: педаль на них більше не
 * впливає.
 */
data class VoltagePoint(
    val socPercent: Double,
    val volts: Double,
) {
    /** Те саме на комірку — так звичніше звіряти з паспортом хімії. */
    val voltsPerCell: Double get() = volts / Pack.CELLS_IN_SERIES
}

/**
 * Точка кривої «кілометри на відсоток шкали».
 *
 * Стоїть окремо від кривої ємності навмисно: це не властивість батареї, а слід
 * того, як їздили. Той самий відсоток у місті з кліматом і на трасі коштує різного
 * пробігу, тож доводити тут нема чого — точки є лише там, де справді їхали.
 */
data class DistancePoint(
    val socPercent: Double,
    val km: Double,
)

enum class CurveRequest {
    None,

    /** Забути виміряну криву й почати заново. */
    Reset,
}
