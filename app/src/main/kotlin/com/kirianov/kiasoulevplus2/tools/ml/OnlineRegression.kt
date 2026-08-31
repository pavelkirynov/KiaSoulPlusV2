// ====================================================================================
// ОНЛАЙН-РЕГРЕСІЯ (OnlineRegression)
//
// Зважений метод найменших квадратів, який довчається по одному спостереженню і
// нічого не пам'ятає про самі спостереження.
//
// Замість матриці коваріацій (класичний RLS) тут накопичуються **достатні
// статистики** A = Σ w·x·xᵀ та b = Σ w·x·y. Так зроблено навмисно:
//
//   • A і b — це кілька десятків чисел, які тривіально лягають у файл;
//   • перенавчання журналом дає той самий результат, що й навчання на льоту;
//   • A — сума невід'ємно визначених доданків, тож вона не може «зіпсуватися»,
//     на відміну від матриці коваріацій RLS, яку після втрати додатної
//     визначеності лишається тільки викидати. Разом із додатною діагоналлю
//     апріорних ваг система додатно визначена **за побудовою**.
//
// Апріорні знання входять як псевдоспостереження з вагою σ_шуму²/σ_апріорі²
// (звичайна MAP-оцінка): поки даних нема, відповідає фізика, а з даними вона
// плавно поступається. Ознака, якої в даних не траплялося — мороз узимку,
// поки надворі літо, — так і лишається при фізиці, замість піти в нуль.
//
// Згасання йде за **календарним** часом, а не за кількістю спостережень. Інакше
// горизонт пам'яті залежав би від того, скільки людина їздить, і в активного водія
// модель забувала б минулу зиму саме тоді, коли зима знову потрібна.
//
// Чиста математика: ні Android, ні корутин, ні сховища.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

