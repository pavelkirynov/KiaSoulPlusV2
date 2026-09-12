// ====================================================================================
// ЧИСЛА В КЛІТИНКАХ І ЇХ ВІДХИЛЕННЯ (CellReadings)
//
// Екран комірок показує п'ять різних чисел на вибір, і фарбувати їх треба всі, а не
// лише те одне, яке дає тест під навантаженням. Досі колір брався тільки з вердикту
// тесту — і в режимах «ХХ», «Відхилення» та «Ввід» сітка стояла безбарвною, хоча
// саме в них найчастіше й дивляться.
//
// ВІДЛІК ІДЕ ВІД НАЙКРАЩОЇ КОМІРКИ, А НЕ ВІД СЕРЕДНЬОЇ ЧИ МЕДІАНИ. Сітка
// відповідає на питання «які комірки зіпсовані», і найкращі в пакеті здорові за
// визначенням: вони показують, на що ця хімія здатна в цьому стані заряду.
// Середнє й медіана натомість повзуть за пакетом — коли просіла третина комірок,
// медіана просідає з ними, і провал перестає виглядати провалом.
//
// ДЛЯ НАПРУГ НАЙКРАЩА — НАЙВИЩА, ДЛЯ ОПОРУ — НАЙНИЖЧИЙ. Це та сама думка, просто
// опір вимірює зіпсованість прямо, а напруга — навпаки. Відлік «від максимуму» в
// опорі означав би, що вся батарея відстає від найгіршої комірки, тобто фарбувати
// треба здорові.
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

    /**
     * Найкраща комірка в пакеті — та, від якої рахується відставання решти.
     *
     * Для напруг це найвища, для опору — найнижчий: див. пояснення в шапці файлу.
     * null — міряних комірок немає, і фарбувати нема від чого.
     */
    fun referenceOf(values: List<Double?>, mode: CellValueMode): Double? {
        val known = values.filterNotNull().filter { it.isFinite() }
        if (known.isEmpty()) return null
        return if (mode == CellValueMode.Resistance) known.min() else known.max()
    }

    /**
     * Наскільки комірка відстає від найкращої — і, отже, яким кольором її залити.
     *
     * Модуль різниці, а не знак: від найкращої всі відхилення й так в один бік, а
     * модуль заодно страхує від випадку, коли еталон узятий не з цього набору.
     *
     * Порожня клітинка не фарбується взагалі: «не міряли» мусить виглядати як
     * порожнє місце, а не як норма й не як провал.
     */
    fun levelOf(value: Double?, reference: Double?, palette: CellPalette): CellLevel {
        if (value == null || reference == null || !value.isFinite()) return CellLevel.Normal
        return palette.levelOf(abs(value - reference))
    }

    /** Скільки комірок вибилося за кожен порог: рядок під сіткою рахує саме це. */
    fun countOf(
        values: List<Double?>,
        mode: CellValueMode,
        palette: CellPalette,
    ): Map<CellLevel, Int> {
        val reference = referenceOf(values, mode)
        val levels = values.map { levelOf(it, reference, palette) }
        return CellLevel.entries.associateWith { level -> levels.count { it == level } }
    }
}
