// ====================================================================================
// ПОРОГИ ФАРБУВАННЯ КОМІРОК (CellPalette)
//
// Комірку фарбує не саме значення, а те, НАСКІЛЬКИ ВОНО ВІДСТАЄ ВІД НАЙКРАЩОЇ в
// пакеті. Абсолютна напруга нічого не каже: на повному заряді всі комірки біля
// 4.1 В, на порожньому — біля 3.3, і однакова заливка на обох випадках означала б
// різні речі. А от «ця на тридцять мілівольт нижча за найвищу» — однакове
// твердження на будь-якому заряді.
//
// ЧОМУ ВІДЛІК ВІД НАЙКРАЩОЇ, А НЕ ВІД СЕРЕДНЬОЇ ЧИ МЕДІАНИ. Бо питання, на яке
// відповідає ця сітка, — «які комірки зіпсовані». Найвищі комірки в пакеті здорові
// за визначенням: вони показують, на що ця хімія здатна в цьому стані заряду.
// Середнє й медіана натомість повзуть за пакетом: коли просіла третина комірок,
// медіана просідає з ними, і провал перестає виглядати провалом.
//
// ЧОМУ ПОРОГИ РІЗНІ ДЛЯ РІЗНИХ РЕЖИМІВ. Це не примха налаштувань, а фізика: у
// спокої комірки розходяться на одиниці мілівольт, а під струмом — на десятки, бо
// просадка залежить від власного опору. Один порог на всі режими або пофарбував би
// у спокої весь пакет, або під навантаженням не пофарбував нічого.
//
// ЧОТИРИ ГРАДАЦІЇ, ТРИ ПОРОГИ: норма — нічого не фарбуємо, далі жовтий, оранжевий,
// червоний. Заливка тут доповнює число, а не замінює його: число лишається видним,
// бо саме за ним і роблять висновок.
// ====================================================================================

package com.kirianov.kiasoulevplus2.Data

/**
 * Три пороги відставання для одного режиму значень.
 *
 * Одиниці — ТІ САМІ, В ЯКИХ НАПИСАНО ЧИСЛО В КЛІТИНЦІ: мілівольти для напруг і
 * відхилень, мілооми для опору. Інакше налаштування довелося б перекладати в
 * голові, а порівнювати — з числом на екрані.
 */
data class CellPalette(
    /** Від цього відставання комірка жовта. */
    val warnAt: Double,

    /** Від цього — оранжева. */
    val alertAt: Double,

    /** Від цього — червона. */
    val badAt: Double,
) {
    /**
     * Пороги в порядку зростання.
     *
     * Порядок не гарантований вводом: у полях можна набрати будь-що, зокрема
     * «червоний» меншим за «жовтий». Плутати їх не можна — вийшло б, що весь пакет
     * червоний при жодному відставанні.
     */
    val steps: List<Double> get() = listOf(warnAt, alertAt, badAt).sorted()

    fun levelOf(deviation: Double): CellLevel {
        if (!deviation.isFinite()) return CellLevel.Normal
        val sorted = steps
        return when {
            deviation >= sorted[2] -> CellLevel.Bad
            deviation >= sorted[1] -> CellLevel.Alert
            deviation >= sorted[0] -> CellLevel.Warn
            else -> CellLevel.Normal
        }
    }
}

/** Наскільки комірка відстає від найкращої в пакеті. */
enum class CellLevel { Normal, Warn, Alert, Bad }

/**
 * Пороги для всіх режимів одразу.
 *
 * Типові значення взяті з живих замірів цієї машини, а не з голови: у спокої розкид
 * по пакету тримається в межах десятка мілівольт, під навантаженням доходить до
 * сотні, а надлишковий опір слабкої комірки — десяті частки мілоома при
 * середньому 0.45.
 */
data class CellPalettes(
    // КРОК BMS — 20 мВ, І ЦЕ ЗАДАЄ НИЖНЮ МЕЖУ ПОРОГІВ. Напруги комірок приходять
    // по шині цілими кроками (див. CellDecoder.VOLTS_PER_STEP), тож найменша
    // різниця, яку взагалі можна побачити, — рівно 20 мВ, і означає вона майже
    // нічого. Порог у 20 мВ фарбував усе, що на один крок нижче найкращої, тобто
    // весь пакет одразу: перший же живий екран показав 95 жовтих із 96.
    val entry: CellPalette = CellPalette(warnAt = 40.0, alertAt = 60.0, badAt = 80.0),
    val rest: CellPalette = CellPalette(warnAt = 40.0, alertAt = 60.0, badAt = 80.0),
    val underLoad: CellPalette = CellPalette(warnAt = 60.0, alertAt = 120.0, badAt = 200.0),
    val deviation: CellPalette = CellPalette(warnAt = 30.0, alertAt = 60.0, badAt = 100.0),
    val resistance: CellPalette = CellPalette(warnAt = 0.1, alertAt = 0.2, badAt = 0.35),
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
