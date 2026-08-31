package com.kirianov.kiasoulevplus2.tools.ml

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перевірки не окремих функцій, а **властивостей навчання**. Тести вище кажуть,
 * що код рахує те, що написано; ці кажуть, що написане варте того, щоб його рахувати.
 *
 * Усі вони прогінні: модель питають ДО того, як показати їй відповідь, і рахують
 * помилку саме за цими передбаченнями. Це єдина чесна оцінка для моделі, яка
 * вчиться на ходу.
 */
class LearningQualityTest {

    /**
     * Головне питання до всієї витівки: чи краще воно за просту сталу?
     *
     * Порівняння з двома орієнтирами: «завжди 150 Вт·год/км» і «скільки виходило
     * на останніх відрізках». Другий особливо показовий — це приблизно те, що
     * робить нинішнє вікно «останні 20 км»: воно не знає, що попереду траса, і
     * після міста передбачає по-міському.
     */
    @Test
    fun `beats a constant and a trailing average on the same drives`() {
        val car = VirtualCar()
        val model = ConsumptionModel()
        val drives = car.week(segments = 200, hillNoiseKw = 0.6, measurementNoiseKw = 0.3)

        var modelError = 0.0
        var constantError = 0.0
        var trailingError = 0.0
        var counted = 0
        val trailing = ArrayDeque<Double>()

        drives.forEach { segment ->
            val hours = segment.durationMs / MS_PER_HOUR
            val actualWhPerKm = segment.energyKwh * 1000.0 / segment.distanceKm

            // Передбачення до навчання — інакше це був би самообман.
            val predicted = model.learn(segment) ?: return@forEach
            val modelWhPerKm = predicted * hours * 1000.0 / segment.distanceKm

            val trailingWhPerKm = if (trailing.isEmpty()) CONSTANT_BASELINE else trailing.average()

            modelError += abs(modelWhPerKm - actualWhPerKm)
            constantError += abs(CONSTANT_BASELINE - actualWhPerKm)
            trailingError += abs(trailingWhPerKm - actualWhPerKm)
            counted++

            trailing.addLast(actualWhPerKm)
            while (trailing.size > TRAILING_SEGMENTS) trailing.removeFirst()
        }

        val model2 = modelError / counted
        val constant = constantError / counted
        val naive = trailingError / counted

        assertTrue("нічого не порахували", counted > 100)
        assertTrue(
            "модель ($model2) мала бути помітно кращою за сталу ($constant)",
            model2 < constant * 0.5,
        )
        assertTrue(
            "модель ($model2) мала бути кращою за середнє останніх ($naive)",
            model2 < naive * 0.7,
        )
    }

    /**
     * Інтервал обіцяє 80 %. Тест перевіряє, що це справді 80 %, а не гасло.
     *
     * Саме заради цього ширина береться з фактичних промахів і підтягується
     * множником: параметрична оцінка тут показала б упевненість, якої немає,
     * бо про рельєф вона не знає нічого.
     */
    @Test
    fun `the eighty percent interval really does contain eighty percent`() {
        val car = VirtualCar(seed = 7)
        val model = ConsumptionModel()
        val quality = PredictionQuality()

        var inside = 0
        var total = 0

        car.week(segments = 600, hillNoiseKw = 0.8, measurementNoiseKw = 0.35).forEach { segment ->
            val predicted = model.learn(segment) ?: return@forEach
            // Межі, які ми показали б ДО цього блоку.
            val bounds = quality.ratioBounds()
            val ratio = quality.observe(segment, predicted) ?: return@forEach
            if (bounds == null) return@forEach

            total++
            if (ratio >= bounds.first && ratio <= bounds.second) inside++
        }

        assertTrue("блоків замало для висновку: $total", total >= 40)
        val coverage = inside.toDouble() / total
        assertTrue(
            "у межі влучило $coverage, а обіцяли 0.8",
            coverage in 0.62..0.95,
        )
    }

    /**
     * Забування йде за календарем саме заради цього: модель не мусить забути минулу
     * зиму, поки триває літо. Інакше морозний доданок щоразу вчився б наново — і
     * щоразу з нуля, бо восени даних про мороз іще немає.
     */
    @Test
    fun `last winter is still remembered after a whole summer`() {
        val car = VirtualCar(heatingKw = 2.4)
        val model = ConsumptionModel()

        val winter = car.week(segments = 120, ambientTempC = -6.0, startAtMs = 0L)
        val summer = car.week(segments = 300, ambientTempC = 24.0, startAtMs = HALF_YEAR_MS)
        (winter + summer).forEach(model::learn)

        // Питаємо про мороз після півроку без жодного морозного відрізка.
        val cold = model.predictWhPerKm(DriveConditions.steady(60.0, ambientTempC = -6.0))
        val truth = car.trueWhPerKm(60.0, -6.0)

        assertEquals("зиму мали пам'ятати", truth, cold, truth * 0.12)
    }

