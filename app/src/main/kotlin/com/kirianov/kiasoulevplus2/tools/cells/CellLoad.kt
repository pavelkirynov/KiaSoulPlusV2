// ====================================================================================
// ТЕСТ КОМІРОК ПІД НАВАНТАЖЕННЯМ (CellLoad)
//
// Слабку комірку в спокої не видно. Напруга в неї така сама, як у сусідів, і
// розбаланс на стоянці нічого не каже. Проявляється вона під струмом: власний опір
// більший, тож просідає вона глибше за решту. Тому тест і робиться під
// навантаженням — і саме тому одного заміру для нього не досить.
//
// ЩО МІРЯЄМО НАСПРАВДІ. Не мінімум напруги, хоч він і напрошується. Мінімум комірки
// №1 припаде на пік струму, мінімум комірки №90 — на інший момент, бо повний прохід
// по 96 комірках займає три запити й до секунди часу. Різниця між такими мінімумами
// покаже не стан комірок, а те, у яку фазу розгону їх устигли прочитати.
//
// Міряємо ВІДХИЛЕННЯ ВІД СЕРЕДНЬОГО по тому самому проходу — струм у ньому
// скорочується, бо діє на всі комірки однаково, — і те, як це відхилення росте зі
// струмом. Нахил «вольти на ампер» і є надлишковий опір комірки, у мілліомах. Це
// той самий метод, яким із кривої напруги вичищається просадка.
//
// ЗНАК СТРУМУ ВИВОДИТЬСЯ З ДАНИХ, А НЕ ПРИЙМАЄТЬСЯ НА ВІРУ. У журналі цього авто
// однаково додатний струм траплявся і на розгоні, і на зарядці, тож домовлятися про
// знак наперед — прямий шлях до моделі навиворіт, на чому проєкт уже обпікався.
// Натомість дивимось, куди їде СЕРЕДНЯ напруга пакета: під розрядом вона мусить
// просідати. Куди вона поїхала, той бік струму й є розряд.
//
// І головне: тест уміє сказати «висновку немає». Якщо людина натиснула «старт»,
// постояла на місці й натиснула «стоп», струм не мінявся — і жодного опору з таких
// даних не виводиться. Намалювати різнокольорові комірки в цьому випадку було б
// гірше, ніж не намалювати нічого.
//
// Чистий Kotlin без Android: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.cells

import com.kirianov.kiasoulevplus2.Data.CellHealth
import com.kirianov.kiasoulevplus2.Data.CellSweep
import com.kirianov.kiasoulevplus2.Data.CellTestResult
import com.kirianov.kiasoulevplus2.Data.CellVerdict
import kotlin.math.abs

object CellLoad {

    /**
     * Наскільки має розійтися струм за тест, щоб з нього виводився опір, А.
     *
     * Двадцять ампер — це вже помітний розгін проти стоянки, і на такому розмаху
     * різниця в кілька мілліомів дає десятки мілівольт, тобто більше за крок
     * читання. Менший розмах означає ділення на майже нуль: нахил вийде, але
     * означатиме він шум.
     */
    const val MIN_CURRENT_SPREAD_A = 20.0

    /**
     * Наскільки струм може поїхати ЗА ЧАС одного проходу, щоб проходу ще вірити, А.
     *
     * Прохід по 96 комірках — три запити й до секунди. Якщо за цю секунду струм
     * змінився більше, ніж на десять ампер, комірки з початку й кінця проходу
     * бачили різне навантаження, і порівнювати їх між собою вже не можна.
     */
    const val MAX_SWEEP_DRIFT_A = 10.0

    /** Менше проходів — і жодної прямої по них не побудувати. */
    const val MIN_SWEEPS = 4

    /** Комірка, слабша за середню на стільки, вже варта окремої уваги, мОм. */
    const val WEAK_EXCESS_MILLI_OHM = 0.5

    /** А на стільки — це вже привід дивитися на неї всерйоз, мОм. */
    const val CRITICAL_EXCESS_MILLI_OHM = 1.5

