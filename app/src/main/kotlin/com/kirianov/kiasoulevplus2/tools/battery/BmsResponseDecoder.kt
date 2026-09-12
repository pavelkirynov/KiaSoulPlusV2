// ====================================================================================
// ДЕКОДЕР ОСНОВНОГО КАДРУ BMS 21 01 (BmsResponseDecoder)
//
// Перетворює байти кадру на BmsData. Нічого не надсилає і нічого не зберігає,
// тому кожну формулу можна перевірити тестом на синтетичному кадрі.
//
// Розкладка байтів — та сама таблиця Kia Soul EV, з якої вже взяті перевірені
// SOC (байт 6) та напруга пакета (байти 14-15).
//
// ЗВІДКИ РЕШТА ЗМІЩЕНЬ. Із відкритого SoulEVSpy (github.com/pemessier/SoulEVSpy,
// Apache 2.0) — але не копіюванням коду, а перекладом його таблиці в нашу систему
// відліку. У них байти нумеруються всередині кадрів ISO-TP: `line2X.get(n)` — це
// n-й байт X-го consecutive frame, по сім байтів у кожному. Наш індекс плоский, і
// перехід між ними такий:
//
//     наш = 6 + 7 × (X − 1) + n
//
// Формула перевірена на трьох полях, які ми читали ЩЕ ДО того, як побачили їхній
// код: напруга пакета (їхнє line22.get(1) → наш 14), струм (line21.get(6) → 12) і
// лічильник прийнятих ампер-годин (line24.get(5) → 32). Усі три сходяться до
// байта, тож решту таблиці можна переносити впевнено.
//
// І ОДРАЗУ ЗАСТЕРЕЖЕННЯ, ЯКЕ КОШТУВАЛО Б НАМ ХИБНИХ ЧИСЕЛ. Ту саму таблицю не
// можна брати на віру: їхній розбір кадру 4F2 («точна швидкість») на цій машині
// дає 128 км/год у нерухомого авто, бо біт, який вони вважають дев'ятим бітом
// швидкості, тут виявився бітом запалювання. Тому кожне число нижче або вже
// зійшлося з нашими вимірами, або чекає перевірки на живій машині.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

object BmsResponseDecoder {

    private const val SOC_INDEX = 6              // SOC * 2
    private const val CURRENT_HIGH_INDEX = 12    // знакове 16-бітне, * 10
    private const val VOLTAGE_HIGH_INDEX = 14    // беззнакове 16-бітне, * 10
    /**
     * Байт 16 — температура ПЕРШОГО МОДУЛЯ, а не максимум по пакету.
     *
     * Так у таблиці SoulEVSpy: 16-19 і 20-22 та 24 — це вісім модулів поспіль, а
     * справжні максимум і мінімум лежать в іншому кадрі, 21 05. Раніше це поле
     * називалося в нас «максимальна температура модулів»; на восьми однакових
     * модулях різниця невелика, але називати перший модуль максимумом однаково
     * не можна.
     */
    private const val TEMP_FIRST_MODULE_INDEX = 16

    /** Дозволена потужність, 16-бітна, × 0.01 кВт. */
    private const val CHARGE_LIMIT_INDEX = 7
    private const val DISCHARGE_LIMIT_INDEX = 9

    /** Прапорці зарядки: біт 7 — заряджається, 6 — CHAdeMO, 5 — Type 1. */
    private const val CHARGE_FLAGS_INDEX = 11
    private const val FLAG_CHARGING = 0x80
    private const val FLAG_CHADEMO = 0x40
    private const val FLAG_J1772 = 0x20

    /**
     * Вісім модулів лежать НЕ підряд: після сьомого стоїть байт, який до
     * температур не належить. Тому список зміщень, а не діапазон.
     */
    private val MODULE_TEMP_INDEXES = listOf(16, 17, 18, 19, 20, 21, 22, 24)

    private const val MAX_CELL_VOLTS_INDEX = 25
    private const val MAX_CELL_NUMBER_INDEX = 26
    private const val MIN_CELL_VOLTS_INDEX = 27
    private const val MIN_CELL_NUMBER_INDEX = 28
    private const val FAN_STEP_INDEX = 29
    private const val FAN_HZ_INDEX = 30
    private const val AUX_VOLTS_INDEX = 31

