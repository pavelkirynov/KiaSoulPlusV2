// ====================================================================================
// БЛОК ПРОГНОЗУ (MlBlock)
//
// ЩО ВІН РОБИТЬ:
// 1. Слухає показники в GeneralData і складає з них відрізки навчання.
// 2. На кожному закритому відрізку довчає дві моделі — витрати і ємності — та
//    дописує відрізок у журнал.
// 3. Тримає в GeneralData свіжий прогноз запасу ходу і «реального відсотка».
// 4. Виконує запити екрана: перенавчити журналом або забути все.
//
// ЧОГО ВІН НЕ РОБИТЬ:
// - НЕ звертається до інших блоків: усе, що йому треба, лежить у GeneralData.
// - НЕ рахує нічого сам: уся математика в сусідніх файлах, тут лише потік даних.
//
// Навчання йде у фоні весь час, поки застосунок під'єднаний до адаптера — блоки
// живуть у App, а не в активності, тож екран для цього відкривати не треба.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.MlModelInfo
import com.kirianov.kiasoulevplus2.Data.MlRequest
import com.kirianov.kiasoulevplus2.Data.MlSegment
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MlBlock(
    private val store: MlStore,
    /** Монотонний час: переведення годинника не має зіпсувати тривалість відрізка. */
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Годинник на стіні: за ним старіють дані й підписуються рядки журналу. */
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var consumption = ConsumptionModel()
    private var capacity = CapacityModel()
    private var quality = PredictionQuality()
    private val session = CapacitySession()
    private val segments = SegmentBuilder()

    private val recent = ArrayDeque<MlSegment>()
    private var learnedSegments = 0
    private var learnedKm = 0.0
    private var sinceLastSave = 0
    private var lastSequence: Long? = null

    /**
     * Один замок на всі моделі. Знімки вчаться в одній корутині, але «Перенавчити»
     * і «Забути все» приходять з іншої — і без замка вони пересоздавали б моделі
     * рівно тоді, коли ті вчаться. Ні втрачених оновлень, ні напівзібраної моделі
     * побачити не можна.
     */
    private val guard = Mutex()

    /**
     * Поки модель піднімається з диска, вчитися не можна: `rebuild` замінює моделі
     * цілком, і відрізок, який устиг би прослизнути раніше, просто зник би разом
     * зі старим об'єктом.
     */
    @Volatile
    private var ready = false

    fun start(scope: CoroutineScope) {
        scope.launch(ioDispatcher) {
            guard.withLock {
                restore()
                ready = true
                publish()
            }
        }
        collectSamples(scope)
        answerRequests(scope)
    }

    // --- Навчання ----------------------------------------------------------------

    /**
     * Знімки беруться з розібраних показників, а такт задає номер зчитування
     * `can.batteryFrames.sequence`.
     *
     * Саме номер, а не зміна значень. Струм і пробіг подеколи виходять однаковими
     * двічі поспіль, і тоді сховище просто не сповістить про «зміну»: однакове
     * значення для StateFlow — це не подія. Відрізок від такого мовчання діставав би
     * дірки саме на рівному ходу, тобто там, де дані найкращі, і чесне покриття
     * відкидало б найкорисніші відрізки. Номер же зчитування росте завжди — рівно
     * один знімок на одне опитування шини.
     */
    private fun collectSamples(scope: CoroutineScope) {
        GeneralData.state
            .onEach { state ->
                if (!state.isConnected || !state.bms.hasData) {
                    // Розрив: далі знімки підуть із діркою в часі, а відрізок крізь
                    // дірку рахувати не можна.
                    segments.reset()
                    session.reset()
                    lastSequence = null
                    return@onEach
                }

                if (!ready) return@onEach

                val sequence = state.can.batteryFrames?.sequence ?: return@onEach
                if (sequence == lastSequence) return@onEach
                lastSequence = sequence

                val sample = sampleOf(state, elapsedMillis()) ?: return@onEach
                guard.withLock {
                    capacity.learnBuffer(sample.displaySocPercent, sample.socPercent, sample.wallClockMs)

                    val segment = segments.accept(sample)
                    if (segment != null) absorb(segment)
                    publish()
                }
            }
            .launchIn(scope)
    }

    /** Довчити моделі на щойно закритому відрізку і покласти його в журнал. */
    private suspend fun absorb(segment: MlSegment) {
        val predicted = consumption.learn(segment)
        if (predicted != null) quality.observe(segment, predicted)

        session.add(segment)?.let { observation ->
            capacity.learn(
                observation.socStartPercent,
                observation.socEndPercent,
                observation.energyKwh,
                observation.atMs,
            )
        }

        learnedSegments++
        learnedKm += segment.distanceKm
        remember(segment.copy(predictedPowerKw = predicted))

        withContext(ioDispatcher) {
            store.appendSegment(segment.copy(predictedPowerKw = predicted))
            if (++sinceLastSave >= SAVE_EVERY_SEGMENTS) {
                sinceLastSave = 0
                store.saveModel(snapshot())
            }
        }
    }

    private fun remember(segment: MlSegment) {
        recent.addLast(segment)
        while (recent.size > RECENT_SEGMENTS) recent.removeFirst()
    }

    // --- Запити екрана -------------------------------------------------------------

    private fun answerRequests(scope: CoroutineScope) {
        GeneralData.state
            .map { it.ml.request }
            .distinctUntilChanged()
            .onEach { request ->
                when (request) {
                    MlRequest.None -> Unit
                    MlRequest.Retrain -> {
                        GeneralData.clearMlRequest()
                        scope.launch(ioDispatcher) { retrain() }
                    }
                    MlRequest.Reset -> {
                        GeneralData.clearMlRequest()
                        scope.launch(ioDispatcher) { forgetEverything() }
                    }
                }
            }
            .launchIn(scope)
    }

    /**
     * Зібрати модель наново з журналу.
     *
     * Це і є сенс зберігати сирі відрізки, а не самі лише коефіцієнти: набір ознак
     * колись зміниться, і тоді накопичені статистики стануть непридатні — а журнал
     * лишиться. Те саме відбувається само собою на старті, якщо у файлі записана
     * інша версія набору ознак.
     */
    private suspend fun retrain() {
        GeneralData.updateMl { it.copy(retraining = true) }
        try {
            val history = withContext(ioDispatcher) { store.readSegments() }
            guard.withLock {
                rebuild(history)
                withContext(ioDispatcher) { store.saveModel(snapshot()) }
            }
        } finally {
            GeneralData.updateMl { it.copy(retraining = false) }
            guard.withLock { publish() }
        }
    }

    private fun rebuild(history: List<MlSegment>) {
        consumption = ConsumptionModel()
        capacity = CapacityModel()
        quality = PredictionQuality()
        session.reset()
        recent.clear()
        learnedSegments = 0
        learnedKm = 0.0

        history.sortedBy { it.startedAtMs }.forEach { segment ->
            val predicted = consumption.learn(segment)
            if (predicted != null) quality.observe(segment, predicted)
            capacity.learnBuffer(segment.displaySocStartPercent, segment.socStartPercent, segment.startedAtMs)
            capacity.learnBuffer(segment.displaySocEndPercent, segment.socEndPercent, segment.startedAtMs)
            session.add(segment)?.let {
                capacity.learn(it.socStartPercent, it.socEndPercent, it.energyKwh, it.atMs)
            }
            learnedSegments++
            learnedKm += segment.distanceKm
            remember(segment)
        }
    }

    private suspend fun forgetEverything() {
        withContext(ioDispatcher) { store.clear() }
        guard.withLock {
            rebuild(emptyList())
            segments.reset()
            publish()
        }
    }

    private suspend fun restore() {
        val saved = store.loadModel()
        if (saved != null && saved.featureSetId == MlCodec.FEATURE_SET) {
            consumption.restore(saved.consumption)
            capacity.restore(saved.capacity)
            quality.restore(saved.quality)
            learnedSegments = saved.segments
            learnedKm = saved.learnedKm
            recent.addAll(store.readSegments().takeLast(RECENT_SEGMENTS))
            return
        }
        // Файлу немає або він від іншого набору ознак: збираємо з журналу.
        rebuild(store.readSegments())
    }

    // --- Прогноз ---------------------------------------------------------------------

    private fun publish() {
        val vehicle = GeneralData.state.value.vehicle
        // Середні умови — з останніх відрізків, а клімат — живий, просто з шини:
        // пічку могли щойно ввімкнути, і прогноз має подорожчати одразу, а не за
        // чверть години, коли вона всередниться у відрізках.
        val conditions = RangeEstimator.recentConditions(recent.toList()).let { aggregated ->
            climateShareOf(vehicle)?.let { aggregated.copy(climateShare = it) } ?: aggregated
        }
        val prediction = socOf(vehicle)?.let { soc ->
            RangeEstimator.predict(consumption, capacity, quality, soc, conditions)
        }

        GeneralData.updateMl { current ->
            current.copy(
                prediction = prediction,
                recentSegments = recent.toList(),
                model = MlModelInfo(
                    segments = learnedSegments,
                    learnedKm = learnedKm,
                    blocks = quality.blocks,
                    usableCapacityKwh = capacity.usableCapacityKwh,
                    averageCapacityKwh = capacity.averageCapacityKwh,
                    measuredScalePercent = capacity.measuredScalePercent,
                    capacityVersusNominalPercent = capacity.capacityVersusNominalPercent,
                    timesLargerThanOriginal = capacity.timesLargerThanOriginal,
                    floorSocPercent = capacity.floorSocPercent,
                    ceilingSocPercent = capacity.ceilingSocPercent,
                    capacityMeasured = capacity.capacityMeasured,
                    scaleMeasured = capacity.scaleMeasured,
                    auxPowerKw = consumption.auxPowerKw,
                    maeWhPerKm = consumption.meanAbsoluteErrorWhPerKm(),
                    terrainRoughness = consumption.noise.terrainRoughness,
                    readiness = consumption.readiness.byFeature,
                    confidence = RangeEstimator.confidenceOf(consumption.readiness, prediction),
                    updatedAtMs = wallClockMillis(),
                ),
            )
        }
    }

    private fun snapshot() = ModelSnapshot(
        featureSetId = MlCodec.FEATURE_SET,
        consumption = consumption.snapshot(),
        capacity = capacity.snapshot(),
        quality = quality.snapshot(),
        segments = learnedSegments,
        learnedKm = learnedKm,
        updatedAtMs = wallClockMillis(),
    )

    /**
     * Точний SOC із BMS, а якщо його ще не чули — панельний із кадру 21 01, який
     * приходить щосекунди. Краще приблизний відсоток одразу, ніж порожній екран
     * першу хвилину після під'єднання.
     */
    private fun socOf(vehicle: VehicleData): Double? = when {
        vehicle.hasPreciseSoc -> vehicle.preciseSocPercent
        vehicle.hasDisplaySoc -> vehicle.displaySocPercent
        else -> GeneralData.state.value.bms.displaySoc.takeIf { it >= 0.0 }
    }

    /**
     * Яку частку витрати з'їдає клімат. Авто саме каже, на скільки кілометрів виріс
     * би запас із вимкненим кліматом, — звідси частка = приріст / (запас + приріст).
     *
     * null, поки кадр 200 не приходив: вигадувати нуль не можна, бо «клімат
     * вимкнений» і «ще не знаємо» — різні речі, і модель має розрізняти їх.
     */
    private fun climateShareOf(vehicle: VehicleData): Double? {
        if (!vehicle.hasClimateExtra || !vehicle.hasRange) return null
        val total = vehicle.rangeKm + vehicle.climateExtraKm
        if (total <= 0.0) return null
        return (vehicle.climateExtraKm / total).coerceIn(0.0, 1.0)
    }

    private fun sampleOf(state: State, elapsedMs: Long): MlSample? {
        val bms = state.bms
        if (!bms.hasData) return null
        val vehicle = state.vehicle
        return MlSample(
            elapsedMs = elapsedMs,
            wallClockMs = wallClockMillis(),
            // Домовленість застосунку: від'ємна потужність — розряд.
            powerKw = bms.batteryVoltage * bms.batteryCurrent / 1000.0,
            odometerKm = vehicle.odometerKm.takeIf { vehicle.hasOdometer },
            speedKmh = vehicle.speedKmh.takeIf { vehicle.hasSpeed },
            ambientTempC = vehicle.ambientTempC.takeIf { vehicle.hasAmbientTemp },
            batteryTempC = bms.batteryTempC,
            climateShare = climateShareOf(vehicle),
            socPercent = vehicle.preciseSocPercent.takeIf { vehicle.hasPreciseSoc },
            // Панельний SOC із BMS оновлюється щосекунди, а кадр 594 — раз на хвилину.
            displaySocPercent = bms.displaySoc.takeIf { it >= 0.0 },
            charging = vehicle.charging.isCharging,
        )
    }

    private companion object {
        /** Скільки відрізків тримати для графіка й для «як їхали останнім часом». */
        const val RECENT_SEGMENTS = 40

        /** Записувати модель не щоразу: відрізок закривається раз на кілька хвилин. */
        const val SAVE_EVERY_SEGMENTS = 3

    }
}
