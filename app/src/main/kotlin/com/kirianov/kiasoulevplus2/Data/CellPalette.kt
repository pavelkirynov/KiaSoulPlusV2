// ====================================================================================
// ПОРОГИ ФАРБУВАННЯ КОМІРОК (CellPalette)
//
// Комірку фарбує не саме значення, а те, НАСКІЛЬКИ ВОНО ВІДРІЗНЯЄТЬСЯ від решти
// пакета. Абсолютна напруга нічого не каже: на повному заряді всі комірки біля
// 4.1 В, на порожньому — біля 3.3, і однакова заливка на обох випадках
// означала б різні речі. А от «ця на тридцять мілівольт нижча за середню» —
// однакове твердження на будь-якому заряді.
//
// ЧОМУ ПОРОГИ РІЗНІ ДЛЯ РІЗНИХ РЕЖИМІВ. Це не примха налаштувань, а фізика: у
// спокої комірки розходяться на одиниці мілівольт, а під струмом — на десятки,
// бо просадка залежить від власного опору. Один порог на всі режими або
// пофарбував би в спокої весь пакет, або під навантаженням не пофарбував нічого.
//
// ТРИ ГРАДАЦІЇ, ДВА ПОРОГИ: норма — нічого не фарбуємо, підозра — жовтим, погано —
// червоним. Заливка тут доповнює число, а не замінює його: число лишається
// видним, бо саме за ним і роблять висновок.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Data

/**
 * Два пороги відхилення для одного режиму значень.
 *
 * Одиниці — ТІ САМІ, В ЯКИХ НАПИСАНО ЧИСЛО В КЛІТИНЦІ: мілівольти для напруг і
 * відхилень, мілооми для опору. Інакше налаштування довелося б перекладати в
 * голові, а порівнювати — з числом на екрані.
 */
data class CellPalette(
    /** Від цього відхилення комірка жовта. */
    val warnAt: Double,

    /** Від цього — червона. */
    val badAt: Double,
) {
    /** Порядок порогів не гарантований вводом: у полі можна набрати будь-що. */
    val low: Double get() = minOf(warnAt, badAt)
    val high: Double get() = maxOf(warnAt, badAt)

    fun levelOf(deviation: Double): CellLevel = when {
        !deviation.isFinite() -> CellLevel.Normal
        deviation >= high -> CellLevel.Bad
        deviation >= low -> CellLevel.Warn
        else -> CellLevel.Normal
    }
}

/** Наскільки комірка вибивається з пакета. */
enum class CellLevel { Normal, Warn, Bad }

/**
 * Пороги для всіх режимів одразу.
 *
 * Типові значення взяті з живих замірів цієї машини, а не з голови: у спокої
 * розкид по пакету тримається в межах десятка мілівольт, під навантаженням
 * доходить до сотні, а надлишковий опір слабкої комірки — десяті частки мілоома
 * при середньому 0.45.
 */
data class CellPalettes(
    val entry: CellPalette = CellPalette(warnAt = 15.0, badAt = 30.0),
    val rest: CellPalette = CellPalette(warnAt = 15.0, badAt = 30.0),
    val underLoad: CellPalette = CellPalette(warnAt = 60.0, badAt = 120.0),
    val deviation: CellPalette = CellPalette(warnAt = 30.0, badAt = 60.0),
    val resistance: CellPalette = CellPalette(warnAt = 0.1, badAt = 0.25),
) {
    fun of(mode: CellValueMode): CellPalette = when (mode) {
        CellValueMode.Entry -> entry
        CellValueMode.Rest -> rest
        CellValueMode.UnderLoad -> underLoad
        CellValueMode.Deviation -> deviation
        CellValueMode.Resistance -> resistance
    }

    fun with(mode: CellValueMode, palette: CellPalette): CellPalettes = when (mode) {
        CellValueMode.Entry -> copy(entry = palette)
        CellValueMode.Rest -> copy(rest = palette)
        CellValueMode.UnderLoad -> copy(underLoad = palette)
        CellValueMode.Deviation -> copy(deviation = palette)
        CellValueMode.Resistance -> copy(resistance = palette)
    }

    /** Одиниці, в яких задаються пороги цього режиму: вони ж стоять у клітинці. */
    fun unitOf(mode: CellValueMode): String =
        if (mode == CellValueMode.Resistance) "мОм" else "мВ"
}
