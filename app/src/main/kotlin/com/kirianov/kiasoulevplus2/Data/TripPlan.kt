// ====================================================================================
// ДАЛЕКА ДОРОГА: ЯКА ШВИДКІСТЬ ДЕШЕВША Й ШВИДША (TripPlan)
//
// На короткій дорозі питання швидкості немає: їдь як їдеш. На далекій воно
// з'являється, і відповідь неочевидна — бо швидша їзда не тільки скорочує час у
// дорозі, а й ДОДАЄ час на зарядці. Опір повітря росте як квадрат швидкості, тож
// сто тридцять з'їдають майже вдвічі більше на кілометр, ніж вісімдесят, і
// виграні хвилини повертаються назад стоянням біля станції.
//
// Рахуємо просто, зате чесно й перевірено: беремо ту саму витрату на швидкості,
// яку вивчила модель (вона вже опублікована в [RangeScenario]), і додаємо до часу
// в дорозі час на добір енергії. Гроші — за тим самим числом енергії.
//
// ЧОГО МОДЕЛЬ НЕ ЗНАЄ, І ЦЕ СКАЗАНО ПРЯМО:
//  • швидка зарядка сповільнюється ближче до вісімдесяти відсотків, а тут
//    потужність стала — тобто справжній час на зарядці буде БІЛЬШИЙ за наш;
//  • зустрічний вітер, гори й дощ у витраті не враховані — модель вивчена на
//    тому, як їздять тут;
//  • пошук станції, черга й розмова з терміналом — це [STOP_OVERHEAD_MINUTES] на
//    кожну зупинку, і це найгрубше припущення в усьому розрахунку;
//  • долив вважається від рівня, нижче якого не сідаємо, до межі швидкої зарядки
//    (85 %) — вище неї CHAdeMO на цій машині просто не йде.
//
// Чистий об'єкт без стану: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Data

/**
 * Що вийде, якщо всю дорогу тримати цю швидкість.
 *
 * [chargingHours] — час на добір енергії, якої не вистачає в пакеті. Нуль означає
 * «доїдете без зупинок»: саме тому короткі дороги й не потребують цього екрана.
 */
data class TripOption(
    val speedKmh: Double,
    val whPerKm: Double,
    val neededKwh: Double,
    val chargedKwh: Double,
    val drivingHours: Double,
    val chargingHours: Double,
    val costUah: Double,
    val stops: Int,
) {
    val totalHours: Double get() = drivingHours + chargingHours

    val reachableWithoutCharging: Boolean get() = chargedKwh <= 0.0
}

/**
 * Умови дороги: усе, що застосунок не може дізнатися сам.
 *
 * [chargerKw] — потужність тієї станції, на яку ви розраховуєте. Двадцять два для
 * змінного струму, п'ятдесят для звичайного CHAdeMO; сімдесят цей автомобіль
 * бере лише на початку зарядки, тож ставити його як середнє — обманювати себе.
 */
data class TripConditions(
    val distanceKm: Double = 0.0,
    val priceUahPerKwh: Double = 0.0,
    val chargerKw: Double = 50.0,

    /**
     * До якого заряду розряджаємось у дорозі, %.
     *
     * Не нуль, і не з обережності «на всяк випадок»: під тридцятьма відсотками
     * батарея вже не бере від станції ту потужність, на яку розрахунок сподівається,
     * а прогноз має право помилятися. Поле, а не константа, бо запас обережності в
     * кожного свій — комусь двадцять, комусь тридцять п'ять.
     */
    val arrivalSocPercent: Double = 30.0,
) {
    val ready: Boolean get() = distanceKm > 0.0 && chargerKw > 0.0
}

object TripPlanner {

    /**
     * Скільки хвилин з'їдає кожна зупинка, крім самої зарядки.
     *
     * Заїхати, під'єднатися, поговорити з терміналом, від'єднатися. П'ять хвилин —
     * стільки це займає в того, хто робить це не вперше; було п'ятнадцять, і це
     * виявилося вигадкою людини, яка на трасі не заряджалася.
     */
    const val STOP_OVERHEAD_MINUTES = 5.0

    /**
     * До якого заряду веде швидка зарядка, %.
     *
     * Вище цього CHAdeMO на цій машині просто не йде — не «невигідно», а не йде.
     * Разом із [TripConditions.arrivalSocPercent] це й задає розмір одного доливу:
     * від того, з чим приїхав, до цих вісімдесяти п'яти.
     */
    const val CHARGE_LIMIT_PERCENT = 85.0

