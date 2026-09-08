// ====================================================================================
// ЩО З ПЕРЕНЕСЕНОГО СПРАВДІ ПРАЦЮЄ (PortedValues)
//
// Усі числа, взяті з таблиці SoulEVSpy, зібрані в один список — і кожне з власним
// ВЕРДИКТОМ: приходить і схоже на правду, приходить і НЕ схоже, або не приходить
// зовсім.
//
// НАВІЩО ОКРЕМИЙ ВЕРДИКТ, А НЕ ПРОСТО ПОКАЗАТИ ЧИСЛА. Бо чуже число виглядає
// правдоподібно доти, доки його не перевірити, і ми на цьому вже спіймалися двічі.
// Спершу формула швидкості з кадру 4F2 дала 128 км/год на нерухомій машині — у
// старший біт там складено запалювання. Потім перша ж поїздка з новими полями
// показала «дозволено брати 90 кВт, дозволено вливати 90 кВт»: два різні за
// природою обмеження, які не можуть бути рівні, та ще й обидва рівно 90. Ці два
// приклади й задають правило: доки число не пройшло перехресну перевірку, воно
// стоїть тут із позначкою, а не в розрахунках.
//
// ЯК СКЛАДЕНІ ПЕРЕВІРКИ. Не «схоже на правду на око», а суперечність, яку можна
// написати рядком коду: дві межі не бувають рівні; вісім температур модулів не
// бувають однакові разом із трьома температурами пакета; швидкість із одного
// кадру мусить збігатися зі швидкістю з іншого; знос комірок не буває нульовим
// для всіх дев'яноста шести одразу.
//
// Файл навмисно без Compose: вердикти — це логіка, і вона перевіряється тестами.
// Малює цей список [CarSystemsScreen].
// ====================================================================================

package com.kirianov.kiasoulevplus2.car.screens

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.Data.CabinControls
import com.kirianov.kiasoulevplus2.Data.CarSystems
import com.kirianov.kiasoulevplus2.Data.PackHealth
import com.kirianov.kiasoulevplus2.Data.State
import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.Data.WiperSpeed
import com.kirianov.kiasoulevplus2.tools.format.formatDecimal
import kotlin.math.abs

/** Вердикт по одному перенесеному полю. */
enum class PortedState {
    /** Кадр іще не приходив або поле в ньому порожнє. */
    Missing,

    /** Прийшло, але суперечить іншому джерелу або самому собі. */
    Suspect,

    /** Прийшло й перехресну перевірку пройшло. */
    Working,
}

/** Один рядок таблиці: назва, значення, вердикт і чим саме він обґрунтований. */
data class PortedValue(
    val label: String,
    val text: String,
    val state: PortedState,
    val note: String = "",
)

/** Група рядків одного джерела: кадр і те, як його читати. */
data class PortedGroup(
    val title: String,
    val source: String,
    val values: List<PortedValue>,
)

object PortedValues {

    fun groups(state: State): List<PortedGroup> = listOf(
        limits(state.bms),
        temperatures(state.bms, state.packHealth),
        cellsByBms(state.bms, state.packHealth),
        service(state.bms),
        systems(state.carSystems, state.vehicle),
    )

    // --- Кадр 21 01: межі, які виставляє сама BMS ------------------------------