    /**
     * Збереження не має коштувати нічого. Модель, яку записали на диск і підняли
     * назад посеред навчання, мусить далі вчитися так, ніби її не переривали:
     * інакше кожен перезапуск застосунку тихо псував би трохи знання.
     */
    @Test
    fun `saving and reloading midway changes nothing`() {
        val car = VirtualCar(seed = 11)
        val drives = car.week(segments = 120, measurementNoiseKw = 0.3)
        val (first, second) = drives.take(60) to drives.drop(60)

        val straight = ConsumptionModel()
        drives.forEach(straight::learn)

        val interrupted = ConsumptionModel()
        first.forEach(interrupted::learn)
        // Через файл, а не копіюванням об'єкта: перевіряємо саме круг збереження.
        val text = MlCodec.encodeModel(snapshotOf(interrupted))
        val restored = ConsumptionModel()
        assertTrue(restored.restore(MlCodec.decodeModel(text)!!.consumption))
        second.forEach(restored::learn)

        listOf(40.0, 60.0, 90.0, 110.0).forEach { speed ->
            assertEquals(
                "на $speed км/год перезапуск не мав нічого змінити",
                straight.predictWhPerKm(DriveConditions.steady(speed)),
                restored.predictWhPerKm(DriveConditions.steady(speed)),
                1e-9,
            )
        }
    }

    /**
     * Модель мусить наздогнати зміну самого авто: нові шини, багажник на даху,
     * інший водій. Тут опір коченню зростає вдвічі.
     *
     * Наздоганяння свідомо не миттєве: пам'ять моделі — рік, тож нова правда
     * пробивається в міру того, як нового пробігу стає більше за старий. Виміряний
     * горизонт: ~900 км нової їзди дають половину шляху, ~3600 км — чотири п'ятих.
     * Швидше було б лише ціною забудькуватості, від якої страждав би кожен звичайний
     * тиждень.
     */
    @Test
    fun `catches up when the car itself changes`() {
        val before = VirtualCar(rollingKw = 2.1, seed = 3)
        val after = VirtualCar(rollingKw = 4.2, seed = 4)
        val model = ConsumptionModel()

        before.week(segments = 200, startAtMs = 0L, hillNoiseKw = 0.6, measurementNoiseKw = 0.3)
            .forEach(model::learn)
        val staleTruth = before.trueWhPerKm(60.0)
        val newTruth = after.trueWhPerKm(60.0)

        fun progress(): Double {
            val now = model.predictWhPerKm(DriveConditions.steady(60.0))
            return (now - staleTruth) / (newTruth - staleTruth)
        }

        val fresh = after.week(segments = 800, startAtMs = WEEK_MS, hillNoiseKw = 0.6, measurementNoiseKw = 0.3)
        fresh.take(200).forEach(model::learn)
        assertTrue("за ~900 км мало пройти хоча б третину шляху: ${progress()}", progress() > 0.33)

        fresh.drop(200).forEach(model::learn)
        val arrived = model.predictWhPerKm(DriveConditions.steady(60.0))
        assertEquals("за ~3600 км мала майже дійти", newTruth, arrived, newTruth * 0.12)
    }

    /**
     * Найтонше місце всієї конструкції, і воно не видно без такої перевірки.
     *
     * Вагу відрізка спокусливо взяти оберненою до вивченої дисперсії — це класика.
     * Але вивчена дисперсія росте саме на промахах моделі, і виходить зачароване
     * коло: змінилося авто, модель промахується, дисперсія зростає, вага нових
     * відрізків падає — і модель глушить рівно ті дані, заради яких усе й затівалося.
     *
     * Тому вага рахується з геометрії відрізка. Тест це і стереже: відрізок, який
     * модель передбачила погано, мусить важити рівно стільки ж, скільки той самий
     * відрізок, який вона передбачила добре.
     */
    @Test
    fun `a segment the model got wrong still weighs the same`() {
        val noise = NoiseModel()
        val speedMps = 16.7
        val distance = 5_000.0

        val before = noise.weightFor(speedMps, distance)
        // Довга низка великих промахів: саме те, що буває після зміни авто.
        repeat(100) { index -> noise.observe(4.0, speedMps, distance, index * 600_000L) }
        val after = noise.weightFor(speedMps, distance)

        assertEquals("промахи не мають знецінювати відрізок", before, after, 1e-9)
        assertTrue("а от невизначеність вони мусять підняти", noise.sigmaFor(speedMps, distance) > 1.0)
    }

    /**
     * Модель не має «вивчати» те, чого не бачила. Якщо всі поїздки — сама лише
     * траса, постійний відбір із них не виділяється, і чесна готовність мусить
     * це показати, а не бадьорий стовпчик.
     */
    @Test
    fun `motorway-only driving does not pretend to know the constant draw`() {
        val car = VirtualCar()
        val model = ConsumptionModel()

        repeat(200) { index ->
            model.learn(
                car.segment(speedKmh = 100.0, distanceKm = 8.0, atMs = index * 600_000L),
            )
        }

        val readiness = model.readiness.byFeature.toMap()
        assertTrue(
            "самої лише траси замало для відбору: $readiness",
            readiness.getValue("постійний відбір") < 0.75,
        )
    }

    private fun snapshotOf(model: ConsumptionModel) = ModelSnapshot(
        featureSetId = MlCodec.FEATURE_SET,
        consumption = model.snapshot(),
        capacity = CapacityModel().snapshot(),
        quality = PredictionQuality().snapshot(),
        segments = 0,
        learnedKm = 0.0,
        updatedAtMs = 0L,
    )

    private companion object {
        const val CONSTANT_BASELINE = 150.0
        const val TRAILING_SEGMENTS = 5
        const val MS_PER_HOUR = 3_600_000.0
        const val HALF_YEAR_MS = 182L * 24 * 60 * 60 * 1000
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
