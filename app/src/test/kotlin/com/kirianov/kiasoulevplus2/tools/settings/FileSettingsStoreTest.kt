package com.kirianov.kiasoulevplus2.tools.settings

import com.kirianov.kiasoulevplus2.Data.Settings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileSettingsStoreTest {

    private fun directory(): File =
        File(System.getProperty("java.io.tmpdir"), "settings-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `a saved choice reads back`() {
        val store = FileSettingsStore(directory())

        store.save(Settings(autoConnect = false))

        assertEquals(Settings(autoConnect = false), store.load())
    }

    @Test
    fun `nothing saved reads as nothing`() {
        assertNull(FileSettingsStore(directory()).load())
    }

    @Test
    fun `a damaged file reads as nothing instead of throwing`() {
        val dir = directory()
        File(dir, "settings.json").writeText("не json")

        assertNull(FileSettingsStore(dir).load())
    }

    @Test
    fun `an empty file reads as nothing`() {
        val dir = directory()
        File(dir, "settings.json").writeText("")

        assertNull(FileSettingsStore(dir).load())
    }
}
