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
import com.kirianov.kiasoulevplus2.Data.TireData
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
        tires(state.tires),
    )

    /**
     * Тиск у шинах — те, чого в SoulEVSpy немає взагалі.
     *
     * Там читаються лише швидкості обертання коліс; тиск лежить у власному блоці
     * 7A0, який на цій машині озивається. Розкладка байтів узята з наборів Torque
     * та Car Scanner для Hyundai/Kia того ж покоління й НЕ ПЕРЕВІРЕНА: якщо тут
     * прочерк, дивитися треба в журнал — сира відповідь блока лежить там цілком.
     */
    private fun tires(tires: TireData): PortedGroup {
        val values = buildList {
            add(
                PortedValue(
                    label = "Тиск, бар",
                    text = if (tires.hasPressures) {
                        tires.pressuresBar.joinToString(" / ") { formatDecimal(it, 2) }
                    } else {
                        DASH
                    },
                    state = verdict(tires.hasPressures, true),
                    note = if (tires.hasPressures) {
                        "розкид ${formatDecimal(tires.spreadBar, 2)} бар; порядок колес " +
                            "(перед лівий, перед правий, зад лівий, зад правий) — здогад"
                    } else {
                        "блок 7A0 ще не відповів або відповів не тим: сира відповідь у журналі"
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Температура датчиків",
                    text = if (tires.known && tires.tempsC.size == TireData.WHEELS) {
                        tires.tempsC.joinToString(" / ") { formatDecimal(it, 0) } + " °C"
                    } else {
                        DASH
                    },
                    state = verdict(tires.known && tires.tempsC.isNotEmpty(), true),
                    note = "мусить бути близько до температури за бортом на стоянці й вище " +
                        "за неї після їзди",
                ),
            )
        }
        return PortedGroup("Тиск у шинах", "блок 7A0, запит 22 C0 0B", values)
    }

    // --- Кадр 21 01: межі, які виставляє сама BMS ------------------------------

    /**
     * Дві межі потужності й прапорці роз'ємів.
     *
     * ЦЯ ГРУПА ВЖЕ ОДИН РАЗ ЗБИЛА НАС ІЗ ПАНТЕЛИКУ, і виправлення варте того, щоб
     * лежати тут. Перший журнал показав «брати 90, вливати 90», і рівність двох
     * різних за природою обмежень виглядала як доказ не тих байтів. Насправді це
     * СТАН СПОКОЮ: поки нічого не відбувається, обидві межі стоять на стелі
     * дев'яноста кіловат. На живій зарядці той самий байт дав 65-78 кВт на прийом
     * при 88-90 на віддачу — тобто байти правильні, а рівність нічого не значила.
     *
     * Мораль ширша за цей рядок: «два числа однакові» саме по собі не суперечність.
     * Суперечністю воно стає лише тоді, коли ми знаємо, що вони МУСЯТЬ різнитися, —
     * а в спокої вони не мусять.
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
                    state = verdict(bothKnown, !tooBig),
                    note = when {
                        !bothKnown -> "поле в кадрі 21 01 порожнє"
                        tooBig -> "більше за все, на що здатен цей привід"
                        else -> ""
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Дозволено вливати",
                    text = kw(into.takeIf { bothKnown }),
                    state = verdict(bothKnown, !tooBig),
                    note = if (equal) {
                        "обидві межі на стелі 90 кВт — так виглядає спокій; на зарядці " +
                            "ця падає (в журналі 65-78 кВт)"
                    } else {
                        ""
                    },
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
        val moduleSpread = if (modules.isEmpty()) 0.0 else modules.max() - modules.min()
        val flatModules = modules.isNotEmpty() && moduleSpread == 0.0
        val flatPack = health.known && health.tempSpreadC == 0.0

        // СПРАВЖНЯ СУПЕРЕЧНІСТЬ ТУТ ОДНА: пакет не може бути рівним, поки його ж
        // модулі різняться. Перший журнал дав саме це — «максимум 29, мінімум 29»
        // при восьми модулях від 24 до 28. Отже, ці два байти з кадру 21 05 не є
        // межами пакета, хоч температура входу поруч із ними ходить окремо й
        // правдоподібно.
        val allFlat = flatPack && moduleSpread > MIN_REAL_SPREAD_C

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
                    state = verdict(modules.isNotEmpty(), true),
                    note = when {
                        modules.isEmpty() -> "кадр коротший за місце, де вони лежать"
                        flatModules -> "усі однакові: можливо, пакет справді вирівняний"
                        else -> "розкид ${temp(moduleSpread)}"
                    },
                ),
            )
            add(
                PortedValue(
                    label = "Максимум по пакету",
                    text = temp(health.maxTempC.takeIf { health.known }),
                    state = verdict(health.known, !allFlat),
                    note = if (allFlat) {
                        "дорівнює мінімуму, хоч самі модулі різняться на " +
                            "${temp(moduleSpread)} — це не межа пакета"
                    } else {
                        ""
                    },
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
                    // Цей байт ходить окремо від пари «максимум-мінімум»: у журналі
                    // стояло tMax=29 tMin=29 tIn=30. Отже, зміщення саме його
                    // правильне, і в чужі підозри його тягнути нема за що.
                    state = verdict(health.known, true),
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
            val ratio = busSpeed
                ?.takeIf { it >= MIN_SPEED_TO_COMPARE && wheels.known }
                ?.let { wheels.averageKmh / it }
            add(
                PortedValue(
                    label = "Чотири колеса",
                    text = if (!wheels.known) {
                        DASH
                    } else {
                        wheels.all.joinToString(" / ") { formatDecimal(it, 1) } + " км/год"
                    },
                    // ДІЛЬНИК ПЕРЕВІРЕНИЙ, І ПЕРЕВІРИЛА ЙОГО КУПА ЗАМІРІВ, А НЕ
                    // ОДИН. Спершу колеса двічі підряд вийшли на 11-14 % вище за
                    // кадр 4F0, і це виглядало як хибний дільник. За цілий день
                    // журналу таких пар набралося двадцять три, і відношення в них
                    // розкидане в обидва боки — від 0.56 до 1.61 з серединою
                    // близько одиниці. Хибний дільник дав би постійний перекіс, а
                    // розкид у обидва боки дає інше: два вікна монітора знімаються
                    // за п'ять-десять секунд одне від одного, і на розгоні чи
                    // гальмуванні за цей час швидкість справді змінюється.
                    //
                    // Тому межа тут широка навмисно. Вона ловить хибний дільник
                    // (той дав би 2 чи 0.5 на РІВНІЙ швидкості), а не різницю в
                    // часі зняття, за яку кадр не відповідає.
                    state = verdict(
                        wheels.known,
                        ratio == null || ratio in MIN_SPEED_RATIO..MAX_SPEED_RATIO,
                    ),
                    note = when {
                        !wheels.known -> "кадр 4B0 ще не приходив"
                        ratio == null -> "звірити з кадром 4F0 можна лише на ходу"
                        else -> "×${formatDecimal(ratio, 2)} до кадру 4F0 " +
                            "(${formatDecimal(busSpeed ?: 0.0, 1)} км/год), розбіг колес " +
                            "${formatDecimal(wheels.spreadKmh, 1)} км/год"
                    },
                ),
            )

            val drive = systems.drive
            val driveGap = busSpeed?.let { abs(drive.speedKmh - it) }
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
                    label = "Гальмо: біт 3 байта 2 (433)",
                    text = if (systems.brake.known) yesNo(systems.brake.brakeBit) else DASH,
                    // ПОЗНАЧКА ТУТ ПРО ЗНАЧЕННЯ, А НЕ ПРО ЧИТАННЯ. Біт приходить
                    // справно, і за два журнали в нього вже видно закономірність:
                    // нуль БУВАЄ ЛИШЕ НА СТОЯНЦІ (12 замірів із 12), одиниця — в
                    // русі й інколи на нерухомій машині. Це схоже на інверсію
                    // того, що написано в SoulEVSpy: не «ручник піднято», а
                    // «гальмо звільнене». Остаточно скаже лева на місці.
                    state = verdict(systems.brake.known, false),
                    note = "SoulEVSpy зве це ручником, але нуль тут буває лише на стоянці, " +
                        "а в русі завжди одиниця — схоже на «гальмо звільнене», тобто " +
                        "навпаки. Перевірка: підняти ручник на місці",
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

    /**
     * Межі відношення «колеса до кадру 4F0», за якими це вже не різниця в часі.
     *
     * Розкид у живому журналі — від 0.56 до 1.61 при правильному дільнику, бо два
     * вікна монітора знімаються за п'ять-десять секунд одне від одного. Хибний
     * дільник дав би постійний перекіс, тож ловити треба саме грубий, а не будь-яку
     * розбіжність.
     */
    private const val MIN_SPEED_RATIO = 0.4
    private const val MAX_SPEED_RATIO = 2.5

    /**
     * Нижче цієї швидкості звіряти джерела нема сенсу, км/год.
     *
     * На малому ходу відношення двох чисел злітає в небо від будь-якої різниці в
     * часі зняття: 2 км/год проти 1 це «×2», хоч насправді це та сама мить.
     */
    private const val MIN_SPEED_TO_COMPARE = 20.0

    /**
     * Розкид температур, який уже точно не похибка округлення, °C.
     *
     * Модулі приходять цілими градусами, тож один градус різниці буває просто
     * округленням двох близьких чисел у різні боки.
     */
    private const val MIN_REAL_SPREAD_C = 1.0
}
