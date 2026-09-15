package com.kirianov.kiasoulevplus2.tools.autoconnect

import com.kirianov.kiasoulevplus2.Data.AppRequest
import com.kirianov.kiasoulevplus2.Data.ConnectionState
import com.kirianov.kiasoulevplus2.Data.GeneralData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectBlockTest {

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() = GeneralData.reset()

    /** Поки з'єднання немає, блок сам просить його — без жодного натискання. */
    @Test
    fun `it asks for a connection while there is none`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.Connect, GeneralData.state.value.request)
        scope.cancel()
    }

    /**
     * Найважливіше правило: явне «Відключити» поважається. Інакше кнопка
     * відключення виглядала б зламаною — застосунок під'єднувався б назад
     * за секунди.
     */
    @Test
    fun `an explicit disconnect is respected`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()

        // Користувач натиснув «Відключити», блок Bluetooth запит прибрав.
        GeneralData.requestDisconnect()
        scope.testScheduler.runCurrent()
        GeneralData.clearRequest()
        scope.testScheduler.runCurrent()

        // Скільби не чекали, запит на підключення більше не з'явиться.
        scope.testScheduler.advanceTimeBy(10 * 60_000L)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.None, GeneralData.state.value.request)
        scope.cancel()
    }

    /** Натискання «Підключити» повертає автопідключення до роботи. */
    @Test
    fun `pressing connect turns auto-connect back on`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()

        GeneralData.requestDisconnect()
        scope.testScheduler.runCurrent()
        GeneralData.clearRequest()
        scope.testScheduler.runCurrent()

        GeneralData.requestConnect()
        scope.testScheduler.runCurrent()
        GeneralData.clearRequest()
        scope.testScheduler.advanceTimeBy(3 * 60_000L)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.Connect, GeneralData.state.value.request)
        scope.cancel()
    }

    /** Поки підключені, просити підключення нема сенсу. */
    @Test
    fun `nothing is asked while already connected`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        GeneralData.updateConnection(ConnectionState.Connected, "")
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()
        GeneralData.clearRequest()

        scope.testScheduler.advanceTimeBy(10 * 60_000L)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.None, GeneralData.state.value.request)
        scope.cancel()
    }

    /** Друга спроба поверх незакінченої першої лише перебила б її. */
    @Test
    fun `nothing is asked while a connection is being made`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        GeneralData.updateConnection(ConnectionState.Connecting, "")
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()
        GeneralData.clearRequest()

        scope.testScheduler.advanceTimeBy(60_000L)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.None, GeneralData.state.value.request)
        scope.cancel()
    }

    /** Після втрати зв'язку блок пробує знову, а не здається назавжди. */
    @Test
    fun `a dropped connection is retried`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        GeneralData.updateConnection(ConnectionState.Connected, "")
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()
        GeneralData.clearRequest()

        GeneralData.updateConnection(ConnectionState.Disconnected, "обрив")
        scope.testScheduler.advanceTimeBy(AutoConnectPolicy.IDLE_CHECK_MS + 1_000L)
        scope.testScheduler.runCurrent()

        assertTrue(GeneralData.state.value.request == AppRequest.Connect)
        scope.cancel()
    }

    /** Перемикач вимкнено — блок не просить нічого. */
    @Test
    fun `the switch turns it off`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        GeneralData.setAutoConnect(false)
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()

        scope.testScheduler.advanceTimeBy(10 * 60_000L)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.None, GeneralData.state.value.request)
        scope.cancel()
    }

    /** Увімкнули назад — блок знову береться до справи. */
    @Test
    fun `turning the switch back on resumes it`() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        GeneralData.setAutoConnect(false)
        AutoConnectBlock().start(scope)
        scope.testScheduler.runCurrent()

        GeneralData.setAutoConnect(true)
        scope.testScheduler.advanceTimeBy(2 * AutoConnectPolicy.IDLE_CHECK_MS)
        scope.testScheduler.runCurrent()

        assertEquals(AppRequest.Connect, GeneralData.state.value.request)
        scope.cancel()
    }
}
