package com.kirianov.kiasoulevplus2.Data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сенс усієї цієї структури — відрізнити три несправності, які з боку виглядають
 * однаково («час в авто злітає»), але лікуються по-різному.
 */
class ClockDriftHistoryTest {

    private fun history(vararg samples: Pair<Long, Int>) =
        samples.fold(ClockDriftHistory()) { acc, (ms, drift) ->
            acc.plus(ClockDriftSample(ms, drift))
        }

    @Test
    fun `a steady clock shows no rate and no jumps`() {
        val result = history(0L to 5, 60_000L to 5, 300_000L to 5)

        assertEquals(0, result.jumpCount)
        assertEquals(0.0, result.rateSecondsPerHour!!, 0.001)
        assertEquals(5, result.driftSeconds)
    }

    /**
     * Кварц магнітоли йде швидше: за 10 хвилин набігло 24 с, тобто 144 с/год.
     * Саме це число й доводить, що GPS ні до чого.
     */
    @Test
    fun `a clock running fast shows up as a rate`() {
        val result = history(0L to 0, 300_000L to 12, 600_000L to 24)

        assertEquals(144.0, result.rateSecondsPerHour!!, 0.001)
        assertEquals(0, result.jumpCount)
    }

    @Test
    fun `a clock running slow gives a negative rate`() {
        val result = history(0L to 0, 600_000L to -24)

        assertEquals(-144.0, result.rateSecondsPerHour!!, 0.001)
    }

    /** Переставлений годинник — це стрибок, а не хід. */
    @Test
    fun `a sudden change is counted as a jump`() {
        val result = history(0L to 0, 60_000L to 0, 70_000L to 3_600)

        assertEquals(1, result.jumpCount)
    }

    /**
     * Найважливіше: швидкість ходу, порахована ЧЕРЕЗ стрибок, — це величина стрибка,
     * а не швидкість. Тому серія мусить почитися заново.
     */
    @Test
    fun `the rate series restarts after a jump`() {
        val result = history(
            0L to 0,
            300_000L to 12,
            310_000L to 3_600,   // стрибок
            610_000L to 3_612,
        )

        assertEquals(1, result.jumpCount)
        // 12 с за 300 с після стрибка -> 144 с/год, а не мільйони від стрибка.
        assertEquals(144.0, result.rateSecondsPerHour!!, 0.001)
    }

    @Test
    fun `a slow drift over a long time is not a jump`() {
        // 20 с за 10 хвилин — поганий кварц, але не переставлений годинник.
        val result = history(0L to 0, 600_000L to 20)

        assertEquals(0, result.jumpCount)
    }

    @Test
    fun `the rate stays unknown until the series is long enough`() {
        val result = history(0L to 0, 30_000L to 5)

        assertNull(result.rateSecondsPerHour)
        assertEquals(5, result.driftSeconds)
    }

    @Test
    fun `the history does not grow without bound`() {
        val result = (0..ClockDriftHistory.MAX_SAMPLES + 500).fold(ClockDriftHistory()) { acc, i ->
            acc.plus(ClockDriftSample(i * 1_000L, 0))
        }

        assertTrue(result.samples.size <= ClockDriftHistory.MAX_SAMPLES)
    }
}
