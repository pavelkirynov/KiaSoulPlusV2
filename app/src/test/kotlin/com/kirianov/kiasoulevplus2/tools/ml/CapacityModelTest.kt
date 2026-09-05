package com.kirianov.kiasoulevplus2.tools.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapacityModelTest {

    /**
     * Батарея з відомою ємністю. Модель має знайти саме її, а не паспортні 27 кВт·год:
     * у цьому й уся суть — показувати ємність **цього** пакета, а не нового.
     */
    @Test
    fun `learns the real capacity of a worn battery`() {
        val model = CapacityModel()
        val trueCapacityKwh = 22.0

        // Десяток заїздів по різних частинах шкали.
        sessions().forEachIndexed { index, (from, to) ->
            val energy = trueCapacityKwh * (from - to) / 100.0
            model.learn(from, to, energy, atMs = index * DAY_MS)
        }

        // 60 % шкали мають важити 60 % ємності.
        assertEquals(trueCapacityKwh * 0.6, model.energyBetween(20.0, 80.0), 0.8)
        assertEquals(trueCapacityKwh / 100.0, model.kwhPerPercentAt(50.0), 0.02)
    }

    /** Заряджання — те саме спостереження, тільки з іншим знаком і чистіше. */
    @Test
    fun `a charging session teaches capacity just as a drive does`() {
        val model = CapacityModel()
        val trueCapacityKwh = 24.0

        repeat(8) { index ->
            // SOC росте, енергія від'ємна: обидві величини міняють знак разом.
            val received = -trueCapacityKwh * 0.5
            model.learn(20.0, 70.0, received, atMs = index * DAY_MS)
        }

        assertEquals(trueCapacityKwh / 100.0, model.kwhPerPercentAt(50.0), 0.03)
    }

    /** Дрібний крок SOC не годиться: на ньому шум більший за сигнал. */
    @Test
    fun `a tiny slice of the scale is refused`() {
        val model = CapacityModel()

        assertFalse(model.learn(60.0, 58.0, 0.5, atMs = 0L))
        assertTrue(model.learn(60.0, 45.0, 3.6, atMs = 0L))
    }

    /** Енергія і SOC мусять рухатися в один бік: інакше це збій читання. */
    @Test
    fun `energy and charge moving opposite ways is a broken reading`() {
        val model = CapacityModel()

        assertFalse("SOC упав, а енергія прибула", model.learn(60.0, 40.0, -5.0, atMs = 0L))
    }

    /**
     * Панель бреше на краях шкали, і модель має вирахувати, наскільки саме.
     * Пари подаються за законом display = 1.05·precise − 4.2, тобто нуль на панелі
     * настає, коли в батареї ще є чотири відсотки.
     */
    @Test
    fun `works out where the scale really ends`() {
        val model = CapacityModel()

        var at = 0L
        (12..95 step 2).forEach { precise ->
            val display = 1.05 * precise - 4.2
            repeat(3) { model.learnBuffer(display, precise.toDouble(), at++) }
        }

        assertEquals("справжнє дно шкали", 4.0, model.floorSocPercent, 0.6)
        assertTrue("стеля має бути близько сотні", model.ceilingSocPercent > 95.0)
    }

    /** Краї шкали, де панель уперлася в 0 і 100, не мусять завалювати нахил. */
    @Test
    fun `the flat ends of the dial are ignored`() {
        val model = CapacityModel()

        var at = 0L
        // «Полички»: панель стоїть на 100, поки точний SOC іще росте.
        repeat(40) { model.learnBuffer(100.0, 96.0 + it * 0.1, at++) }
        repeat(40) { model.learnBuffer(0.0, 4.0 - it * 0.05, at++) }

        // Жодна з цих пар не мала потрапити в підгонку, тож лишилася фізика.
        assertEquals(CapacityModel.DEFAULT_FLOOR_PERCENT, model.floorSocPercent, 0.5)
    }

    /** Реальний відсоток міряє енергію, а не положення стрілки. */
    @Test
    fun `the real percentage counts energy above the real floor`() {
        val model = CapacityModel()

        assertEquals(100.0, model.realPercent(model.ceilingSocPercent), 0.5)
        assertEquals(0.0, model.realPercent(model.floorSocPercent), 0.5)
        // На дні шкали панельні відсотки закінчуються раніше за реальні нулі.
        assertTrue(model.realPercent(10.0) < 10.0)
        assertTrue(model.realPercent(50.0) in 40.0..55.0)
    }

    /**
     * Модель мусить розрізняти «виміряв» і «поки припускаю». Без цього щойно
     * встановлений застосунок показує дно шкали 4 % так само впевнено, як і за рік
     * їзди, — а це число з апріорної прямої, а не про це авто.
     */
    @Test
    fun `an untrained model admits its numbers are only assumptions`() {
        val model = CapacityModel()

        assertFalse("ємності ще не міряли", model.capacityMeasured)
        assertFalse("краї шкали ще не міряли", model.scaleMeasured)
        // Саме те число, яке бачить власник на свіжому встановленні.
        assertEquals(4.0, model.floorSocPercent, 0.1)

        model.learn(90.0, 60.0, 13.5, atMs = 0L)
        assertTrue("одна сесія — вже вимір", model.capacityMeasured)
        assertFalse("а краї шкали з неї не беруться", model.scaleMeasured)

        model.learnBuffer(displaySocPercent = 60.0, preciseSocPercent = 61.0, atMs = DAY_MS)
        assertTrue("пара «панель / точний» міряє краї", model.scaleMeasured)
    }

    /**
     * Апріорі має означати те саме, що виміряв власник, — а виміряв він **вікно**:
     * зарядка від нуля на панелі до сотні. Якщо приор розкласти по всій шкалі BMS
     * як є, то вікно вийде на п'ять відсотків меншим за виміряне, і свіжий
     * застосунок показуватиме 48.6 там, де зарядка казала 51.
     */
    @Test
    fun `the untrained capacity matches what the charger measured`() {
        val model = CapacityModel()

        assertEquals(
            "вікно мало важити рівно стільки, скільки виміряли",
            Vehicle.USABLE_CAPACITY_KWH,
            model.usableCapacityKwh,
            0.2,
        )
        assertEquals("і «від очікуваної» на старті — сто відсотків", 100.0, model.capacityVersusNominalPercent, 0.5)
    }

    /**
     * Крива для графіка «панель проти реальності». Кінці прибиті за побудовою — і це
     * не формальність, а вимога: нуль і сто на екрані мусять бути тим самим нулем і
     * сотнею, які дозволяє BMS.
     */
    @Test
    fun `the dial to real curve is pinned at both ends and never falls`() {
        val curve = CapacityModel().scaleCurve()

        assertEquals(CapacityModel.CURVE_POINTS, curve.size)
        assertEquals(0.0, curve.first().dialPercent, 1e-9)
        assertEquals(0.0, curve.first().realPercent, 0.01)
        assertEquals(100.0, curve.last().dialPercent, 1e-9)
        assertEquals(100.0, curve.last().realPercent, 0.01)

        // Заряд не може падати, коли стрілка росте.
        curve.zipWithNext().forEach { (lower, upper) ->
            assertTrue(
                "крива мала лишитися зростаючою: ${lower.realPercent} -> ${upper.realPercent}",
                upper.realPercent >= lower.realPercent - 1e-9,
            )
        }
    }

    /**
     * І головне, заради чого графік узагалі є: кривина шкали мусить бути на ньому
     * видна. На пакеті, де відсоток унизу дорожчий, середина панелі відповідає
     * помітно більшому реальному заряду.
     */
    @Test
    fun `a bent scale shows up as a bend in the curve`() {
        val model = CapacityModel()

        // dQ/du = 56 − 10·u: унизу шкали відсоток дорожчий, угорі дешевший.
        fun energyBetween(fromPercent: Double, toPercent: Double): Double {
            val from = fromPercent / 100.0
            val to = toPercent / 100.0
            return 56.0 * (to - from) - 10.0 * (to * to - from * from) / 2.0
        }

        var at = 0L
        listOf(
            100.0 to 70.0, 70.0 to 40.0, 40.0 to 10.0, 90.0 to 45.0,
            60.0 to 20.0, 95.0 to 60.0, 50.0 to 12.0, 85.0 to 35.0,
        ).forEach { (from, to) ->
            model.learn(from, to, energyBetween(to, from), atMs = at)
            at += DAY_MS
        }

        val middle = model.realPercentForDisplay(50.0)
        assertTrue("кривина мала підняти реальний відсоток: $middle", middle > 51.0)
        assertTrue("але не в рази — кінці ж прибиті: $middle", middle < 60.0)

        // Обидва кінці лишилися на місці навіть після навчання.
        assertEquals(0.0, model.realPercentForDisplay(0.0), 0.01)
        assertEquals(100.0, model.realPercentForDisplay(100.0), 0.01)
    }

    /** Сесія складає відрізки, поки SOC не пройде помітний шмат шкали. */
    @Test
    fun `a session gathers segments until the charge moved enough`() {
        val session = CapacitySession()
        val car = VirtualCar()

        var soc = 80.0
        var observation: CapacityObservation? = null
        repeat(12) { index ->
            val next = soc - 1.5
            val segment = car.segment(speedKmh = 60.0, atMs = index * 600_000L)
                .copy(socStartPercent = soc, socEndPercent = next)
            observation = observation ?: session.add(segment)
            soc = next
        }

        val result = requireNonNull(observation)
        assertEquals(80.0, result.socStartPercent, 1e-9)
        assertTrue("розмах мав перевищити поріг", result.socStartPercent - result.socEndPercent >= 8.0)
        assertTrue("енергія мала скластися з відрізків", result.energyKwh > 0.0)
    }

    /**
     * Головна перевірка для того, хто не тримає застосунок увімкненим весь час.
     *
     * Поки телефон у кишені, машина їде, і SOC падає — а енергії ніхто не міряв.
     * Якщо просто продовжити сесію після повернення, вийде великий крок шкали з
     * маленькою енергією, і ємність упаде в рази. На вимірюванні пропуск у п'ять
     * відсотків давав 20 кВт·год замість 45.
     */
    @Test
    fun `a gap in the data starts the session over instead of lying`() {
        val session = CapacitySession()
        var observation: CapacityObservation? = null

        // Видима частина: 80 -> 77.
        listOf(80.0 to 79.0, 79.0 to 78.0, 78.0 to 77.0).forEach { (from, to) ->
            observation = observation ?: session.add(segment(from, to))
        }
        // Застосунок був згорнутий: машина проїхала 77 -> 72 непоміченою.
        // Далі знову видно, аж поки не набереться поріг.
        listOf(72.0 to 71.0, 71.0 to 70.0, 70.0 to 69.0, 69.0 to 68.0).forEach { (from, to) ->
            observation = observation ?: session.add(segment(from, to))
        }

        assertTrue("сесія крізь пропуск не мала закритися", observation == null)
        assertTrue("а рахуватися мала вже з-за пропуску", session.spanPercent <= 4.0)
    }

    /** Без пропусків усе працює як раніше: відрізки складаються в сесію. */
    @Test
    fun `contiguous segments still add up`() {
        val session = CapacitySession()
        var observation: CapacityObservation? = null

        var soc = 90.0
        repeat(10) {
            val next = soc - 1.0
            observation = observation ?: session.add(segment(soc, next))
            soc = next
        }

        val result = requireNonNull(observation)
        assertEquals(90.0, result.socStartPercent, 1e-9)
        assertEquals("енергія має бути сумою відрізків", 8 * 0.45, result.energyKwh, 0.5)
    }

    /** Відрізок без відомого SOC на початку перевірити нічим — отже, це теж розрив. */
    @Test
    fun `a segment with no starting charge is treated as a gap`() {
        val session = CapacitySession()
        session.add(segment(80.0, 79.0))
        session.add(segment(79.0, 78.0))

        session.add(segment(null, 70.0))

        assertTrue("сесія мала початися заново", session.spanPercent < 1.0)
    }

    private fun segment(
        socStart: Double?,
        socEnd: Double,
        energyKwh: Double = 0.45,
    ) = VirtualCar().segment(speedKmh = 60.0).copy(
        socStartPercent = socStart,
        socEndPercent = socEnd,
        energyKwh = energyKwh,
    )

    private fun requireNonNull(observation: CapacityObservation?): CapacityObservation {
        assertTrue("сесія так і не закрилася", observation != null)
        return observation!!
    }

    /** Батарея відомої ємності, вивчена десятком заїздів по різних частинах шкали. */
    private fun trainedOnKnownCapacity(capacityKwh: Double): CapacityModel {
        val model = CapacityModel()
        sessions().forEachIndexed { index, (from, to) ->
            val energy = capacityKwh * (from - to) / 100.0
            model.learn(from, to, energy, atMs = index * DAY_MS)
        }
        return model
    }

    private fun sessions() = listOf(
        90.0 to 60.0,
        75.0 to 40.0,
        95.0 to 55.0,
        60.0 to 25.0,
        85.0 to 30.0,
        70.0 to 35.0,
        100.0 to 65.0,
        50.0 to 15.0,
        80.0 to 45.0,
        65.0 to 20.0,
    )

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    /**
     * Крива «кВт·год проти відсотка» мусить рости монотонно від нуля на дні до
     * повної ємності на стелі: це та сама виміряна енергія, лише накопичена.
     */
    @Test
    fun `the energy curve rises from zero to the learned capacity`() {
        val model = trainedOnKnownCapacity(51.0)

        val curve = model.energyCurve()

        assertTrue("крива мусить бути", curve.size >= 2)
        assertEquals(0.0, curve.first().energyKwh, 0.001)
        assertEquals(model.usableCapacityKwh, curve.last().energyKwh, 0.5)

        curve.zipWithNext().forEach { (a, b) ->
            assertTrue("енергія не може падати з відсотком", b.energyKwh >= a.energyKwh)
            assertTrue("відсоток мусить рости", b.socPercent > a.socPercent)
        }
    }

    /** Кінці кривої — справжні дно й стеля, а не нуль і сто зі шкали панелі. */
    @Test
    fun `the curve spans the real floor and ceiling`() {
        val model = trainedOnKnownCapacity(51.0)

        val curve = model.energyCurve()

        assertEquals(model.floorSocPercent, curve.first().socPercent, 0.001)
        assertEquals(model.ceilingSocPercent, curve.last().socPercent, 0.001)
    }

    /** Поки ємність не міряли, малювати нема чого — краще порожньо, ніж вигадане. */
    @Test
    fun `an unmeasured battery has no curve`() {
        assertTrue(CapacityModel().energyCurve().isEmpty())
    }

    /**
     * Головне з відгуку: графік мусить показувати те, що вже зняли, а не чекати
     * повного проходу шкали. Для цього модель повинна знати, які корзини виміряні.
     */
    @Test
    fun `only the bins actually driven are marked as measured`() {
        val model = CapacityModel()

        // Їздили лише в середині шкали: з 70 до 40 %.
        repeat(6) { index ->
            model.learn(70.0, 40.0, 51.0 * 0.3, atMs = index * DAY_MS)
        }

        assertTrue("середина мусить бути виміряною", model.binMeasured(50.0))
        assertFalse("низ шкали не міряли", model.binMeasured(5.0))
        assertFalse("верх шкали не міряли", model.binMeasured(95.0))
        assertEquals(40.0, model.measuredFromPercent!!, 0.001)
        assertEquals(70.0, model.measuredToPercent!!, 0.001)
    }

    @Test
    fun `nothing is measured before anything is learned`() {
        val model = CapacityModel()

        assertNull(model.measuredFromPercent)
        assertNull(model.measuredToPercent)
        assertFalse(model.binMeasured(50.0))
    }

    /** Крива мусить казати, де вимір, а де доведене з відомого. */
    @Test
    fun `the energy curve marks measured points apart from inferred ones`() {
        val model = CapacityModel()
        repeat(6) { index -> model.learn(70.0, 40.0, 51.0 * 0.3, atMs = index * DAY_MS) }

        val curve = model.energyCurve()

        assertTrue(curve.any { it.measured })
        assertTrue("поза виміряним мусить бути доведене", curve.any { !it.measured })
        curve.filter { it.measured }.forEach {
            assertTrue("виміряне поза межами: ${it.socPercent}", it.socPercent in 39.0..71.0)
        }
    }

    /** Покриття корзин мусить пережити збереження: інакше графік після перезапуску порожній. */
    @Test
    fun `bin coverage survives a snapshot`() {
        val model = CapacityModel()
        repeat(6) { index -> model.learn(70.0, 40.0, 51.0 * 0.3, atMs = index * DAY_MS) }

        val restored = CapacityModel()
        restored.restore(model.snapshot())

        assertTrue(restored.binMeasured(50.0))
        assertFalse(restored.binMeasured(5.0))
        assertEquals(model.measuredFromPercent!!, restored.measuredFromPercent!!, 0.001)
    }

    /** Старе збереження без покриття читається як «нічого не виміряно», а не падає. */
    @Test
    fun `an old snapshot without coverage still restores`() {
        val model = CapacityModel()
        repeat(6) { index -> model.learn(70.0, 40.0, 51.0 * 0.3, atMs = index * DAY_MS) }
        val old = model.snapshot()

        val restored = CapacityModel()
        val ok = restored.restore(
            CapacitySnapshot(
                energy = old.energy,
                buffer = old.buffer,
                measuredEnergyKwh = old.measuredEnergyKwh,
                measuredSpanPercent = old.measuredSpanPercent,
            ),
        )

        assertTrue(ok)
        assertNull(restored.measuredFromPercent)
    }
}