class OnlineRegression(
    val size: Int,
    /** Коефіцієнти, з яких модель стартує, поки не бачила жодного спостереження. */
    private val prior: DoubleArray,
    /**
     * Наскільки апріорні значення непевні, у тих самих одиницях, що й коефіцієнти.
     * Це не «вага в умовних одиницях», а справжня σ: широка σ означає «здогад»,
     * вузька — «майже впевнені».
     */
    private val priorSigma: DoubleArray,
    /** Типовий розкид залишків. Задає, у скільки спостережень цінується апріорі. */
    private val noiseSigma: Double,
    /** Стала часу забування, мілісекунди календарного часу. */
    private val forgetMs: Long = FORGET_YEAR_MS,
    private val ridge: Double = RIDGE,
) {

    init {
        require(prior.size == size) { "апріорних коефіцієнтів має бути $size" }
        require(priorSigma.size == size) { "апріорних σ має бути $size" }
        require(priorSigma.all { it > 0.0 }) { "апріорна σ мусить бути додатною" }
    }

    /** A = Σ w·x·xᵀ, симетрична, зберігається повністю: матриця крихітна. */
    private val gram = DoubleArray(size * size)
    private val moment = DoubleArray(size)

    private var weightSum = 0.0
    private var residualSquareSum = 0.0
    private var absoluteResidualSum = 0.0
    private var observations = 0.0
    private var lastDecayAtMs = 0L

    private var cached: DoubleArray? = null

    /** Ваги апріорних псевдоспостережень: Λ = σ_шуму²/σ_апріорі². */
    private val priorWeight = DoubleArray(size) { noiseSigma * noiseSigma / (priorSigma[it] * priorSigma[it]) }

    /** Скільки спостережень модель фактично пам'ятає з урахуванням згасання. */
    val effectiveSamples: Double get() = observations

    /** Сумарна вага спостережень: за нею видно, коли дані переважили апріорі. */
    val effectiveWeight: Double get() = weightSum

    /**
     * Розкид залишків «спитали до того, як показали відповідь». Це чесна,
     * позавибіркова оцінка, а не залишок підігнаної моделі.
     */
    val residualSigma: Double
        get() = if (weightSum > 0.0) sqrt((residualSquareSum / weightSum).coerceAtLeast(0.0)) else 0.0

    /** Середня абсолютна помилка передбачення — те, що показуємо на екрані. */
    val meanAbsoluteResidual: Double
        get() = if (weightSum > 0.0) absoluteResidualSum / weightSum else 0.0

    fun coefficients(): DoubleArray = cached ?: solve().also { cached = it }

    fun predict(x: DoubleArray): Double = dot(coefficients(), x)

    /**
     * Частка інформації про коефіцієнт, що прийшла з даних, а не з фізики: від 0 до 1.
     * Саме це, а не кількість відрізків, чесно відповідає «чи вже вивчив».
     *
     * Показує і те, чого модель ще не бачила: узимку готовність морозного доданка
     * так і лишиться низькою, поки не настане мороз.
     */
    fun readiness(index: Int): Double {
        val posterior = Cholesky.diagonalOfInverse(totalGram(), size, index) ?: return 0.0
        val priorVariance = 1.0 / priorWeight[index]
        if (priorVariance <= 0.0) return 0.0
        return (1.0 - posterior / priorVariance).coerceIn(0.0, 1.0)
    }

    /**
     * Довчитися на одному спостереженні.
     *
     * Спершу рахується залишок за **поточними** коефіцієнтами — це і є перевірка
     * «спитали до того, як показали відповідь», і саме з неї береться помилка моделі.
     *
     * Дике значення не відкидається, а **обрізається** (винзоризація Хьюбера):
     * рядок ознак лишається в матриці, але потягнути відгук далі, ніж на
     * [OUTLIER_SIGMA] розкидів, спостереження не може.
     *
     * Різниця не косметична. Відкидання прибирає рядок цілком, і прибирає
     * **вибірково**: коли в авто змінюється опір коченню, найбільші розбіжності —
     * на високій швидкості, тобто ворота відсіюють саме ті відрізки, які єдині й
     * могли б цю зміну показати. Модель тоді повзе до нової правди роками. Обрізання
     * ж не втрачає жодного відрізка: кожен вносить свою геометрію, а вплив
     * зіпсованого обмежений.
     *
     * Розкид при цьому рахується за **справжнім** залишком, а не за обрізаним. Тому
     * стійка розбіжність сама розширює межу обрізання, ворота відчиняються, і модель
     * доганяє зміну тим швидше, чим вона більша.
     */
    fun observe(x: DoubleArray, y: Double, weight: Double, atMs: Long): Boolean {
        require(x.size == size) { "ознак має бути $size" }
        if (weight <= 0.0 || !weight.isFinite() || !y.isFinite()) return false
        if (x.any { !it.isFinite() }) return false

        // Спершу старіння, і лише потім усе інше: після довгої перерви накопичена
        // впевненість мусить осісти, інакше межа обрізання лишалася б вузькою саме
        // тоді, коли світ найімовірніше змінився.
        decayTo(atMs)

        val predicted = predict(x)
        val residual = y - predicted

        // Розкид не може бути меншим за оголошений типовий: інакше на дуже рівних
        // даних межа обрізання схлопнулась би майже в нуль, і модель проголосила б
        // упевненість, якої не буває, — а тоді будь-яка справжня зміна виглядала б
        // викидом і пробивалася б крізь обрізання роками.
        val sigma = maxOf(residualSigma, noiseSigma * MIN_SIGMA_SHARE)
        val limit = OUTLIER_SIGMA * sigma
        val trusted = observations >= OUTLIER_GUARD_AFTER
        val target = if (trusted && abs(residual) > limit) {
            predicted + limit * sign(residual)
        } else {
            y
        }

        for (row in 0 until size) {
            val weighted = weight * x[row]
            moment[row] += weighted * target
            for (column in 0 until size) {
                gram[row * size + column] += weighted * x[column]
            }
        }

        weightSum += weight
        residualSquareSum += weight * residual * residual
        absoluteResidualSum += weight * abs(residual)
        observations += 1.0

        cached = null
        return true
    }

    fun snapshot(): RegressionSnapshot = RegressionSnapshot(
        gram = gram.copyOf(),
        moment = moment.copyOf(),
        weightSum = weightSum,
        residualSquareSum = residualSquareSum,
        absoluteResidualSum = absoluteResidualSum,
        observations = observations,
        lastDecayAtMs = lastDecayAtMs,
    )

    /**
     * Підняти накопичене з файлу. Знімок не того розміру мовчки відхиляється:
     * набір ознак змінився між версіями застосунку, і старі статистики до нового
     * набору не підходять — тоді блок перечитає журнал і збере модель заново.
     */
    fun restore(snapshot: RegressionSnapshot): Boolean {
        if (snapshot.gram.size != gram.size || snapshot.moment.size != moment.size) return false
        if (snapshot.gram.any { !it.isFinite() } || snapshot.moment.any { !it.isFinite() }) return false

        snapshot.gram.copyInto(gram)
        snapshot.moment.copyInto(moment)
        weightSum = snapshot.weightSum
        residualSquareSum = snapshot.residualSquareSum
        absoluteResidualSum = snapshot.absoluteResidualSum
        observations = snapshot.observations
        lastDecayAtMs = snapshot.lastDecayAtMs
        cached = null
        return true
    }

    /**
     * Невизначеність передбачення в точці [x]: і від того, що коефіцієнти ще не
     * усталилися, і від власного розкиду даних.
     *
     * Саме по собі це число з часом прямує до розкиду даних і **не** відображає
     * усього, чого модель не бачить, — тому воно не єдине джерело інтервалу
     * на екрані. Див. `PredictionQuality`.
     */
    fun predictionSigma(x: DoubleArray): Double {
        val sigma = residualSigma
        if (sigma <= 0.0) return 0.0
        val leverage = Cholesky.quadraticFormOfInverse(totalGram(), x, size) ?: return sigma
        return sigma * sqrt(1.0 + leverage.coerceAtLeast(0.0))
    }

    /**
     * Згасання за календарем: вага спостережень старіє вдвічі приблизно за
     * [forgetMs]·ln2. Забування тут потрібне для повільного дрейфу — шини, знос
     * батареї, багажник на даху, — а не для пори року: погода є серед ознак.
     */
    private fun decayTo(atMs: Long) {
        if (lastDecayAtMs == 0L) {
            lastDecayAtMs = atMs
            return
        }
        val elapsed = atMs - lastDecayAtMs
        // Годинник телефона міг поїхати назад; від'ємний інтервал нічого не старить.
        if (elapsed <= 0L) return

        val factor = exp(-elapsed.toDouble() / forgetMs)
        if (factor >= 1.0) return

        for (index in gram.indices) gram[index] *= factor
        for (index in moment.indices) moment[index] *= factor
        weightSum *= factor
        residualSquareSum *= factor
        absoluteResidualSum *= factor
        observations *= factor
        lastDecayAtMs = atMs
        cached = null
    }

    /** A з апріорними псевдоспостереженнями і гребенем: те, що реально розв'язується. */
    private fun totalGram(): DoubleArray {
        val total = gram.copyOf()
        for (index in 0 until size) {
            total[index * size + index] += priorWeight[index] + ridge
        }
        return total
    }

    private fun solve(): DoubleArray {
        val right = DoubleArray(size) { moment[it] + priorWeight[it] * prior[it] }
        val solved = Cholesky.solve(totalGram(), right, size) ?: return prior.copyOf()
        return if (solved.all { it.isFinite() }) solved else prior.copyOf()
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (index in 0 until size) sum += a[index] * b[index]
        return sum
    }

    companion object {
        /**
         * Рік. Забування має ловити повільний дрейф, а не пори року: сезон описують
         * температурні ознаки, і швидке забування якраз завадило б їм навчитися —
         * модель ніколи не тримала б у пам'яті дві різні зими одночасно.
         */
        const val FORGET_YEAR_MS = 365L * 24 * 60 * 60 * 1000

        /** Дві зими: ємність батареї змінюється роками, а не тижнями. */
        const val FORGET_TWO_YEARS_MS = 2 * FORGET_YEAR_MS

        /** Гребінь: тримає систему розв'язною навіть за виродженого апріорі. */
        const val RIDGE = 1e-9

        /** Скільки спостережень спершу приймати беззастережно, щоб було з чим порівнювати. */
        const val OUTLIER_GUARD_AFTER = 12.0

        /** Далі за скільки розкидів спостереження вже не пускають тягнути модель. */
        const val OUTLIER_SIGMA = 4.0

        /** Нижня межа розкиду як частка оголошеного типового шуму. */
        const val MIN_SIGMA_SHARE = 0.5
    }
}