    /** Час роботи батареї, 32-бітний, у секундах. */
    private const val OPERATING_TIME_INDEX = 48

    /** Обороти мотора, 16-бітні. */
    private const val MOTOR_RPM_INDEX = 55

    /** Крок напруги комірки той самий, що й у кадрах 21 02-04. */
    private const val VOLTS_PER_CELL_STEP = 0.02

    // Лічильники за весь час життя батареї. Лежать поспіль, по чотири байти,
    // усі в десятих своєї одиниці:
    //
    //   32  прийнято, Ач        36  віддано, Ач
    //   40  прийнято, кВт·год   44  віддано, кВт·год
    //
    // ЧОМУ ЦЕ ВАЖЛИВО. Спершу як кВт·год читалися саме зсуви 32 і 36, тобто
    // амперу-години. Помилку видно перехресною перевіркою: кВт·год поділити на Ач
    // мусить дати середню напругу пакета. На реальних даних це 366.8 В для заряду
    // і 353.4 В для розряду — обидва в межах робочої напруги Soul EV, і зарядна
    // вище за розрядну, як і має бути. З Ач на місці кВт·год витрата на 100 км
    // виходила завищеною приблизно в 2.7 раза.
    private const val CHARGED_AH_INDEX = 32
    private const val DISCHARGED_AH_INDEX = 36
    private const val CHARGED_KWH_INDEX = 40
    private const val DISCHARGED_KWH_INDEX = 44

    /** Найбільший індекс основних показників: коротший кадр розбирати немає сенсу. */
    private const val MIN_FRAME_SIZE = TEMP_FIRST_MODULE_INDEX + 1

    /**
     * Понад цю межу значення лічильника не може бути фізичним і означає, що ми
     * прочитали не ті байти. Краще показати прочерк, ніж правдоподібне сміття.
     */
    private const val MAX_PLAUSIBLE_LIFETIME_COUNTER = 1_000_000.0

    /**
     * Повертає BmsData з кадру. Якщо кадр коротший за очікуваний — повертає
     * порожній BmsData з displaySoc = NO_DATA, щоб UI показав «--», а не нулі.
     */
    fun decode(bytes: List<Int>): BmsData {
        if (bytes.size < MIN_FRAME_SIZE) return BmsData()

        return decoded(bytes).takeIf { plausible(it) } ?: BmsData()
    }

    /**
     * Чи схоже це взагалі на показники батареї.
     *
     * Довжина кадру нічого не гарантує: у журналі є рядок «socD 127.5, I −1043.5 А,
     * U 6455.1 В» — це кадр із самих 0xFF, який прийшов посеред зарядки, коли
     * адаптер захлинувся. Довжина в нього правильна, а числа — ні, і далі вони
     * ідуть на екран і в криву нарівні зі справжніми.
     *
     * Межі взяті з широким запасом до всього, що Soul EV може віддати: заряд не
     * буває більшим за сотню, пакет живе між 240 і 420 В, а струм навіть на
     * швидкій зарядці й повному газі не сягає й трьохсот ампер.
     */
    private fun plausible(bms: BmsData): Boolean =
        bms.displaySoc <= MAX_SOC &&
            bms.batteryVoltage <= MAX_PACK_VOLTS &&
            kotlin.math.abs(bms.batteryCurrent) <= MAX_CURRENT_A

    private const val MAX_SOC = 100.0
    private const val MAX_PACK_VOLTS = 500.0
    private const val MAX_CURRENT_A = 600.0

