package com.kirianov.kiasoulevplus2.tools.probe

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.ProbeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProbeBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        GeneralData.reset()
        ProbeBlock().start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    @Test
    fun `a raw reply becomes a parsed result`() {
        GeneralData.publishProbeFrames("7C6", "22 B0 02", "62 B0 02 00 01 E2 40\r>")

        val result = GeneralData.state.value.probe.results.first()
        assertEquals("7C6", result.header)
        assertEquals("22 B0 02", result.command)
        assertEquals(listOf(0x62, 0xB0, 0x02, 0x00, 0x01, 0xE2, 0x40), result.bytes)
        assertNull(result.error)
    }

    /** Відмова блока — теж результат: її треба показати, а не проковтнути. */
    @Test
    fun `an error is carried through to the result`() {
        GeneralData.publishProbeFrames("7C6", "22 B0 02", "", error = "немає відповіді")

        val result = GeneralData.state.value.probe.results.first()
        assertEquals("немає відповіді", result.error)
        assertTrue(result.bytes.isEmpty())
    }

    @Test
    fun `newest result comes first and the list is capped`() {
        repeat(ProbeState.MAX_RESULTS + 5) { index ->
            GeneralData.publishProbeFrames("7C6", "22 B0 0$index", "62 B0 0$index")
        }

        val results = GeneralData.state.value.probe.results
        assertEquals(ProbeState.MAX_RESULTS, results.size)
        assertEquals("22 B0 0${ProbeState.MAX_RESULTS + 4}", results.first().command)
    }

    @Test
    fun `a target set before probing is matched in the reply`() {
        GeneralData.setProbeTarget(188_443)

        GeneralData.publishProbeFrames("7C6", "22 B0 02", "62 B0 02 00 00 02 E0 1B")

        val match = GeneralData.state.value.probe.results.first().matches.first()
        assertEquals(5, match.index)
        assertEquals(3, match.width)
    }

    /**
     * Число вводять уже після кількох запитів, тому збіги мають перерахуватися
     * для збережених відповідей — інакше довелося б перепитувати авто.
     */
    @Test
    fun `setting a target rematches replies already received`() {
        GeneralData.publishProbeFrames("7C6", "22 B0 02", "62 B0 02 00 00 02 E0 1B")
        assertTrue(GeneralData.state.value.probe.results.first().matches.isEmpty())

        GeneralData.setProbeTarget(188_443)

        val match = GeneralData.state.value.probe.results.first().matches.first()
        assertEquals(5, match.index)
        assertEquals(3, match.width)
    }

    @Test
    fun `clearing the target drops the matches`() {
        GeneralData.setProbeTarget(188_443)
        GeneralData.publishProbeFrames("7C6", "22 B0 02", "62 B0 02 00 00 02 E0 1B")

        GeneralData.setProbeTarget(null)

        assertTrue(GeneralData.state.value.probe.results.first().matches.isEmpty())
    }

    /** Повторний однаковий запит має дати новий результат, а не бути пропущеним. */
    @Test
    fun `repeating the same probe records it again`() {
        GeneralData.publishProbeFrames("7E4", "21 01", "61 01 AA")
        GeneralData.publishProbeFrames("7E4", "21 01", "61 01 AA")

        assertEquals(2, GeneralData.state.value.probe.results.size)
    }
}