/** Накопичене регресією у вигляді, придатному для файлу. */
class RegressionSnapshot(
    val gram: DoubleArray,
    val moment: DoubleArray,
    val weightSum: Double,
    val residualSquareSum: Double,
    val absoluteResidualSum: Double,
    val observations: Double,
    val lastDecayAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegressionSnapshot) return false
        return gram.contentEquals(other.gram) &&
            moment.contentEquals(other.moment) &&
            weightSum == other.weightSum &&
            residualSquareSum == other.residualSquareSum &&
            absoluteResidualSum == other.absoluteResidualSum &&
            observations == other.observations &&
            lastDecayAtMs == other.lastDecayAtMs
    }

    override fun hashCode(): Int {
        var result = gram.contentHashCode()
        result = 31 * result + moment.contentHashCode()
        result = 31 * result + weightSum.hashCode()
        result = 31 * result + residualSquareSum.hashCode()
        result = 31 * result + absoluteResidualSum.hashCode()
        result = 31 * result + observations.hashCode()
        result = 31 * result + lastDecayAtMs.hashCode()
        return result
    }
}

/**
 * Розклад Холецького для симетричних додатно визначених матриць.
 *
 * Повертає null, а не кидає виняток. За побудовою система додатно визначена
 * (додатна діагональ апріорних ваг), тож null тут — це ознака зіпсованих даних,
 * а не буденна числова подія: викликач лишає попередні коефіцієнти.
 */