    /**
     * Наскільки мінімум комірки може бути нижчим за медіанний, щоб це ще не
     * турбувало, В.
     *
     * Числа тут ЗУМИСНО попередні, і це чесніше сказати прямо, ніж видати їх за
     * істину. Двадцять мілівольт узято з того, що звичайний розбаланс справного
     * пакета на око не виходить за десяток-другий; п'ятдесят — з того, що така
     * різниця під навантаженням уже помітна очима в журналі. Уточнити їх можна
     * лише на живих тестах цієї батареї, і саме заради цього обидва погляди —
     * і за опором, і за мінімумом — лежать поруч.
     */
    const val WEAK_BELOW_MEDIAN_VOLTS = 0.020
    const val CRITICAL_BELOW_MEDIAN_VOLTS = 0.050

    /**
     * Підсумок тесту з набраних проходів.
     *
     * Напруги повертаються завжди, навіть коли опір вивести нема з чого: мінімум за
     * тест — теж корисне число, просто інше. А ось опір — лише коли струм справді
     * мінявся, і про відмову сказано словами.
     */
    fun summarize(sweeps: List<CellSweep>): CellTestResult {
        if (sweeps.isEmpty()) {
            return CellTestResult(0, 0, 0.0, 0.0, note = "Замірів немає")
        }

        val steady = sweeps.filter { it.currentDriftA <= MAX_SWEEP_DRIFT_A && it.voltages.isNotEmpty() }
        val currents = steady.map { it.currentA }
        val spread = if (currents.isEmpty()) 0.0 else (currents.max() - currents.min())
        val power = sweeps.map { it.powerKw }.average()

        val cellCount = sweeps.maxOf { it.voltages.size }
        if (cellCount == 0) {
            return CellTestResult(sweeps.size, steady.size, spread, power, note = "Напруг не прочитано")
        }

        val basic = withMinimumHealth((0 until cellCount).map { index -> basicVerdict(index, sweeps) })

        val enough = steady.size >= MIN_SWEEPS && spread >= MIN_CURRENT_SPREAD_A
        if (!enough) {
            return CellTestResult(
                sweeps = sweeps.size,
                steadySweeps = steady.size,
                currentSpreadA = spread,
                averagePowerKw = power,
                cells = basic,
                resistanceKnown = false,
                note = whyNoResistance(steady.size, spread),
            )
        }

        return CellTestResult(
            sweeps = sweeps.size,
            steadySweeps = steady.size,
            currentSpreadA = spread,
            averagePowerKw = power,
            cells = basic.map { verdict ->
                val excess = excessOf(verdict.index, steady)
                verdict.copy(excessMilliOhm = excess, health = healthOf(excess))
            },
            resistanceKnown = true,
        )
    }

    /**
     * Наскільки комірка гірша за сусідів.
     *
     * Межі не з паспорта — його на перепаковані комірки й не буває, — а з того, що
     * взагалі означає різниця в опорі. Півміліома на комірку при сотні ампер це вже
     * п'ятдесят мілівольт розбіжності під навантаженням: помітно на око в журналі й
     * достатньо, щоб така комірка першою впиралася в нижню межу BMS. Півтора —
     * утричі більше, і на розгоні така комірка тягне вниз усю шкалу.
     */
    private fun healthOf(excessMilliOhm: Double?): CellHealth = when {
        excessMilliOhm == null -> CellHealth.Unknown
        excessMilliOhm >= CRITICAL_EXCESS_MILLI_OHM -> CellHealth.Critical
        excessMilliOhm >= WEAK_EXCESS_MILLI_OHM -> CellHealth.Weak
        else -> CellHealth.Normal
    }

