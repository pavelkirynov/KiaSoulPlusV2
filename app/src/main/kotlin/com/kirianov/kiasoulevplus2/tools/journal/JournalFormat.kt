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

        val modelBefore = before.ml.model
        val modelAfter = after.ml.model
        if (modelBefore.segments != modelAfter.segments) {
            val segment = after.ml.recentSegments.lastOrNull()
            out += "$at seg n=${modelAfter.segments} km=${num(modelAfter.learnedKm)} " +
                "last=${num(segment?.distanceKm)}/${num(segment?.energyKwh)} " +
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

        if (before.curve.samples != after.curve.samples) {
            val curve = after.curve
            out += "$at curve n=${curve.samples} covered=${num(curve.coveredPercent)} " +
                "full=${num(curve.fullKwh)} " +
                "from=${num(curve.measuredFromPercent)} to=${num(curve.measuredToPercent)}"
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

    /**
     * SimpleDateFormat не потокобезпечний, а рядки складаються з корутини блока.
     * Один екземпляр на потік дешевший і за замок, і за створення формату щоразу.
     */
    private val STAMP = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
