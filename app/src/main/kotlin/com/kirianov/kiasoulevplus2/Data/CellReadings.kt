// ====================================================================================
// ЧИСЛА В КЛІТИНКАХ І ЇХ ВІДХИЛЕННЯ (CellReadings)
//
// Екран комірок показує п'ять різних чисел на вибір, і фарбувати їх треба всі, а не
// лише те одне, яке дає тест під навантаженням. Досі колір брався тільки з вердикту
// тесту — і в режимах «ХХ», «Відхилення» та «Ввід» сітка стояла безбарвною, хоча
// саме в них найчастіше й дивляться.
//
// ВІДХИЛЕННЯ БЕРЕТЬСЯ ВІД МЕДІАНИ, А НЕ ВІД СЕРЕДНЬОГО. Кілька провалених комірок
// тягнуть середнє за собою: на тлі просілого середнього вони перестають виглядати
// провалом, а решта пакета натомість починає. Медіану вони не тягнуть.
//
// І ВІДХИЛЕННЯ БЕРЕТЬСЯ ЗА МОДУЛЕМ. Комірка, яка вибилася вгору, — така сама
// ознака розбалансу, як і та, що просіла; яка з них зіпсована, каже не колір, а
// саме число, і воно лишається на екрані.
//
// Чистий об'єкт без стану: перевіряється тестами.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Data

import kotlin.math.abs

object CellReadings {

    /**
     * Число, яке стоїть у клітинці, в одиницях свого режиму: мілівольти для
     * напруг і відхилень, мілооми для опору. null — цього числа немає, і це не
     * те саме, що нуль.
     */
    fun valueOf(
        index: Int,
        mode: CellValueMode,
        cells: CellData,
        manual: ManualCells,
        test: CellTestState,
    ): Double? {
        val verdict = test.result.cells.getOrNull(index)?.takeIf { it.index == index }

        // ВВІД І СПОКІЙ — ЦЕ ТА САМА ВЕЛИЧИНА, і коли вердикту немає, обидва беруть
        // її з того, що видно в клітинці: спершу прочитане з шини, потім набране
        // руками. Без цього збережений простий замір лишався і безбарвним, і
        // порожнім: напруги в ньому є, а тесту під навантаженням у ньому немає.
        if (mode == CellValueMode.Entry || (mode == CellValueMode.Rest && verdict == null)) {
            val fromBus = cells.cellVoltages.getOrNull(index)?.takeIf { it > 0.0 }
            val voltage = fromBus ?: manual.voltageAt(index).takeIf { it > 0.0 }
            return voltage?.times(1000.0)
        }

        if (verdict == null) return null
        return when (mode) {
            CellValueMode.Entry -> null
            CellValueMode.Rest -> verdict.restVolts.takeIf { it > 0.0 }?.times(1000.0)
            CellValueMode.UnderLoad -> verdict.minVolts.takeIf { it > 0.0 }?.times(1000.0)
            CellValueMode.Deviation -> verdict.worstDeviationVolts * 1000.0
            CellValueMode.Resistance -> verdict.excessMilliOhm
        }
    }

    /** Ті самі числа для всього пакета, у порядку опитування. */
    fun allOf(
        mode: CellValueMode,
        cells: CellData,
        manual: ManualCells,
        test: CellTestState,
    ): List<Double?> = (0 until Pack.CELLS_IN_SERIES).map { valueOf(it, mode, cells, manual, test) }

    /** Медіана відомих чисел; null — відомих немає. */
    fun medianOf(values: List<Double?>): Double? {
        val known = values.filterNotNull().filter { it.isFinite() }.sorted()
        if (known.isEmpty()) return null
        val middle = known.size / 2
        return if (known.size % 2 == 1) {
            known[middle]
        } else {
            (known[middle - 1] + known[middle]) / 2.0
        }
    }

    /**
     * Наскільки комірка вибивається з пакета — і, отже, яким кольором її залити.
     *
     * Порожня клітинка не фарбується взагалі: «не міряли» мусить виглядати як
     * порожнє місце, а не як норма й не як провал.
     */
    fun levelOf(value: Double?, median: Double?, palette: CellPalette): CellLevel {
        if (value == null || median == null || !value.isFinite()) return CellLevel.Normal
        return palette.levelOf(abs(value - median))
    }

    /** Скільки комірок вибилося за пороги: рядок під сіткою рахує саме це. */
    fun countOf(values: List<Double?>, palette: CellPalette): Map<CellLevel, Int> {
        val median = medianOf(values)
        val levels = values.map { levelOf(it, median, palette) }
        return CellLevel.entries.associateWith { level -> levels.count { it == level } }
    }
}
