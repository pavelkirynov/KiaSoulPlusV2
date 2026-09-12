package com.kirianov.kiasoulevplus2.tools.frames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дві причини відмови з усього списку означають не «ні», а «ще раз». Плутати їх із
 * рештою дорого: перше ж опитування живої машини оголосило два справних блоки
 * відмовниками саме через це.
 */
class NegativeResponseTest {

    /** «Зайнятий, повтори» — блок є і чує нас. */
    @Test
    fun `busy repeat request is worth another try`() {
        assertTrue(NegativeResponse.busy("7F 19 21", "1902FF"))
    }

    /** «Прийняв, готую відповідь» — теж не відмова. */
    @Test
    fun `response pending is worth another try`() {
        assertTrue(NegativeResponse.busy("7F 19 78", "1902FF"))
    }

    /** «Не знаю такої послуги» повторювати марно — відповідь буде та сама. */
    @Test
    fun `a plain refusal is not retried`() {
        assertFalse(NegativeResponse.busy("7F 19 11", "1902FF"))
        assertFalse(NegativeResponse.busy("7F 19 31", "1902FF"))
    }

    /** Нормальна відповідь — тим паче не привід перепитувати. */
    @Test
    fun `a positive answer is not retried`() {
        assertFalse(NegativeResponse.busy("59 02 FF 01 62 00 08", "1902FF"))
        assertFalse(NegativeResponse.busy("", "1902FF"))
    }

    /**
     * Відмова на ЧУЖУ службу — не наша.
     *
     * У буфері адаптера цілком може лежати хвіст попереднього обміну; прийняти
     * його за свою відповідь означає перепитувати блок, який нам уже відповів.
     */
    @Test
    fun `a refusal for another service is not ours`() {
        assertFalse(NegativeResponse.busy("7F 21 78", "1902FF"))
        assertNull(NegativeResponse.codeOf("7F 21 78", 0x19))
    }

    /** Служба береться з першого байта самого запиту, а не задається окремо. */
    @Test
    fun `the service comes from the request itself`() {
        assertEquals(0x19, NegativeResponse.serviceOf("1902FF"))
        assertEquals(0x18, NegativeResponse.serviceOf("1802FF00"))
    }

    /** KWP відмовляє по-своєму — на службу 18. */
    @Test
    fun `a kwp refusal is matched by its own service`() {
        assertTrue(NegativeResponse.busy("7F 18 78", "1802FF00"))
        assertFalse(NegativeResponse.busy("7F 18 78", "1902FF"))
    }

    /**
     * Чи блок відповів по суті — по цьому вирішується, чи питати його другою
     * мовою. Помилитися тут означає або зайвий запит на кожен блок, або втрачену
     * відповідь.
     */
    @Test
    fun `only a real answer counts as answered`() {
        assertTrue(NegativeResponse.answeredWithData("59 02 FF 01 62 00 08", "1902FF"))
        assertTrue(NegativeResponse.answeredWithData("58 00", "1802FF00"))
        assertFalse("Відмова — не відповідь по суті", NegativeResponse.answeredWithData("7F 19 11", "1902FF"))
        assertFalse("Мовчання — тим паче", NegativeResponse.answeredWithData("NO DATA", "1902FF"))
    }
}
