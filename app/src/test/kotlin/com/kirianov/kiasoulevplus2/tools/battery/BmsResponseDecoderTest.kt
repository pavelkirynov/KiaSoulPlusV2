package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BmsResponseDecoderTest {

    /**
     * Синтетичний кадр 21 01 із відомими значеннями на позиціях, які читає декодер:
     * [6] SOC*2, [12..13] струм, [14..15] напруга, [16] температура.
     */
    private fun frame(
        socRaw: Int = 0xA0,           // 160 / 2 = 80.0 %
        currentRaw: Int = 0xFFF6,     // -10 -> -1.0 A
        voltageRaw: Int = 0x0E4C,     // 3660 -> 366.0 V
        tempRaw: Int = 0x19,          // 25 °C
        chargedAhRaw: Long = 734_373,      // -> 73437.3 Ач
        dischargedAhRaw: Long = 732_608,   // -> 73260.8 Ач
        chargedRaw: Long = 269_379,        // -> 26937.9 кВт·год
        dischargedRaw: Long = 258_908,     // -> 25890.8 кВт·год
        size: Int = 52,
    ): List<Int> = MutableList(size) { 0 }.apply {
        this[6] = socRaw
        this[12] = currentRaw shr 8 and 0xFF
        this[13] = currentRaw and 0xFF
        this[14] = voltageRaw shr 8 and 0xFF
        this[15] = voltageRaw and 0xFF
        this[16] = tempRaw
        if (size >= 36) putUnsigned32(32, chargedAhRaw)
        if (size >= 40) putUnsigned32(36, dischargedAhRaw)
        if (size >= 44) putUnsigned32(40, chargedRaw)
        if (size >= 48) putUnsigned32(44, dischargedRaw)
    }

    private fun MutableList<Int>.putUnsigned32(index: Int, value: Long) {
        this[index] = (value shr 24 and 0xFF).toInt()
        this[index + 1] = (value shr 16 and 0xFF).toInt()
        this[index + 2] = (value shr 8 and 0xFF).toInt()
        this[index + 3] = (value and 0xFF).toInt()
    }

    @Test
    fun `decodes soc voltage current and temperature`() {
        val data = BmsResponseDecoder.decode(frame())

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertEquals(1.0, data.batteryCurrent, 0.001)
        assertEquals(25.0, data.batteryTempC, 0.001)
        assertTrue(data.hasData)
    }

    /**
     * Знак сирого значення на цьому авто протилежний домовленості застосунку.
     *
     * Це не припущення, а вимір: у журналі поїздки розгін ішов із сирим +147 А і
     * просадкою напруги з 390 до 381 В (батарея віддає), а гальмування — із
     * сирим -122 А і підскоком до 397 В (батарея приймає). Тому декодер
     * інвертує знак, і саме це тут і перевіряється.
     */
    @Test
    fun `a raw positive current means discharge, not charge`() {
        val data = BmsResponseDecoder.decode(frame(currentRaw = 0x00C8)) // сире 200 -> 20.0 A
        assertEquals(-20.0, data.batteryCurrent, 0.001)
    }

    @Test
    fun `a raw negative current means charge`() {
        val data = BmsResponseDecoder.decode(frame(currentRaw = 0xFF38)) // сире -200 -> -20.0 A
        assertEquals(20.0, data.batteryCurrent, 0.001)
    }

    @Test
    fun `decodes a temperature below zero`() {
        val data = BmsResponseDecoder.decode(frame(tempRaw = 0xFB)) // -5 °C
        assertEquals(-5.0, data.batteryTempC, 0.001)
    }

    @Test
    fun `reports no data for a truncated frame instead of showing zeroes`() {
        val data = BmsResponseDecoder.decode(listOf(0x61, 0x01, 0x00))

        assertEquals(BmsData.NO_DATA, data.displaySoc, 0.001)
        assertFalse(data.hasData)
    }

    @Test
    fun `reports no data for an empty frame`() {
        assertFalse(BmsResponseDecoder.decode(emptyList()).hasData)
    }

    @Test
    fun `decodes the lifetime energy counters`() {
        val data = BmsResponseDecoder.decode(frame())

        assertEquals(26937.9, data.cumulativeEnergyChargedKwh, 0.001)
        assertEquals(25890.8, data.cumulativeEnergyDischargedKwh, 0.001)
        assertEquals(73437.3, data.cumulativeChargedAh, 0.001)
        assertEquals(73260.8, data.cumulativeDischargedAh, 0.001)
        assertTrue(data.hasEnergyCounters)
    }

    /**
     * Лічильники лежать далеко в кадрі. Якщо адаптер віддав коротшу відповідь,
     * заряд і напруга все одно мають дійти — інакше одна відсутня величина
     * гасила б увесь екран.
     */
    @Test
    fun `a frame too short for the counters still yields soc and voltage`() {
        val data = BmsResponseDecoder.decode(frame(size = 20))

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertFalse(data.hasEnergyCounters)
        assertEquals(0.0, data.cumulativeEnergyDischargedKwh, 0.001)
    }

    /** Читання не тих байтів дало б правдоподібне сміття; краще прочерк. */
    @Test
    fun `an implausible counter is reported as absent`() {
        val data = BmsResponseDecoder.decode(frame(dischargedRaw = 4_000_000_000L))

        assertFalse(data.hasEnergyCounters)
    }

    @Test
    fun `decodes an end-to-end raw adapter reply`() {
        // Той самий кадр, але у вигляді, в якому його віддає ELM327.
        val raw = "0: 61 01 00 00 00 00 A0 00\r" +
            "1: 00 00 00 00 FF F6 0E 4C\r" +
            "2: 19 00 00 00 00 00 00 00\r>"

        val data = BmsResponseDecoder.decode(FrameParser.parse(raw))

        assertEquals(80.0, data.displaySoc, 0.001)
        assertEquals(366.0, data.batteryVoltage, 0.001)
        assertEquals(1.0, data.batteryCurrent, 0.001)
        assertEquals(25.0, data.batteryTempC, 0.001)
    }

    /**
     * Регресія на реальну помилку: як кВт·год читалися зсуви 32 і 36, тобто
     * амперу-години. Числа взяті з Soul EV Spy на тій самій машині, тому цей тест
     * ловить саме підміну однієї одиниці іншою, а не абстрактне значення.
     */
    @Test
    fun `the kilowatt-hour counters are not the ampere-hour ones`() {
        val data = BmsResponseDecoder.decode(frame())

        // Ач і кВт·год — різні числа з різних місць кадру.
        assertNotEquals(data.cumulativeChargedAh, data.cumulativeEnergyChargedKwh, 0.001)
        assertNotEquals(data.cumulativeDischargedAh, data.cumulativeEnergyDischargedKwh, 0.001)

        // Перехресна перевірка, якою помилка й виявилася: кВт·год поділити на Ач
        // мусить дати середню напругу пакета. Поза цією смугою прочитано не ті байти.
        val chargeVolts = data.cumulativeEnergyChargedKwh / data.cumulativeChargedAh * 1000.0
        val dischargeVolts = data.cumulativeEnergyDischargedKwh / data.cumulativeDischargedAh * 1000.0

        assertTrue("Зарядна напруга $chargeVolts В поза межами пакета", chargeVolts in 300.0..420.0)
        assertTrue("Розрядна напруга $dischargeVolts В поза межами пакета", dischargeVolts in 300.0..420.0)

        // Заряд іде на вищій напрузі, ніж розряд: під струмом заряду напруга росте,
        // під струмом розряду просідає.
        assertTrue("Зарядна напруга мусить бути вищою за розрядну", chargeVolts > dischargeVolts)
    }

    /** Кадр, обрізаний посередині лічильників, не має занулити ті, що вже прочитані. */
    @Test
    fun `a frame cut between counters keeps what it managed to read`() {
        val data = BmsResponseDecoder.decode(frame(size = 44))

        assertEquals(73437.3, data.cumulativeChargedAh, 0.001)
        assertEquals(73260.8, data.cumulativeDischargedAh, 0.001)
        assertEquals(26937.9, data.cumulativeEnergyChargedKwh, 0.001)
        assertEquals(0.0, data.cumulativeEnergyDischargedKwh, 0.001)
    }

    /**
     * Кадр із самих 0xFF правильної довжини. У журналі він з'явився посеред
     * швидкої зарядки: «socD 127.5, I −1043.5 А, U 6455.1 В» — і пішов на екран
     * та в криву нарівні зі справжніми числами. Довжина кадру не доводить нічого.
     */
    @Test
    fun `a frame of all ones is refused`() {
        val data = BmsResponseDecoder.decode(List(48) { 0xFF })

        assertFalse("Кадр зі сміттям не має вважатися даними", data.hasData)
    }

    /** Справжній кадр межі не зачіпає: заряд, напруга і струм лишаються на місці. */
    @Test
    fun `a real frame passes the plausibility check`() {
        val data = BmsResponseDecoder.decode(frame())

        assertTrue(data.hasData)
        assertTrue(data.displaySoc in 0.0..100.0)
        assertTrue(data.batteryVoltage in 200.0..450.0)
    }

    // --- Те, що лежало в кадрі й досі викидалося ------------------------------

    /**
     * Кадр із заповненими новими полями.
     *
     * Зміщення тут навмисно виписані числами, а не константами декодера: тест
     * мусить ловити зсув таблиці, а не повторювати його за тим, що перевіряє.
     */
    private fun richFrame(): List<Int> = MutableList(60) { 0 }.apply {
        this[6] = 0xA0                    // SOC 80 %
        this[7] = 0x0F; this[8] = 0xA0    // 4000 × 0.01 = 40.00 кВт заряду
        this[9] = 0x1D; this[10] = 0x4C   // 7500 × 0.01 = 75.00 кВт розряду
        this[11] = 0xE0                   // біти 7, 6, 5: заряджається, CHAdeMO, Type 1
        this[14] = 0x0E; this[15] = 0x4C  // 366.0 В
        // Вісім модулів: 21..28 °C, з пропуском байта 23.
        this[16] = 21; this[17] = 22; this[18] = 23; this[19] = 24
        this[20] = 25; this[21] = 26; this[22] = 27
        this[23] = 0x7F                   // байт-сусід, який до температур не належить
        this[24] = 28
        this[25] = 0xC8                   // 200 × 0.02 = 4.00 В — найвища комірка
        this[26] = 42                     // її номер
        this[27] = 0xC3                   // 195 × 0.02 = 3.90 В — найнижча
        this[28] = 7                      // її номер
        this[29] = 4                      // вентилятор, крок 4
        this[30] = 120                    // 120 Гц
        this[31] = 0x8C                   // 140 / 10 = 14.0 В на 12-вольтовому
        // Час роботи: 3 600 000 с = 1000 годин.
        this[48] = 0x00; this[49] = 0x36; this[50] = 0xEE; this[51] = 0x80
        this[55] = 0x1B; this[56] = 0x58  // 7000 об/хв
    }

    /**
     * ЖОДНОГО ЗАЙВОГО ЗАПИТУ: усе це лежало в кадрі, який застосунок читає
     * щосекунди, і просто не розбиралося.
     */
    @Test
    fun `the rest of the frame is decoded too`() {
        val bms = BmsResponseDecoder.decode(richFrame())

        assertEquals(40.0, bms.availableChargeKw, 0.001)
        assertEquals(75.0, bms.availableDischargeKw, 0.001)
        assertEquals(4.0, bms.maxCellVolts, 0.001)
        assertEquals(42, bms.maxCellNumber)
        assertEquals(3.9, bms.minCellVolts, 0.001)
        assertEquals(7, bms.minCellNumber)
        assertEquals(4, bms.fanStep)
        assertEquals(120, bms.fanHz)
        assertEquals(14.0, bms.auxVolts, 0.001)
        assertEquals(1000.0, bms.operatingHours, 0.01)
        assertEquals(7000, bms.motorRpm)
    }

    /** Прапорці роз'ємів і зарядки — три різні біти одного байта. */
    @Test
    fun `the charging flags are three separate bits`() {
        val all = BmsResponseDecoder.decode(richFrame())
        assertTrue(all.bmsCharging)
        assertTrue(all.chademoPlugged)
        assertTrue(all.j1772Plugged)

        val onlyChademo = BmsResponseDecoder.decode(
            richFrame().toMutableList().apply { this[11] = 0x40 },
        )
        assertFalse(onlyChademo.bmsCharging)
        assertTrue(onlyChademo.chademoPlugged)
        assertFalse(onlyChademo.j1772Plugged)
    }

    /**
     * ВІСІМ МОДУЛІВ ЛЕЖАТЬ НЕ ПІДРЯД: між сьомим і восьмим стоїть чужий байт.
     * Прочитати їх діапазоном означало б показати сміття замість восьмого модуля.
     */
    @Test
    fun `the eight module temperatures skip the byte between them`() {
        val temps = BmsResponseDecoder.decode(richFrame()).moduleTempsC

        assertEquals(listOf(21.0, 22.0, 23.0, 24.0, 25.0, 26.0, 27.0, 28.0), temps)
    }

    /** Мороз читається зі знаком: модулі бувають і мінусовими. */
    @Test
    fun `module temperatures are signed`() {
        val frozen = richFrame().toMutableList().apply { this[16] = 0xF6 } // -10
        val temps = BmsResponseDecoder.decode(frozen).moduleTempsC

        assertEquals(-10.0, temps.first(), 0.001)
    }

    /**
     * Короткий кадр не має ні позбавляти нас заряду з напругою, ні вигадувати
     * температури: порожній список, а не вісім нулів. Нуль градусів — законна
     * температура, і відрізнити її від «не прочитали» треба на око.
     */
    @Test
    fun `a short frame gives no module temperatures at all`() {
        val short = richFrame().take(20)
        val bms = BmsResponseDecoder.decode(short)

        assertEquals(80.0, bms.displaySoc, 0.001)
        assertTrue("температур бути не мусить", bms.moduleTempsC.isEmpty())
        assertEquals(0, bms.motorRpm)
        assertEquals(0.0, bms.auxVolts, 0.001)
    }
}