    private fun decoded(bytes: List<Int>): BmsData {
        return BmsData(
            displaySoc = bytes[SOC_INDEX] / 2.0,
            batteryVoltage = FrameParser.unsigned16(bytes, VOLTAGE_HIGH_INDEX) / 10.0,
            // Знак ІНВЕРТУЄТЬСЯ. Сире значення на цьому авто додатне саме на
            // розряді — це видно з журналу поїздки: розгін до 95 км/год ішов із
            // I=+147 А, і напруга при цьому просідала з 390 до 381 В, тобто
            // батарея віддавала. Від'ємне сире з'являлося рівно на гальмуванні,
            // коли напруга підскакувала до 397 В, тобто батарея приймала.
            //
            // Домовленість застосунку протилежна (від'ємне = розряд), і поки
            // знак не переверталося тут, ВСЕ навчання йшло навиворіт: тяга
            // зараховувалася як рекуперація, і відрізок на 6.1 км виходив із
            // енергією -0.98 кВт·год. Модель училася, що авто їздить задарма.
            batteryCurrent = -FrameParser.signed16(bytes, CURRENT_HIGH_INDEX) / 10.0,
            batteryTempC = FrameParser.signed8(bytes, TEMP_FIRST_MODULE_INDEX).toDouble(),
            cumulativeChargedAh = counterAt(bytes, CHARGED_AH_INDEX),
            cumulativeDischargedAh = counterAt(bytes, DISCHARGED_AH_INDEX),
            cumulativeEnergyChargedKwh = counterAt(bytes, CHARGED_KWH_INDEX),
            cumulativeEnergyDischargedKwh = counterAt(bytes, DISCHARGED_KWH_INDEX),

            // Далі — те, що лежало в цьому ж кадрі й досі викидалося. Кожне поле
            // перевіряє свою довжину саме тут: короткий кадр не повинен позбавляти
            // застосунок ні заряду, ні напруги, які лежать на початку.
            availableChargeKw = word(bytes, CHARGE_LIMIT_INDEX) * 0.01,
            availableDischargeKw = word(bytes, DISCHARGE_LIMIT_INDEX) * 0.01,
            bmsCharging = flag(bytes, CHARGE_FLAGS_INDEX, FLAG_CHARGING),
            chademoPlugged = flag(bytes, CHARGE_FLAGS_INDEX, FLAG_CHADEMO),
            j1772Plugged = flag(bytes, CHARGE_FLAGS_INDEX, FLAG_J1772),
            moduleTempsC = moduleTemps(bytes),
            maxCellVolts = byteAt(bytes, MAX_CELL_VOLTS_INDEX) * VOLTS_PER_CELL_STEP,
            maxCellNumber = byteAt(bytes, MAX_CELL_NUMBER_INDEX),
            minCellVolts = byteAt(bytes, MIN_CELL_VOLTS_INDEX) * VOLTS_PER_CELL_STEP,
            minCellNumber = byteAt(bytes, MIN_CELL_NUMBER_INDEX),
            fanStep = byteAt(bytes, FAN_STEP_INDEX),
            fanHz = byteAt(bytes, FAN_HZ_INDEX),
            auxVolts = byteAt(bytes, AUX_VOLTS_INDEX) / 10.0,
            operatingSeconds = longCounterAt(bytes, OPERATING_TIME_INDEX),
            motorRpm = word(bytes, MOTOR_RPM_INDEX),
        )
    }

    /** Байт, якого в кадрі може й не бути: нуль тоді означає «не прочитали». */
    private fun byteAt(bytes: List<Int>, index: Int): Int = bytes.getOrElse(index) { 0 }

    private fun word(bytes: List<Int>, index: Int): Int =
        if (bytes.size <= index + 1) 0 else FrameParser.unsigned16(bytes, index)

    private fun flag(bytes: List<Int>, index: Int, mask: Int): Boolean =
        byteAt(bytes, index) and mask != 0

    private fun longCounterAt(bytes: List<Int>, index: Int): Long =
        if (bytes.size < index + 4) 0L else FrameParser.unsigned32(bytes, index).toLong()

    /**
     * Вісім температур модулів. Порожній список, а не вісім нулів: нуль градусів —
     * законна температура, і відрізнити її від «не прочитали» треба на око.
     */
    private fun moduleTemps(bytes: List<Int>): List<Double> {
        if (bytes.size <= MODULE_TEMP_INDEXES.last()) return emptyList()
        return MODULE_TEMP_INDEXES.map { FrameParser.signed8(bytes, it).toDouble() }
    }

    /**
     * Лічильники читаються окремо від решти й КОЖЕН перевіряє свою довжину:
     * короткий кадр не повинен позбавляти додаток ні заряду з напругою, які лежать
     * на початку, ні тих лічильників, які до кадру все ж увійшли.
     */
    private fun counterAt(bytes: List<Int>, index: Int): Double {
        if (bytes.size < index + 4) return 0.0

        val value = FrameParser.unsigned32(bytes, index) / 10.0
        return if (value <= MAX_PLAUSIBLE_LIFETIME_COUNTER) value else 0.0
    }
}
