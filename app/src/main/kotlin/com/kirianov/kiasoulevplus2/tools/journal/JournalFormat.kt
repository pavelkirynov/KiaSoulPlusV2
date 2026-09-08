// ====================================================================================
// РЯДКИ ЖУРНАЛУ (JournalFormat)
//
// Перетворює стан застосунку на рядки тексту. Нічого не пише і нікуди не звертається,
// тож перевіряється тестами повністю.
//
// ФОРМАТ. Один рядок — одна подія, поля «ключ=значення» через пробіл:
//
//   09-03 08:03:12.345 snap seq=41 odo=188894.3 v=0 socD=95.2 socP=98.3 …
//   09-03 08:03:14.001 link Connected «Підключено до Vlink»
//   09-03 08:05:41.900 abort n=6 «обрив зв'язку»
//
// Чому не JSON: журнал читають очима. Рівний стовпчик подій із мітками часу видно
// з першого погляду, а вкладені дужки — ні.
//
// ДВА ВИДИ РЯДКІВ:
//  - ПОДІЇ пишуться тоді, коли щось справді змінилося. Їх мало, і саме вони
//    відповідають на «чому».
//  - ЗРІЗИ пишуться за розкладом і відповідають на «а чи відбувалося взагалі
//    хоч щось». Без них тиша в журналі не відрізняється від застосунку, який
//    просто нічого не пише.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.journal