    /**
     * Другий погляд: наскільки мінімум комірки нижчий за мінімум середньої.
     *
     * Медіана, а не середнє. Кілька провалених комірок тягнуть середнє за собою, і
     * на тлі просілого середнього вони самі виглядають нормальними — рівно та вада,
     * через яку розбаланс «у середньому по пакету» нічого й не показує.
     *
     * Працює завжди, навіть коли струм не мінявся: мінімум — це просто найнижче
     * побачене число, для нього ні прямої, ні розгонів не треба.
     */
    private fun withMinimumHealth(cells: List<CellVerdict>): List<CellVerdict> {
        val minima = cells.map { it.minVolts }.filter { it > 0.0 }.sorted()
        if (minima.isEmpty()) return cells
        val median = minima[minima.size / 2]

        return cells.map { verdict ->
            if (verdict.minVolts <= 0.0) return@map verdict
            val below = median - verdict.minVolts
            verdict.copy(
                minBelowMedianVolts = below,
                minHealth = when {
                    below >= CRITICAL_BELOW_MEDIAN_VOLTS -> CellHealth.Critical
                    below >= WEAK_BELOW_MEDIAN_VOLTS -> CellHealth.Weak
                    else -> CellHealth.Normal
                },
            )
        }
    }

    private fun whyNoResistance(steady: Int, spread: Double): String = when {
        steady < MIN_SWEEPS -> "Проходів замало: $steady із $MIN_SWEEPS. " +
            "Тримайте зв'язок і дайте тесту попрацювати довше."
        else -> "Струм за тест розійшовся лише на ${round(spread)} А. " +
            "Опір із цього не виводиться — потрібен розгін, а не стоянка."
    }

    /** Те, що видно з напруг незалежно від струму. */
    private fun basicVerdict(index: Int, sweeps: List<CellSweep>): CellVerdict {
        var min = Double.MAX_VALUE
        var worst = 0.0
        var sum = 0.0
        var count = 0

        sweeps.forEach { sweep ->
            val volts = sweep.voltages.getOrNull(index) ?: return@forEach
            if (volts <= 0.0) return@forEach
            if (volts < min) min = volts
            val deviation = volts - sweep.meanVolts
            if (deviation < worst) worst = deviation
            sum += volts
            count++
        }

        return CellVerdict(
            index = index,
            restVolts = if (count == 0) 0.0 else sum / count,
            excessMilliOhm = null,
            minVolts = if (min == Double.MAX_VALUE) 0.0 else min,
            worstDeviationVolts = worst,
        )
    }

    /**
     * Надлишковий опір комірки відносно середньої, мОм.
     *
     * Рахується по ВІДХИЛЕННЮ від середнього по пакету, а не по самій напрузі. Так
     * із даних зникає все спільне — просадка від струму, дрейф SOC за час тесту, —
     * і лишається рівно те, чим ця комірка відрізняється від сусідів.
     *
     * Знак струму тут не має значення взагалі: нахил відхилення береться за модулем
     * струму, а модуль однаковий, з якого боку не домовляйся.
     */
    private fun excessOf(index: Int, steady: List<CellSweep>): Double? {
        val points = steady.mapNotNull { sweep ->
            val volts = sweep.voltages.getOrNull(index)?.takeIf { it > 0.0 } ?: return@mapNotNull null
            abs(sweep.currentA) to (volts - sweep.meanVolts)
        }
        if (points.size < MIN_SWEEPS) return null

        val slope = slopeOf(points) ?: return null
        // Відхилення падає зі струмом у слабкої комірки, тож нахил у неї від'ємний.
        // Опір хочеться бачити додатним саме в слабкої — звідси мінус.
        return -slope * 1000.0
    }

    /** Нахил прямої найменших квадратів. null — по осі X усе стоїть на місці. */
    private fun slopeOf(points: List<Pair<Double, Double>>): Double? {
        val meanX = points.sumOf { it.first } / points.size
        val meanY = points.sumOf { it.second } / points.size
        var top = 0.0
        var bottom = 0.0
        points.forEach { (x, y) ->
            val dx = x - meanX
            top += dx * (y - meanY)
            bottom += dx * dx
        }
        return if (bottom <= 0.0) null else top / bottom
    }

    private fun round(value: Double): String = (kotlin.math.round(value * 10.0) / 10.0).toString()
}
