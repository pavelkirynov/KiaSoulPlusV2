package com.kirianov.kiasoulevplus2.tools.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSenseTest {

    private val sense = ChargeSense()

    /** Дві хвилини приймання — це ще може бути що завгодно. */
    @Test
    fun `a short spell of current is not yet charging`() {
        assertFalse(sense.observe(20.0, moving = false, nowMs = 0))
        assertFalse(sense.observe(20.0, moving = false, nowMs = 120_000))
    }

    @Test
    fun `steady current on a parked car becomes charging`() {
        sense.observe(20.0, moving = false, nowMs = 0)

        assertTrue(sense.observe(20.0, moving = false, nowMs = 180_000))
    }

    /** Перерва в прийманні скидає відлік: три хвилини мають бути безперервними. */
    @Test
    fun `a break in the current starts the count over`() {
        sense.observe(20.0, moving = false, nowMs = 0)
        sense.observe(0.0, moving = false, nowMs = 100_000)

        assertFalse(sense.observe(20.0, moving = false, nowMs = 180_000))
        assertTrue(sense.observe(20.0, moving = false, nowMs = 361_000))
    }

    @Test
    fun `movement cancels charging at once`() {
        sense.observe(20.0, moving = false, nowMs = 0)
        assertTrue(sense.observe(20.0, moving = false, nowMs = 180_000))

        assertFalse(sense.observe(20.0, moving = true, nowMs = 181_000))
    }

    /**
     * Станція вміє пригальмовувати, а наприкінці зарядки струм і зовсім падає.
     * Рвати сесію на кожній паузі не варто.
     */
    @Test
    fun `a pause in the station does not end the charge at once`() {
        sense.observe(20.0, moving = false, nowMs = 0)
        sense.observe(20.0, moving = false, nowMs = 180_000)

        assertTrue(sense.observe(0.0, moving = false, nowMs = 200_000))
        assertTrue(sense.observe(0.0, moving = false, nowMs = 400_000))
        assertFalse(sense.observe(0.0, moving = false, nowMs = 500_000))
    }

    /** Розрив зв'язку: усе, що ми знали про струм, застаріло. */
    @Test
    fun `forgetting drops the flag`() {
        sense.observe(20.0, moving = false, nowMs = 0)
        assertTrue(sense.observe(20.0, moving = false, nowMs = 180_000))

        sense.forget()

        assertFalse(sense.observe(20.0, moving = false, nowMs = 181_000))
    }

    /** Розряд — це не зарядка, скільки б він не тривав. */
    @Test
    fun `current out of the pack is never charging`() {
        sense.observe(-40.0, moving = false, nowMs = 0)

        assertFalse(sense.observe(-40.0, moving = false, nowMs = 600_000))
    }
}