internal object Cholesky {

    /** L·Lᵀ = matrix. Повертає нижню трикутну L або null. */
    fun decompose(matrix: DoubleArray, size: Int): DoubleArray? {
        val lower = DoubleArray(size * size)
        for (row in 0 until size) {
            for (column in 0..row) {
                var sum = matrix[row * size + column]
                for (k in 0 until column) {
                    sum -= lower[row * size + k] * lower[column * size + k]
                }
                if (row == column) {
                    if (sum <= 0.0 || !sum.isFinite()) return null
                    lower[row * size + row] = sqrt(sum)
                } else {
                    lower[row * size + column] = sum / lower[column * size + column]
                }
            }
        }
        return lower
    }

    fun solve(matrix: DoubleArray, right: DoubleArray, size: Int): DoubleArray? {
        val lower = decompose(matrix, size) ?: return null
        return substitute(lower, right, size)
    }

    /** xᵀ·A⁻¹·x — важіль точки: наскільки модель екстраполює, питаючи саме тут. */
    fun quadraticFormOfInverse(matrix: DoubleArray, x: DoubleArray, size: Int): Double? {
        val solved = solve(matrix, x, size) ?: return null
        var sum = 0.0
        for (index in 0 until size) sum += x[index] * solved[index]
        return sum
    }

    /** Діагональний елемент A⁻¹: апостеріорна дисперсія одного коефіцієнта. */
    fun diagonalOfInverse(matrix: DoubleArray, size: Int, index: Int): Double? {
        val unit = DoubleArray(size)
        unit[index] = 1.0
        return solve(matrix, unit, size)?.get(index)
    }

    /** Прямий і зворотний хід: L·y = right, далі Lᵀ·result = y. */
    private fun substitute(lower: DoubleArray, right: DoubleArray, size: Int): DoubleArray? {
        val forward = DoubleArray(size)
        for (row in 0 until size) {
            var sum = right[row]
            for (k in 0 until row) sum -= lower[row * size + k] * forward[k]
            forward[row] = sum / lower[row * size + row]
        }

        val result = DoubleArray(size)
        for (row in size - 1 downTo 0) {
            var sum = forward[row]
            for (k in row + 1 until size) sum -= lower[k * size + row] * result[k]
            result[row] = sum / lower[row * size + row]
        }

        return if (result.all { it.isFinite() }) result else null
    }
}
