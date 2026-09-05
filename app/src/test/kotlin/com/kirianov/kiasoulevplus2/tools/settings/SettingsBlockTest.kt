package com.kirianov.kiasoulevplus2.tools.settings

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsBlockTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private class MemoryStore(var saved: Settings? = null) : SettingsStore {
        override fun load(): Settings? = saved
        override fun save(settings: Settings) {
            saved = settings
        }
    }

    @Before
    fun setUp() = GeneralData.reset()

    @After
    fun tearDown() {
        scope.cancel()
        GeneralData.reset()
    }

    /** Типово увімкнено: інакше моделі вчаться лише коли хтось згадав про кнопку. */
    @Test
    fun `auto-connect is on when nothing was saved`() {
        SettingsBlock(MemoryStore()).start(scope)

        assertTrue(GeneralData.state.value.settings.autoConnect)
    }

    @Test
    fun `a saved choice is restored`() {
        SettingsBlock(MemoryStore(Settings(autoConnect = false))).start(scope)

        assertFalse(GeneralData.state.value.settings.autoConnect)
    }

    @Test
    fun `flipping the switch is written to the store`() {
        val store = MemoryStore()
        SettingsBlock(store).start(scope)

        GeneralData.setAutoConnect(false)

        // loaded не зберігається — це познака «прочитано з диска», яка живе лише в
        // пам'яті. Порівнюємо з нею, бо блок ставить її при завантаженні.
        assertEquals(Settings(autoConnect = false, loaded = true), store.saved)
    }

    /** Щойно завантажене писати назад немає сенсу. */
    @Test
    fun `loading does not write back`() {
        val store = MemoryStore()
        SettingsBlock(store).start(scope)

        assertNull(store.saved)
    }
}
