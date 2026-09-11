package com.kirianov.kiasoulevplus2.tools.settings

import com.kirianov.kiasoulevplus2.Data.CellPalette
import com.kirianov.kiasoulevplus2.Data.CellPalettes
import com.kirianov.kiasoulevplus2.Data.CellValueMode
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

    /**
     * Пристроїв для пробудження буває кілька: магнітола з'єднується то одним
     * профілем, то іншим, і адреси в них різні.
     */
    @Test
    fun `several wake devices read back`() {
        val store = FileSettingsStore(directory())
        val devices = setOf("68:84:70:11:A5:B9", "00:1D:A5:00:00:FF")

        store.save(Settings(wakeOnDeviceAddresses = devices))

        assertEquals(devices, store.load()?.wakeOnDeviceAddresses)
    }

    /**
     * ФАЙЛ ВІД ПОПЕРЕДНЬОЇ ЗБІРКИ МУСИТЬ ЧИТАТИСЯ, і це не формальність: у ньому
     * лежить одна адреса під старим ключем. Якби вона загубилася, застосунок після
     * оновлення мовчки перестав би прокидатися — рівно та поломка, яку ми тут і
     * лікуємо.
     */
    @Test
    fun `a single device from an older file still wakes the app`() {
        val dir = directory()
        File(dir, "settings.json").writeText(
            """{"autoConnect":true,"journal":true,"wakeOnDevice":"68:84:70:11:A5:B9"}""",
        )

        val settings = FileSettingsStore(dir).load()

        assertEquals(setOf("68:84:70:11:A5:B9"), settings?.wakeOnDeviceAddresses)
        assertEquals(true, settings?.wakesOn("68:84:70:11:a5:b9"))
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

    /**
     * Пороги фарбування комірок теж мусять переживати перезапуск: п'ять режимів по
     * дві межі, і набирати їх заново після кожного оновлення — та сама втрата, що
     * й із рештою налаштувань.
     */
    @Test
    fun `cell colour thresholds survive a restart`() {
        val store = FileSettingsStore(directory())
        val palettes = CellPalettes()
            .with(CellValueMode.Rest, CellPalette(warnAt = 7.0, alertAt = 14.0, badAt = 21.0))
            .with(
                CellValueMode.UnderLoad,
                CellPalette(warnAt = 80.0, alertAt = 110.0, badAt = 150.0),
            )

        store.save(Settings(cellPalettes = palettes))

        val loaded = store.load()!!.cellPalettes
        assertEquals(
            CellPalette(warnAt = 7.0, alertAt = 14.0, badAt = 21.0),
            loaded.of(CellValueMode.Rest),
        )
        assertEquals(
            CellPalette(warnAt = 80.0, alertAt = 110.0, badAt = 150.0),
            loaded.of(CellValueMode.UnderLoad),
        )
        // Незмінені режими лишаються типовими, а не нульовими.
        assertEquals(CellPalettes().resistance, loaded.resistance)
    }

    /**
     * Старий файл без порогів читається з типовими, а не з нулями: нуль пофарбував
     * би весь пакет червоним на першому ж запуску після оновлення.
     */
    @Test
    fun `an old file without thresholds falls back to the defaults`() {
        val directory = directory()
        File(directory, "settings.json")
            .writeText("""{"autoConnect":true,"journal":true,"wakeOnDevice":""}""")

        assertEquals(CellPalettes(), FileSettingsStore(directory).load()!!.cellPalettes)
    }
}
