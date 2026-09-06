package com.kirianov.kiasoulevplus2.car.dtc

import com.kirianov.kiasoulevplus2.Data.Ecu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Код помилки запакований незвично: старші два біти першого байта — літера, наступні
 * два — перша цифра, решта звичайні півбайти. Тобто «01 62» це P0162, а не 0162.
 * Помилитися тут легко, а на екрані буде впевнено намальоване чуже число.
 */
class FaultDecoderTest {

    private val bms = Ecu("7E4", "Батарея")

    /** Одна помилка з активним станом. */
    @Test
    fun `a single confirmed fault is decoded`() {
        val result = FaultDecoder.decode(bms, "59 02 FF 01 62 00 09")

        assertTrue(result.answered)
        val fault = result.faults.single()
        assertEquals("P0162-00", fault.code)
        assertTrue("Біт 0 означає, що перевірка провалюється зараз", fault.failingNow)
        assertTrue("Біт 3 — підтверджена", fault.confirmed)
    }

    /** Літера береться зі старших двох бітів: 00 — P, 01 — C, 10 — B, 11 — U. */
    @Test
    fun `the letter comes from the top two bits`() {
        val codes = FaultDecoder.decode(
            bms,
            "59 02 FF 01 62 00 08 41 62 00 08 81 62 00 08 C1 62 00 08",
        ).faults.map { it.code }

        assertEquals(listOf("P0162-00", "C0162-00", "B0162-00", "U0162-00"), codes)
    }

    /** Друга цифра коду теж із першого байта — і саме на ній найлегше помилитися. */
    @Test
    fun `the first digit comes from the next two bits`() {
        val codes = FaultDecoder.decode(bms, "59 02 FF 11 62 00 08 21 62 00 08 31 62 00 08")
            .faults.map { it.code }

        assertEquals(listOf("P1162-00", "P2162-00", "P3162-00"), codes)
    }

    /** Третій байт — уточнення типу відмови, воно йде через дефіс. */
    @Test
    fun `the failure type is kept`() {
        val fault = FaultDecoder.decode(bms, "59 02 FF C1 62 87 08").faults.single()

        assertEquals("U0162-87", fault.code)
    }

    /** Порожня відповідь блока: помилок немає, і це не те саме, що мовчання. */
    @Test
    fun `an empty list means no faults, not silence`() {
        val result = FaultDecoder.decode(bms, "59 02 FF")

        assertTrue("Блок озвався", result.answered)
        assertTrue(result.faults.isEmpty())
        assertEquals("помилок немає", result.note)
    }

    /** Нулі в хвості кадру — доповнення, а не помилка з кодом P0000. */
    @Test
    fun `zero padding at the end is not a fault`() {
        val result = FaultDecoder.decode(bms, "59 02 FF 01 62 00 08 00 00 00 00")

        assertEquals(1, result.faults.size)
    }

    /** Мовчання блока — його в цій машині просто немає. */
    @Test
    fun `silence is reported as no answer`() {
        val result = FaultDecoder.decode(bms, "NO DATA")

        assertFalse(result.answered)
        assertEquals("не відповів", result.note)
    }

    /**
     * Негативна відповідь — не «помилок немає». У машині 2015 року половина блоків
     * чесно каже «такої послуги не знаю», і плутати це з чистою пам'яттю не можна.
     */
    @Test
    fun `a refusal is not the same as a clean memory`() {
        val result = FaultDecoder.decode(bms, "7F 19 31")

        assertTrue("Блок озвався, хай і відмовою", result.answered)
        assertTrue(result.faults.isEmpty())
        assertEquals("не підтримує запит помилок", result.note)
    }

    /** Незнайому причину відмови показуємо числом, а не вигаданим перекладом. */
    @Test
    fun `an unknown refusal keeps its number`() {
        val result = FaultDecoder.decode(bms, "7F 19 A5")

        assertTrue(result.note.contains("A5"))
    }

    /** Багаторамкова відповідь із префіксами кадрів розбирається так само. */
    @Test
    fun `a multi frame answer is decoded too`() {
        val result = FaultDecoder.decode(
            bms,
            "0: 59 02 FF 01 62 00\n1: 08 41 62 00 08 C0 43",
        )

        assertEquals(listOf("P0162-00", "C0162-00"), result.faults.map { it.code })
    }

    /** Стан читається побітово, і лампа на панелі — окремий біт. */
    @Test
    fun `the status bits are read one by one`() {
        val faults = FaultDecoder.decode(bms, "59 02 FF 01 62 00 89 01 63 00 08").faults

        assertTrue("Біт 7 — блок просить лампу", faults[0].warningLight)
        assertFalse(faults[1].warningLight)
        assertEquals("активна зараз", faults[0].stateText)
        assertEquals("підтверджена, зараз не повторюється", faults[1].stateText)
    }
}