    /**
     * Порахувати всі швидкості, які модель уміє оцінити.
     *
     * [scenarios] беруться з прогнозу як є: у них уже лежить витрата на кожній
     * швидкості, вивчена на цій машині. [capacityKwh] — корисна ємність пакета,
     * [socPercent] — реальний заряд просто зараз.
     */
    fun options(
        scenarios: List<RangeScenario>,
        capacityKwh: Double,
        socPercent: Double,
        conditions: TripConditions,
    ): List<TripOption> {
        if (!conditions.ready || capacityKwh <= 0.0) return emptyList()

        // Скільки з пакета можна витратити: від того, що є, до рівня, нижче якого
        // не сідаємо. Скільки доливають за одну зупинку: від того самого рівня до
        // межі швидкої зарядки.
        val availableKwh = share(socPercent - conditions.arrivalSocPercent) * capacityKwh
        val perStopKwh = share(CHARGE_LIMIT_PERCENT - conditions.arrivalSocPercent) * capacityKwh

        return scenarios
            .filter { it.speedKmh > 0.0 && it.whPerKm > 0.0 }
            .map { scenario -> option(scenario, availableKwh, perStopKwh, conditions) }
    }

    private fun share(percent: Double): Double = percent.coerceAtLeast(0.0) / 100.0

    private fun option(
        scenario: RangeScenario,
        availableKwh: Double,
        perStopKwh: Double,
        conditions: TripConditions,
    ): TripOption {
        val neededKwh = conditions.distanceKm * scenario.whPerKm / 1000.0
        val chargedKwh = (neededKwh - availableKwh).coerceAtLeast(0.0)

        // Зупинок стільки, скільки разів треба долити по одному доливу. Округлення
        // вгору: половини зупинки не буває. Якщо долив нульовий (заряд у дорозі
        // заданий вище за межу швидкої зарядки), зупинок не рахуємо взагалі —
        // інакше вийшло б ділення на нуль замість числа.
        val stops = when {
            chargedKwh <= 0.0 -> 0
            perStopKwh <= 0.0 -> 0
            else -> ((chargedKwh - 0.01) / perStopKwh).toInt() + 1
        }

        return TripOption(
            speedKmh = scenario.speedKmh,
            whPerKm = scenario.whPerKm,
            neededKwh = neededKwh,
            chargedKwh = chargedKwh,
            drivingHours = conditions.distanceKm / scenario.speedKmh,
            chargingHours = chargedKwh / conditions.chargerKw +
                stops * STOP_OVERHEAD_MINUTES / MINUTES_PER_HOUR,
            costUah = chargedKwh * conditions.priceUahPerKwh,
            stops = stops,
        )
    }

    /** Найшвидша дорога загалом: час у дорозі плюс час на зарядці. */
    fun fastest(options: List<TripOption>): TripOption? = options.minByOrNull { it.totalHours }

    /**
     * Найдешевша дорога.
     *
     * Серед варіантів, які взагалі потребують зарядки, це завжди найповільніший:
     * менше витрата — менше доливати. Тому окремо цікавий не сам мінімум, а
     * РІЗНИЦЯ з обраною швидкістю, і саме її показує [compare].
     */
    fun cheapest(options: List<TripOption>): TripOption? = options.minByOrNull { it.costUah }

    /**
     * Що ви втрачаєте й що виграєте, обравши [chosen] замість [reference].
     *
     * Знак навмисно від «обраного»: додатні години означають, що обране ДОВШЕ, а
     * додатні гривні — що дорожче.
     */
    fun compare(chosen: TripOption, reference: TripOption): TripDifference = TripDifference(
        hours = chosen.totalHours - reference.totalHours,
        drivingHours = chosen.drivingHours - reference.drivingHours,
        chargingHours = chosen.chargingHours - reference.chargingHours,
        costUah = chosen.costUah - reference.costUah,
    )

    private const val MINUTES_PER_HOUR = 60.0
}

/** Різниця між двома швидкостями: час і гроші. */
data class TripDifference(
    val hours: Double,
    val drivingHours: Double,
    val chargingHours: Double,
    val costUah: Double,
)
