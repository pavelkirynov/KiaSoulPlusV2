package com.kirianov.kiasoulevplus2.tools.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRegressionTest {

    /** Найпростіша перевірка: чи знаходить регресія коефіцієнти, які в дані закладені. */
    @Test
    fun `recovers the coefficients hidden in the data`() {
        val regression = regression(prior = doubleArrayOf(0.0, 0.0), sigma = doubleArrayOf(10.0, 10.0))

        // y = 3 + 2x
        (0..60).forEach { index ->
            val x = index % 10 + 1.0
            regression.observe(doubleArrayOf(1.0, x), 3.0 + 2.0 * x, weight = 1.0, atMs = index * 1000L)
        }

        val theta = regression.coefficients()
        assertEquals(3.0, theta[0], 0.05)
        assertEquals(2.0, theta[1], 0.02)
    }

    /** Без даних відповідає апріорі: перший день має бути осмисленим, а не нулем. */
    @Test
    fun `answers with the prior while it has seen nothing`() {
        val regression = regression(prior = doubleArrayOf(5.0, 1.0), sigma = doubleArrayOf(1.0, 1.0))

        // Гребінь зсуває розв'язок на мільярдні частки — це його робота, не помилка.
        assertEquals(5.0, regression.coefficients()[0], 1e-6)
        assertEquals(7.0, regression.predict(doubleArrayOf(1.0, 2.0)), 1e-6)
    }

    /** Дані мусять переважити апріорі, а не змагатися з ним вічно. */
    @Test
    fun `data eventually outweighs the prior`() {
        val regression = regression(prior = doubleArrayOf(5.0, 0.0), sigma = doubleArrayOf(1.0, 1.0))

        repeat(400) { index ->
            regression.observe(doubleArrayOf(1.0, 0.0), 1.0, weight = 1.0, atMs = index * 1000L)
        }

        assertEquals(1.0, regression.coefficients()[0], 0.1)
    }

    /** Ознака, якої в даних не траплялося, лишається при фізиці, а не падає в нуль. */
    @Test
    fun `a feature the data never exercised keeps its prior`() {
        val regression = regression(prior = doubleArrayOf(0.0, 9.0), sigma = doubleArrayOf(5.0, 0.5))

        // Друга ознака завжди нульова: даних про неї нема жодних.
        repeat(200) { index ->
            regression.observe(doubleArrayOf(1.0, 0.0), 2.0, weight = 1.0, atMs = index * 1000L)
        }

        assertEquals("перша вивчилася", 2.0, regression.coefficients()[0], 0.1)
        assertEquals("друга лишилася апріорною", 9.0, regression.coefficients()[1], 0.2)
        assertTrue("і це видно з готовності", regression.readiness(1) < 0.1)
        assertTrue(regression.readiness(0) > 0.8)
    }

    /** Викид відкидається, а не вчить модель неправди. */
    @Test
    fun `a wild observation is refused`() {
        val regression = regression(prior = doubleArrayOf(0.0, 0.0), sigma = doubleArrayOf(10.0, 10.0))
        repeat(40) { index ->
            val x = index % 5 + 1.0
            regression.observe(doubleArrayOf(1.0, x), 2.0 * x, weight = 1.0, atMs = index * 1000L)
        }

        val before = regression.predict(doubleArrayOf(1.0, 3.0))
        val accepted = regression.observe(doubleArrayOf(1.0, 3.0), 900.0, weight = 1.0, atMs = 99_000L)

        assertFalse(accepted)
        // Час усе одно минув, тож накопичене трохи постаріло — але дикого значення
        // модель не всмоктала: передбачення лишилося там, де було.
        assertEquals(6.0, before, 0.05)
        assertEquals(before, regression.predict(doubleArrayOf(1.0, 3.0)), 1e-6)
    }

    /** Збережене й підняте з файлу має давати ті самі коефіцієнти. */
    @Test
    fun `a saved model comes back the same`() {
        val original = regression(prior = doubleArrayOf(0.0, 0.0), sigma = doubleArrayOf(10.0, 10.0))
        repeat(50) { index ->
            val x = index % 7 + 1.0
            original.observe(doubleArrayOf(1.0, x), 4.0 + 1.5 * x, weight = 1.0, atMs = index * 1000L)
        }

        val restored = regression(prior = doubleArrayOf(0.0, 0.0), sigma = doubleArrayOf(10.0, 10.0))
        assertTrue(restored.restore(original.snapshot()))

        assertEquals(original.coefficients()[0], restored.coefficients()[0], 1e-12)
        assertEquals(original.coefficients()[1], restored.coefficients()[1], 1e-12)
        assertEquals(original.residualSigma, restored.residualSigma, 1e-12)
    }

    /** Знімок від іншого набору ознак не приймається: старі статистики до нього не пасують. */
    @Test
    fun `a model saved with a different feature set is refused`() {
        val wider = regression(
            prior = doubleArrayOf(0.0, 0.0, 0.0),
            sigma = doubleArrayOf(1.0, 1.0, 1.0),
        )
        wider.observe(doubleArrayOf(1.0, 1.0, 1.0), 3.0, weight = 1.0, atMs = 0L)

        val narrower = regression(prior = doubleArrayOf(0.0, 0.0), sigma = doubleArrayOf(1.0, 1.0))

        assertFalse(narrower.restore(wider.snapshot()))
    }

    /**
     * Забування йде за календарем, а не за кількістю спостережень. Інакше горизонт
     * пам'яті залежав би від того, скільки людина їздить.
     */
    @Test
    fun `old data fades with the calendar`() {
        val regression = regression(
            prior = doubleArrayOf(0.0, 0.0),
            sigma = doubleArrayOf(10.0, 10.0),
            forgetMs = 30L * 24 * 60 * 60 * 1000,
        )

        // Рік тому машина їла 10.
        repeat(50) { index -> regression.observe(doubleArrayOf(1.0, 0.0), 10.0, 1.0, index * 1000L) }
        // Сьогодні їсть 4.
        val yearLater = 365L * 24 * 60 * 60 * 1000
        repeat(50) { index -> regression.observe(doubleArrayOf(1.0, 0.0), 4.0, 1.0, yearLater + index * 1000L) }

        assertEquals("торішнє мало забутися", 4.0, regression.coefficients()[0], 0.2)
    }

    /** Годинник телефона міг поїхати назад — від'ємний інтервал нічого не старить. */
    @Test
    fun `a clock that jumps backwards does not erase the model`() {
        val regression = regression(prior = doubleArrayOf(0.0, 0.0), sigma = doubleArrayOf(10.0, 10.0))
        repeat(30) { index -> regression.observe(doubleArrayOf(1.0, 0.0), 7.0, 1.0, 1_000_000L + index * 1000L) }

        regression.observe(doubleArrayOf(1.0, 0.0), 7.0, 1.0, atMs = 5L)

        assertEquals(7.0, regression.coefficients()[0], 0.3)
    }

    /** Вироджена система не має кидати виняток: вона має чесно повернути апріорі. */
    @Test
    fun `a hopeless system falls back to the prior instead of failing`() {
        val singular = doubleArrayOf(0.0, 0.0, 0.0, 0.0)

        assertTrue(Cholesky.decompose(singular, 2) == null)
        assertTrue(Cholesky.solve(singular, doubleArrayOf(1.0, 1.0), 2) == null)
    }

    @Test
    fun `cholesky solves a system it can solve`() {
        // [[4, 2], [2, 3]] · x = [10, 8]  =>  x = [1.75, 1.5]
        val matrix = doubleArrayOf(4.0, 2.0, 2.0, 3.0)

        val solved = Cholesky.solve(matrix, doubleArrayOf(10.0, 8.0), 2)!!

        assertEquals(1.75, solved[0], 1e-9)
        assertEquals(1.5, solved[1], 1e-9)
    }

    private fun regression(
        prior: DoubleArray,
        sigma: DoubleArray,
        forgetMs: Long = OnlineRegression.FORGET_YEAR_MS,
    ) = OnlineRegression(
        size = prior.size,
        prior = prior,
        priorSigma = sigma,
        noiseSigma = 1.0,
        forgetMs = forgetMs,
    )
}