    /**
     * Дві межі потужності й прапорці роз'ємів.
     *
     * ГОЛОВНА ПЕРЕВІРКА ТУТ — РІВНІСТЬ. «Дозволено брати» й «дозволено вливати» —
     * різні за природою обмеження: перше падає на низькому заряді, друге на
     * високому. Однакові числа означають, що читаються не ті байти, і саме це
     * показала перша поїздка: 90 і 90.
     */
    private fun limits(bms: BmsData): PortedGroup {
        val values = buildList {
            val out = bms.availableDischargeKw
            val into = bms.availableChargeKw
            val bothKnown = bms.hasData && (out > 0.0 || into > 0.0)
            val equal = bothKnown && abs(out - into) < EQUAL_KW
            val tooBig = out > MAX_PLAUSIBLE_KW || into > MAX_PLAUSIBLE_KW

            add(
                PortedValue(
                    label = "Дозволено брати",
                    text = kw(out.takeIf { bothKnown }),
                    state = when {
                        !bothKnown -> PortedState.Missing
                        equal || tooBig -> PortedState.Suspect
                        else -> PortedState.Working
                    },
                    note = when {
                        !bothKnown -> "поле в кадрі 21 01 порожнє"
                        equal -> "рівно стільки ж, скільки «вливати» — не ті байти"
                        tooBig -> "більше за все, на що здатен цей привід"
                        else -> ""
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Дозволено вливати",
                    text = kw(into.takeIf { bothKnown }),
                    state = when {
                        !bothKnown -> PortedState.Missing
                        equal || tooBig -> PortedState.Suspect
                        else -> PortedState.Working
                    },
                    note = if (equal) "звірити на зарядці: там ця межа мусить падати" else "",
                ),
            )

            add(
                PortedValue(
                    label = "BMS каже «заряджаюсь»",
                    text = if (bms.hasData) yesNo(bms.bmsCharging) else DASH,
                    state = if (bms.hasData) PortedState.Working else PortedState.Missing,
                    note = "перевіряється лише на зарядці",
                ),
            )
            add(
                PortedValue(
                    label = "Роз'єм CHAdeMO",
                    text = if (bms.hasData) yesNo(bms.chademoPlugged) else DASH,
                    state = if (bms.hasData) PortedState.Working else PortedState.Missing,
                    note = "перевіряється лише зі вставленим роз'ємом",
                ),
            )
            add(
                PortedValue(
                    label = "Роз'єм Type 1",
                    text = if (bms.hasData) yesNo(bms.j1772Plugged) else DASH,
                    state = if (bms.hasData) PortedState.Working else PortedState.Missing,
                    note = "перевіряється лише зі вставленим роз'ємом",
                ),
            )
        }
        return PortedGroup("Межі потужності й роз'єми", "кадр 21 01", values)
    }

    // --- Температури з двох кадрів одразу -------------------------------------

    /**
     * Вісім модулів із кадру 21 01 і межі пакета з кадру 21 05 в одній групі — бо
     * перевіряють вони одне одного.
     *
     * ПЕРЕВІРКА: максимум і мінімум по пакету мусять обіймати вісім модулів. Якщо
     * усі одинадцять чисел однакові, це не «пакет вирівняний» — це один і той
     * самий байт, прочитаний одинадцять разів. Саме так виглядав перший живий
     * журнал: tMax=26 tMin=26 tIn=26 при восьми однакових модулях.
     */
    private fun temperatures(bms: BmsData, health: PackHealth): PortedGroup {
        val modules = bms.moduleTempsC
        val flatModules = modules.isNotEmpty() && modules.min() == modules.max()
        val flatPack = health.known && health.tempSpreadC == 0.0
        val allFlat = flatModules && flatPack && modules.isNotEmpty() &&
            modules.first() == health.maxTempC

        val values = buildList {
            add(
                PortedValue(
                    label = "Модуль 1",
                    text = temp(bms.batteryTempC.takeIf { bms.hasData }),
                    state = if (bms.hasData) PortedState.Working else PortedState.Missing,
                    note = "це поле застосунок читає давно, і воно перевірене",
                ),
            )
            add(
                PortedValue(
                    label = "Вісім модулів",
                    text = if (modules.isEmpty()) DASH else modules.joinToString(" / ") { temp(it) },
                    state = when {
                        modules.isEmpty() -> PortedState.Missing
                        allFlat -> PortedState.Suspect
                        else -> PortedState.Working
                    },
                    note = when {
                        modules.isEmpty() -> "кадр коротший за місце, де вони лежать"
                        allFlat -> "усі однакові разом із межами пакета — схоже на один байт"
                        flatModules -> "усі однакові: можливо, пакет справді вирівняний"
                        else -> "розкид ${temp(modules.max() - modules.min())}"
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Максимум по пакету",
                    text = temp(health.maxTempC.takeIf { health.known }),
                    state = verdict(health.known, !allFlat),
                    note = if (allFlat) "не відрізняється ні від мінімуму, ні від модулів" else "",
                ),
            )
            add(
                PortedValue(
                    label = "Мінімум по пакету",
                    text = temp(health.minTempC.takeIf { health.known }),
                    state = verdict(health.known, !allFlat),
                    note = if (health.known && !allFlat) {
                        "розкид ${temp(health.tempSpreadC)}"
                    } else {
                        ""
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Вхід контуру",
                    text = temp(health.inletTempC.takeIf { health.known }),
                    state = verdict(health.known, !allFlat),
                ),
            )
            add(
                PortedValue(
                    label = "Нагрівачі",
                    text = if (!health.known) {
                        DASH
                    } else {
                        "${temp(health.heater1TempC)} / ${temp(health.heater2TempC)}"
                    },
                    state = verdict(
                        health.known,
                        health.heater1TempC != 0.0 || health.heater2TempC != 0.0,
                    ),
                    note = "нуль на обох улітку теж можливий: нагрівачі просто холодні",
                ),
            )
            add(
                PortedValue(
                    label = "Вентилятор",
                    text = if (bms.hasData) "щабель ${bms.fanStep}, ${bms.fanHz} Гц" else DASH,
                    state = verdict(bms.hasData, bms.fanStep <= MAX_FAN_STEP),
                    note = if (bms.fanStep > MAX_FAN_STEP) "щабель буває лише 0-9" else "",
                ),
            )
        }
        return PortedGroup("Температури й охолодження", "кадри 21 01 і 21 05", values)
    }

    // --- Що BMS думає про комірки ---------------------------------------------

    /**
     * Найгірша й найкраща комірка очима самої BMS, і її ж оцінка зносу.
     *
     * ПЕРЕВІРКА НАПРУГ ПЕРЕХРЕСНА І НАЙСИЛЬНІША З УСІХ: ті самі дев'яносто шість
     * комірок застосунок читає сам, кадрами 21 02-21 04. Якщо BMS каже «найвища
     * 4.06», а наш власний максимум 3.98, читається не те.
     *
     * ЗНОС ЖЕ ПЕРЕВІРЯЄТЬСЯ САМ ПО СОБІ: нуль означав би, що комірки зносилися
     * начисто — при живій батареї це не число, а не те поле. Перший живий журнал
     * дав рівно нулі, тож знос поки не працює.
     */
    private fun cellsByBms(bms: BmsData, health: PackHealth): PortedGroup {
        val values = buildList {
            val known = bms.maxCellVolts > 0.0 && bms.minCellVolts > 0.0
            val ordered = bms.maxCellVolts >= bms.minCellVolts
            val inRange = bms.maxCellVolts in MIN_CELL_VOLTS..MAX_CELL_VOLTS &&
                bms.minCellVolts in MIN_CELL_VOLTS..MAX_CELL_VOLTS
            val numbered = bms.maxCellNumber in 1..CELL_COUNT && bms.minCellNumber in 1..CELL_COUNT

            add(
                PortedValue(
                    label = "Найвища комірка",
                    text = if (known) "${volts(bms.maxCellVolts)} (№${bms.maxCellNumber})" else DASH,
                    state = verdict(known, ordered && inRange && numbered),
                    note = when {
                        !known -> ""
                        !inRange -> "напруга комірки не буває такою"
                        !numbered -> "номер комірки поза 1-96"
                        else -> ""
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Найнижча комірка",
                    text = if (known) "${volts(bms.minCellVolts)} (№${bms.minCellNumber})" else DASH,
                    state = verdict(known, ordered && inRange && numbered),
                    note = if (known && ordered) {
                        "розкид ${millivolts(bms.maxCellVolts - bms.minCellVolts)}"
                    } else if (known) {
                        "найнижча вища за найвищу — байти переставлені"
                    } else {
                        ""
                    },
                ),
            )

            val wearKnown = health.known
            val wearZero = health.maxDeteriorationPercent == 0.0 &&
                health.minDeteriorationPercent == 0.0
            add(
                PortedValue(
                    label = "Знос найгіршої",
                    text = if (wearKnown) {
                        "${percent(health.maxDeteriorationPercent)} (№${health.maxDeteriorationCell})"
                    } else {
                        DASH
                    },
                    state = verdict(wearKnown, !wearZero),
                    note = if (wearZero) "нуль на всіх комірках: це не знос, а не те поле" else "",
                ),
            )
            add(
                PortedValue(
                    label = "Знос найкращої",
                    text = if (wearKnown) {
                        "${percent(health.minDeteriorationPercent)} (№${health.minDeteriorationCell})"
                    } else {
                        DASH
                    },
                    state = verdict(wearKnown, !wearZero),
                    note = if (wearKnown && !wearZero) {
                        "розкид ${percent(health.deteriorationSpread)}"
                    } else {
                        ""
                    },
                ),
            )

            add(
                PortedValue(
                    label = "SOC панелі з кадру 21 05",
                    text = percent(health.displaySoc.takeIf { health.known }),
                    state = verdict(
                        health.known,
                        abs(health.displaySoc - bms.displaySoc) < MAX_SOC_GAP || !bms.hasData,
                    ),
                    note = "мусить збігатися з SOC, який застосунок показує на головній",
                ),
            )
        }
        return PortedGroup("Комірки очима BMS", "кадри 21 01 і 21 05", values)
    }

    // --- Службові числа з кадру 21 01 -----------------------------------------

    private fun service(bms: BmsData): PortedGroup {
        val values = buildList {
            add(
                PortedValue(
                    label = "Акумулятор 12 В",
                    text = volts(bms.auxVolts.takeIf { it > 0.0 }),
                    state = verdict(bms.auxVolts > 0.0, bms.auxVolts in MIN_AUX..MAX_AUX),
                    note = "понад 13.5 В означає, що DC-DC працює",
                ),
            )
            add(
                PortedValue(
                    label = "Наробіток батареї",
                    text = if (bms.operatingSeconds > 0L) {
                        "${formatDecimal(bms.operatingHours, 0)} год"
                    } else {
                        DASH
                    },
                    state = verdict(
                        bms.operatingSeconds > 0L,
                        bms.operatingHours < MAX_PLAUSIBLE_HOURS,
                    ),
                    note = "мусить рости рівно на годину за годину",
                ),
            )
            add(
                PortedValue(
                    label = "Обороти мотора",
                    text = if (bms.hasData) "${bms.motorRpm} об/хв" else DASH,
                    state = verdict(bms.hasData, abs(bms.motorRpm) <= MAX_RPM),
                    note = "на місці мусить бути нуль, на ходу — рости зі швидкістю",
                ),
            )
        }
        return PortedGroup("Службові числа", "кадр 21 01", values)
    }

    // --- Нові широкомовні кадри -----------------------------------------------

    /**
     * П'ять кадрів, доданих у чергу прослуховування цим кроком.
     *
     * ПЕРЕВІРКА КОЛЕС І ЗАПАЛЮВАННЯ ПЕРЕХРЕСНА: швидкість приходить трьома різними
     * шляхами — кадром 4F0, який працює давно, кадром 4F2 і чотирма колесами. Три
     * джерела мусять сходитися; те, що не зійшлося, і є те, що прочитано не так.
     */
    private fun systems(systems: CarSystems, vehicle: VehicleData): PortedGroup {
        val busSpeed = vehicle.speedKmh.takeIf { vehicle.hasSpeed }
        val values = buildList {
            val wheels = systems.wheels
            val wheelGap = busSpeed?.let { abs(wheels.averageKmh - it) }
            add(
                PortedValue(
                    label = "Чотири колеса",
                    text = if (!wheels.known) {
                        DASH
                    } else {
                        wheels.all.joinToString(" / ") { formatDecimal(it, 1) } + " км/год"
                    },
                    state = verdict(wheels.known, wheelGap == null || wheelGap < MAX_SPEED_GAP),
                    note = when {
                        !wheels.known -> "кадр 4B0 ще не приходив"
                        wheelGap == null -> "звірити з кадром 4F0 можна лише на ходу"
                        wheelGap >= MAX_SPEED_GAP -> "середнє розходиться з кадром 4F0 на " +
                            "${formatDecimal(wheelGap, 1)} км/год"
                        else -> "розбіг колес ${formatDecimal(wheels.spreadKmh, 1)} км/год"
                    },
                ),
            )

            val drive = systems.drive
            val driveGap = busSpeed?.let { abs(drive.speedKmh - it) }
            add(
                PortedValue(
                    label = "Запалювання",
                    text = if (drive.known) yesNo(drive.ignitionOn) else DASH,
                    state = verdict(drive.known, true),
                    note = "біти 6-7 байта 2 кадру 4F2: саме вони змінюються з 00 на C0",
                ),
            )
            add(
                PortedValue(
                    label = "Швидкість із кадру 4F2",
                    text = if (drive.known) "${formatDecimal(drive.speedKmh, 1)} км/год" else DASH,
                    state = verdict(drive.known, driveGap == null || driveGap < MAX_SPEED_GAP),
                    note = when {
                        !drive.known -> "кадр 4F2 ще не приходив"
                        driveGap == null -> "звірити з кадром 4F0 можна лише на ходу"
                        driveGap >= MAX_SPEED_GAP -> "розходиться з кадром 4F0 на " +
                            "${formatDecimal(driveGap, 1)} км/год"
                        else -> "збігається з кадром 4F0"
                    },
                ),
            )

            add(
                PortedValue(
                    label = "Ручник",
                    text = if (systems.brake.known) yesNo(systems.brake.parkingBrakeOn) else DASH,
                    state = verdict(systems.brake.known, true),
                    note = "перевіряється просто: підняти й опустити",
                ),
            )

            val cabin = systems.cabin
            add(
                PortedValue(
                    label = "Світло",
                    text = if (cabin.known) lights(cabin) else DASH,
                    state = verdict(cabin.known, true),
                    note = "кадр 050 стоїть у черзі раз на три хвилини: це ознака, що кадр " +
                        "читається, а не живий покажчик",
                ),
            )
            add(
                PortedValue(
                    label = "Поворотник і щітки",
                    text = if (cabin.known) {
                        "${cabin.turnSignal} / ${wipers(cabin)}"
                    } else {
                        DASH
                    },
                    state = verdict(cabin.known, true),
                ),
            )

            add(
                PortedValue(
                    label = "Годинник авто",
                    text = if (systems.clock.known) systems.clock.text else DASH,
                    state = verdict(systems.clock.known, true),
                    note = "за джерело часу вже відкинутий; тут — щоб побачити, що байти " +
                        "кадру взято правильно",
                ),
            )
        }
        return PortedGroup("Системи авто", "кадри 4B0, 4F2, 433, 050, 567", values)
    }

    // --- Дрібниці --------------------------------------------------------------

    /** Немає даних — це не «підозріло», а окремий стан: перевіряти ще нічого. */
    private fun verdict(known: Boolean, plausible: Boolean): PortedState = when {
        !known -> PortedState.Missing
        plausible -> PortedState.Working
        else -> PortedState.Suspect
    }

    private fun lights(cabin: CabinControls): String = cabin.lights.toString()

    private fun wipers(cabin: CabinControls): String =
        if (cabin.wipers == WiperSpeed.Intermittent) {
            "${cabin.wipers} ${cabin.wiperStep}"
        } else {
            cabin.wipers.toString()
        }

    private fun kw(value: Double?): String = value?.let { "${formatDecimal(it, 1)} кВт" } ?: DASH

    private fun temp(value: Double?): String = value?.let { "${formatDecimal(it, 0)} °C" } ?: DASH

    private fun volts(value: Double?): String = value?.let { "${formatDecimal(it, 2)} В" } ?: DASH

    private fun millivolts(value: Double): String = "${formatDecimal(value * 1000.0, 0)} мВ"

    private fun percent(value: Double?): String = value?.let { "${formatDecimal(it, 1)} %" } ?: DASH

    private fun yesNo(value: Boolean): String = if (value) "так" else "ні"

    private const val DASH = "—"

    /** Дві межі, що відрізняються менше ніж на це, вважаються однаковими. */
    private const val EQUAL_KW = 0.05

    /** Понад це не буває ні прийому, ні віддачі на цьому приводі. */
    private const val MAX_PLAUSIBLE_KW = 150.0

    private const val MAX_FAN_STEP = 9

    private const val MIN_CELL_VOLTS = 2.0
    private const val MAX_CELL_VOLTS = 4.5
    private const val CELL_COUNT = 96

    /** Наскільки два джерела SOC можуть розійтися й лишитися тим самим числом, %. */
    private const val MAX_SOC_GAP = 3.0

    private const val MIN_AUX = 8.0
    private const val MAX_AUX = 16.0

    /** Двадцять років безперервної роботи: більше — не наробіток. */
    private const val MAX_PLAUSIBLE_HOURS = 175_000.0

    private const val MAX_RPM = 12_000

    /** Наскільки два джерела швидкості можуть розійтися, км/год. */
    private const val MAX_SPEED_GAP = 10.0
}