import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.tools.frames.FrameDiff
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object JournalFormat {

    /** Версія формату: щоб потім не гадати, чи той це набір полів. */
    const val VERSION = 1

    fun opened(atMs: Long, appVersion: String): String =
        "${stamp(atMs)} open journal=v$VERSION app=$appVersion"

    /**
     * Зріз поточних показників.
     *
     * `seq` — номер зчитування шини. Саме він відповідає на головне питання «чи
     * приходять дані взагалі»: якщо застосунок показує «Підключено», а seq між
     * двома зрізами не зрушив, значить опитування стоїть, і решта чисел — просто
     * останнє, що встигло прийти.
     */
    fun snapshot(state: State, atMs: Long): String {
        val bms = state.bms
        val vehicle = state.vehicle
        val fields = buildList {
            add("seq=${state.can.batteryFrames?.sequence ?: -1}")
            add("odo=${num(vehicle.odometerKm.takeIf { vehicle.hasOdometer })}")
            add("v=${num(vehicle.speedKmh.takeIf { vehicle.hasSpeed })}")
            add("socD=${num(bms.displaySoc.takeIf { bms.hasData })}")
            add("socP=${num(vehicle.preciseSocPercent.takeIf { vehicle.hasPreciseSoc })}")
            add("U=${num(bms.batteryVoltage)}")
            add("I=${num(bms.batteryCurrent)}")
            add("chg=${flag(vehicle.charging.isCharging)}")
            // МЕЖІ, ЯКІ ВИСТАВЛЯЄ САМА BMS, і 12-вольтовий акумулятор. Перше
            // об'єднує «машина не тягне» з «батарея не дозволяє», друге —
            // найчастіша причина «не запускається» в електромобілі.
            add("okOut=${num(bms.availableDischargeKw.takeIf { bms.hasData })}")
            add("okIn=${num(bms.availableChargeKw.takeIf { bms.hasData })}")
            add("v12=${num(bms.auxVolts.takeIf { it > 0.0 })}")
            add("kWhIn=${num(bms.cumulativeEnergyChargedKwh)}")
            add("kWhOut=${num(bms.cumulativeEnergyDischargedKwh)}")
            // Лічильники в ампер-годинах мають крок 0.1 А·год, тобто вдесятеро
            // тонший за кВт·год. Якщо кривій ємності забракне роздільності, міряти
            // доведеться саме по них — а для цього їх треба спершу побачити.
            add("AhIn=${num(bms.cumulativeChargedAh)}")
            add("AhOut=${num(bms.cumulativeDischargedAh)}")
            add("rng=${vehicle.rangeKm}")
            add("amb=${num(vehicle.ambientTempC.takeIf { vehicle.hasAmbientTemp })}")
            add("batT=${num(bms.batteryTempC)}")
            state.can.monitor?.let { add("mon=${it.filterId}/${it.lines.size}") }
            // ЧИЇ ЦЕ ЧИСЛА. Знак питання тут коштує рівно одного символа, а
            // відповідає на найдорожче питання журналу: чи належать усі попередні
            // поля тому авто, за яке застосунок рахує.
            add("car=" + if (state.garage.identified) vinTail(state.garage.activeVin) else "?")
        }
        return "${stamp(atMs)} snap ${fields.joinToString(" ")}"
    }

    /**
     * Що змінилося між двома станами. Порожній список означає «нічого, вартого
     * рядка»: більшість оновлень сховища саме такі.
     */
    fun events(before: State, after: State, atMs: Long): List<String> {
        val out = mutableListOf<String>()
        val at = stamp(atMs)

        if (before.connection != after.connection) {
            out += "$at link ${after.connection} «${after.debugInfo}»"
        } else if (before.debugInfo != after.debugInfo && after.debugInfo.isNotEmpty()) {
            out += "$at note «${after.debugInfo}»"
        }

        if (before.bms.hasData != after.bms.hasData) {
            out += "$at bms data=${flag(after.bms.hasData)}"
        }

        if (before.vehicle.charging.isCharging != after.vehicle.charging.isCharging) {
            // Ознака заряджання вирішує все в обліку зарядок, тож поруч із нею
            // одразу лічильник, швидкість і пробіг: так видно, чи це справді
            // зарядка, чи рекуперація на ходу.
            out += "$at chg=${flag(after.vehicle.charging.isCharging)} " +
                "kWhIn=${num(after.bms.cumulativeEnergyChargedKwh)} " +
                "v=${num(after.vehicle.speedKmh.takeIf { after.vehicle.hasSpeed })} " +
                "odo=${num(after.vehicle.odometerKm.takeIf { after.vehicle.hasOdometer })}"
        }

        val chargeBefore = before.charge
        val chargeAfter = after.charge
        if (chargeBefore.sessionKwh != chargeAfter.sessionKwh ||
            chargeBefore.lastSessionKwh != chargeAfter.lastSessionKwh ||
            chargeBefore.todayKwh != chargeAfter.todayKwh
        ) {
            out += "$at charge session=${num(chargeAfter.sessionKwh)} " +
                "last=${num(chargeAfter.lastSessionKwh)} today=${num(chargeAfter.todayKwh)} " +
                "base=${num(chargeAfter.counterBaselineKwh)}"
        }
        if (chargeBefore.lastDecision != chargeAfter.lastDecision && chargeAfter.lastDecision.isNotEmpty()) {
            out += "$at charge? «${chargeAfter.lastDecision}»"
        }

        // ЯКЕ ЦЕ АВТО. Мовчазна невдача при читанні VIN одного разу коштувала того,
        // що дані другої машини лягли в теку першої: зв'язок піднявся, шина ще
        // мовчала, запит провалився — і застосунок цілу поїздку рахував чуже за своє.
        if (before.garage.vinNote != after.garage.vinNote && after.garage.vinNote.isNotEmpty()) {
            out += "$at vin «${after.garage.vinNote}»"
        }
        if (before.garage.identified != after.garage.identified) {
            out += "$at car? ${if (after.garage.identified) "підтверджено" else "не підтверджено"}"
        }
        if (before.garage.activeVin != after.garage.activeVin && after.garage.activeVin.isNotEmpty()) {
            out += "$at car ...${after.garage.activeVin.takeLast(6)} " +
                "пакет=${num(after.garage.active.packKwh.takeIf { it > 0.0 })}"
        }

        // ЩО БАТАРЕЯ ДУМАЄ ПРО СЕБЕ. Кадр 21 05 приходить раз на кілька хвилин,
        // тож рядок рідкий — і саме тому потрібен: за ним видно, як знос і межі
        // температур повзуть із місяцями, а на екрані видно тільки «зараз».
        val healthBefore = before.packHealth
        val healthAfter = after.packHealth
        if (healthAfter.known && healthAfter != healthBefore) {
            out += "$at health tMax=${num(healthAfter.maxTempC)} " +
                "tMin=${num(healthAfter.minTempC)} " +
                "tIn=${num(healthAfter.inletTempC)} " +
                "wear=${num(healthAfter.maxDeteriorationPercent)}" +
                "/№${healthAfter.maxDeteriorationCell} " +
                "best=${num(healthAfter.minDeteriorationPercent)}" +
                "/№${healthAfter.minDeteriorationCell} " +
                "socD=${num(healthAfter.displaySoc)}"
        }

        out += carSystems(before, after, at)

        // СИРА ВІДПОВІДЬ КОЖНОГО БЛОКА, по рядку на блок.
        //
        // Підсумковий рядок нижче каже «озвалося шість із дев'яти» — і на цьому
        // все: які саме байти привели до такого висновку, з нього не дізнатися.
        // Перше ж опитування живої машини вперлося рівно в це: два блоки показали
        // «відмовив, причина 0x78» і «0x21», і щоб зрозуміти, що це були за
        // відмови, довелося здогадуватися. Тепер не доведеться.
        val answer = after.faults.answer
        if (answer != null && answer.sequence != before.faults.answer?.sequence) {
            out += "$at dtc? ${answer.header} " +
                answer.raw.joinToString(" | ") { "«${it.trim().ifEmpty { "тиша" }}»" }
        }

        // ПОМИЛКИ БЛОКІВ. Рядок пишеться раз на опитування, коли воно скінчилося:
        // саме тоді відомо, хто озвався, а хто промовчав. Коди йдуть повністю —
        // їх одиниці, а без них рядок не варт нічого.
        if (before.faults.scannedAtMs != after.faults.scannedAtMs && after.faults.scannedAtMs > 0L) {
            val scan = after.faults
            val codes = scan.results.flatMap { result ->
                result.faults.map { "${result.ecu.header}:${it.code}/${it.status}" }
            }
            out += "$at dtc блоків=${scan.results.size} озвалося=${scan.answered.size} " +
                "кодів=${scan.faults} активних=${scan.active}" +
                if (codes.isEmpty()) "" else " «${codes.joinToString(" ")}»"
        }

        out += busSnapshots(before, after, at)
        out += cellTest(before, after, at)

        val modelBefore = before.ml.model
        val modelAfter = after.ml.model
        if (modelBefore.segments != modelAfter.segments) {
            val segment = after.ml.recentSegments.lastOrNull()
            // Тяга і рекуперація ОКРЕМО, і це найважливіше поле цього рядка.
            //
            // Обидва числа порахував сам застосунок, інтегруючи миттєву потужність,
            // — незалежно від пожиттєвих лічильників BMS. А ті лічильники дають
            // дивне: за поїздку «прийнято» виходить 47 % від «віддано». Стільки
            // рекуперації на дорозі не буває: щоб повернути половину тягової
            // енергії, треба, щоб майже вся вона йшла в гальмування, а її їдять
            // повітря й кочення. Якщо власний інтеграл покаже звичні 15–25 %,
            // значить лічильник «прийнято» рахує не саму лише рекуперацію — і
            // питання про справжню ємність шкали закривається без глибокої зарядки.
            out += "$at seg n=${modelAfter.segments} km=${num(modelAfter.learnedKm)} " +
                "last=${num(segment?.distanceKm)}/${num(segment?.energyKwh)} " +
                "trac=${num(segment?.tractionKwh)} reg=${num(segment?.regenKwh)} " +
                "cov=${num(segment?.coverage)} spd=${segment?.speedSamples ?: -1}"
        }
        if (modelBefore.abortedSegments != modelAfter.abortedSegments) {
            out += "$at abort n=${modelAfter.abortedSegments} «${modelAfter.lastAbortReason}»"
        }
        if (modelBefore.sessionSpanPercent != modelAfter.sessionSpanPercent) {
            out += "$at capacity span=${num(modelAfter.sessionSpanPercent)} " +
                "target=${num(modelAfter.sessionTargetPercent)} " +
                "measured=${flag(modelAfter.capacityMeasured)} " +
                "kWh=${num(modelAfter.usableCapacityKwh)}"
        }

        if (before.curve.samples != after.curve.samples ||
            before.curve.powerSamples != after.curve.powerSamples ||
            before.curve.distanceSamples != after.curve.distanceSamples ||
            before.curve.fullChargeSamples != after.curve.fullChargeSamples
        ) {
            val curve = after.curve
            // Дві зміряні ємності поруч із заявленою: різниця між ними і є те, що
            // ця крива взагалі має сказати.
            out += "$at curve n=${curve.samples} covered=${num(curve.coveredPercent)} " +
                "total=${num(curve.totalKwh)} " +
                "byCounter=${num(curve.counterCapacityKwh)} " +
                "byCurrent=${num(curve.powerCapacityKwh)}/${curve.powerSamples} " +
                "km=${curve.distanceSamples} " +
                "charges=${curve.fullChargeSamples} " +
                "from=${num(curve.measuredFromPercent)} to=${num(curve.measuredToPercent)}"
        }

        // КОЖЕН ПРИЙНЯТИЙ ІНТЕРВАЛ КРИВОЇ, з усіма сирими числами.
        //
        // Рядок «curve n=…» вище каже лише підсумок, а розійшлися саме окремі
        // заміри: крива B дала 29.3 кВт·год на всю шкалу там, де сума відрізків за
        // той самий день дає близько сорока. Здогадуватися, які інтервали тягнуть
        // її вниз, немає сенсу — тут вони видно поштучно.
        val sample = after.curve.lastSample
        if (sample != null && sample.sequence != before.curve.lastSample?.sequence) {
            out += "$at curve+ from=${num(sample.fromPercent)} to=${num(sample.toPercent)} " +
                "out=${num(sample.outKwh)} in=${num(sample.counterInKwh)} " +
                "reg=${num(sample.regenKwh)} kW=${num(sample.averagePowerKw)} " +
                "km=${num(sample.km)} min=${num(sample.minutes)} " +
                "ticks=${sample.ticks} whole=${flag(sample.whole)} " +
                "A=${flag(sample.intoCounter)} B=${flag(sample.intoPower)} " +
                "km?=${flag(sample.intoDistance)}"
        }

        val accuracyBefore = before.rangeAccuracy
        val accuracyAfter = after.rangeAccuracy
        if (accuracyBefore.started && !accuracyAfter.started) {
            out += "$at accuracy reset driven=${num(accuracyBefore.drivenKm)} " +
                "drop=${num(accuracyBefore.predictedDropKm)}"
        }

        return out
    }

    /**
     * Мітка часу за годинником телефона. Без року: журнал живе дні, а не роки, а
     * кожен зайвий символ множиться на сотні тисяч рядків.
     */
    fun stamp(atMs: Long): String = STAMP.get()!!.format(Date(atMs))

    private fun num(value: Double?): String =
        when {
            value == null -> "-"
            !value.isFinite() -> "?"
            abs(value) >= 1000.0 -> String.format(Locale.US, "%.1f", value)
            else -> String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    /** Хвіст VIN: цього досить, щоб відрізнити авто, і замало, щоб його впізнати. */
    private fun vinTail(vin: String): String = if (vin.isEmpty()) "—" else "...${vin.takeLast(6)}"

    /**
     * SimpleDateFormat не потокобезпечний, а рядки складаються з корутини блока.
     * Один екземпляр на потік дешевший і за замок, і за створення формату щоразу.
     */
    private val STAMP = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * ЗНІМКИ ШИНИ — У ЖУРНАЛ, І ЦЕ НЕ ДРІБНИЦЯ.
     *
     * Знімок живе в пам'яті процесу: закрив застосунок — і полювання на біт
     * запалювання починається спочатку. Дивитися на нього можна лише на тому самому
     * екрані, а надіслати не можна взагалі. Журнал же й зберігається, і надсилається
     * однією кнопкою, — тож знімок пишеться сюди цілком, кадр за кадром.
     *
     * Разом зі знімком пишеться й різниця, якщо є з чим порівнювати: саме вона й
     * потрібна, а рахувати її вдруге по рядках журналу — марна робота.
     */
    /**
     * НОВІ КАДРИ, ЯКІ ЩЕ ПЕРЕВІРЯЮТЬСЯ: колеса, ручник, світло, годинник, ключ.
     *
     * Формули взяті з SoulEVSpy, писаної під рідну батарею, і одна з них уже
     * підвела: їхня швидкість із кадру 4F2 давала 128 км/год на місці. Тому кожне
     * число тут іде в журнал ЗІ СВОГО КАДРУ ОКРЕМИМ РЯДКОМ — не для краси, а щоб
     * після поїздки було з чим звірити: колеса проти пробігу, ключ проти того, що
     * дев'ять кадрів існують лише при запалюванні, секунди годинника — самі
     * проти себе.
     *
     * Рядок пишеться лише на ЗМІНУ: кадр 050 приходить раз на три хвилини й
     * здебільшого несе те саме.
     */
    private fun carSystems(before: State, after: State, at: String): List<String> {
        val out = mutableListOf<String>()
        val was = before.carSystems
        val now = after.carSystems

        if (now.wheels.known && now.wheels != was.wheels) {
            val w = now.wheels
            out += "$at wheel fl=${num(w.frontLeftKmh)} fr=${num(w.frontRightKmh)} " +
                "rl=${num(w.rearLeftKmh)} rr=${num(w.rearRightKmh)} " +
                "spread=${num(w.spreadKmh)} v4F0=${num(after.vehicle.speedKmh.takeIf { after.vehicle.hasSpeed })}"
        }

        if (now.drive.known && now.drive != was.drive) {
            out += "$at key ign=${flag(now.drive.ignitionOn)} v4F2=${num(now.drive.speedKmh)} " +
                "v4F0=${num(after.vehicle.speedKmh.takeIf { after.vehicle.hasSpeed })}"
        }

        if (now.brake.known && now.brake != was.brake) {
            out += "$at brake hand=${flag(now.brake.parkingBrakeOn)}"
        }

        if (now.cabin.known && now.cabin != was.cabin) {
            val c = now.cabin
            out += "$at cabin light=${c.lights} turn=${c.turnSignal} " +
                "wipe=${c.wipers}/${c.wiperStep}"
        }

        if (now.clock.known && now.clock != was.clock) {
            out += "$at clock car=${now.clock.text}"
        }

        return out
    }

    private fun busSnapshots(before: State, after: State, at: String): List<String> {
        val out = mutableListOf<String>()

        listOf(
            "А" to (before.probe.snapshotA to after.probe.snapshotA),
            "Б" to (before.probe.snapshotB to after.probe.snapshotB),
        ).forEach { (name, pair) ->
            val (was, now) = pair
            if (now == null || now == was) return@forEach
            out += "$at bus $name «${now.label}» кадрів=${now.frames.size}"
            now.frames.toSortedMap().forEach { (id, bytes) ->
                out += "$at bus $name $id " + bytes.joinToString(" ") { "%02X".format(it) }
            }
        }

        val hadBoth = before.probe.snapshotA != null && before.probe.snapshotB != null
        val hasBoth = after.probe.snapshotA != null && after.probe.snapshotB != null
        val changed = before.probe.snapshotA != after.probe.snapshotA ||
            before.probe.snapshotB != after.probe.snapshotB
        if (hasBoth && (changed || !hadBoth)) {
            FrameDiff.compare(after.probe.snapshotA!!.frames, after.probe.snapshotB!!.frames)
                .filterNot { it.onlyInOne }
                .forEach { frame ->
                    frame.changes.forEach { out += "$at bus? ${frame.id} ${it.describe()}" }
                }
        }
        return out
    }

    /**
     * Підсумок тесту комірок — теж у журнал.
     *
     * Тест триває хвилини, а його результат живе до наступного натискання. Без рядка
     * в журналі порівняти сьогоднішній тест із учорашнім неможливо, а саме порівняння
     * і покаже, чи комірка справді слабшає, чи це був один невдалий прохід.
     */
    private fun cellTest(before: State, after: State, at: String): List<String> {
        val was = before.cellTest.result
        val now = after.cellTest.result
        if (now == was || !now.hasCells) return emptyList()
        // Пишемо не кожен прохід, а зміну підсумку на завершеному тесті: рядок на
        // кожен прохід утопив би журнал у собі.
        if (after.cellTest.running) return emptyList()

        val out = mutableListOf(
            "$at cells проходів=${now.sweeps}/${now.steadySweeps} " +
                "розмах=${num(now.currentSpreadA)}А потужність=${num(now.averagePowerKw)}кВт " +
                "опір=${flag(now.resistanceKnown)}",
        )
        now.worstByResistance.take(WORST_CELLS).forEach { verdict ->
            out += "$at cells R №${verdict.index + 1} +${num(verdict.excessMilliOhm)} мОм"
        }
        now.worstByMinimum.take(WORST_CELLS).forEach { verdict ->
            out += "$at cells U №${verdict.index + 1} ${num(verdict.minVolts)} В " +
                "(-${num(verdict.minBelowMedianVolts * 1000.0)} мВ)"
        }
        if (now.note.isNotEmpty()) out += "$at cells? «${now.note}»"
        return out
    }

    /** Скільки найгірших комірок писати. Більше в журналі однаково не читають. */
    private const val WORST_CELLS = 5

}
