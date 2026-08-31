package com.kirianov.kiasoulevplus2

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стежить за головним правилом побудови додатка: блоки не знають один про одного,
 * а обмінюються даними через GeneralData.
 *
 * Тест читає самі вихідні файли, тому будь-який новий імпорт між блоками впаде тут,
 * а не через пів року в заплутаному коді.
 */
class ArchitectureTest {

    /** Блок = тека верхнього рівня з кодом однієї відповідальності. */
    private val blocks = listOf(
        "Data",
        "Interface",
        "services.bluetooth",
        "services.AndroidAuto",
        "tools.battery",
        "tools.calculations",
        "tools.storage",
        "tools.probe",
        "tools.vehicle",
        "tools.ml",
        "tools.format",
        "tools.frames",
    )

    /** Хаб: сюди дозволено звертатися будь-кому, це і є канал обміну. */
    private val hub = "Data"

    /**
     * Бібліотеки чистих функцій без стану: форматування чисел і розбір байтів кадру.
     * Ними користуються всі блоки — це спільна бібліотека, а не канал обміну даними.
     */
    private val sharedUtilities = listOf("tools.format", "tools.frames")

    /**
     * Файли в корені пакета: App (піднімає блоки на весь час життя процесу),
     * MainActivity та місце, де перелічені всі блоки.
     * Їм за визначенням видно всіх — на те вони й точка збірки.
     */
    private val compositionRoot = listOf("App.kt", "MainActivity.kt", "AppBlocks.kt")

    @Test
    fun `no block imports another block`() {
        val violations = mutableListOf<String>()

        sourceFiles().forEach { file ->
            if (file.name in compositionRoot) return@forEach
            val owner = blockOf(file) ?: return@forEach

            internalImports(file).forEach { imported ->
                val target = blocks.firstOrNull { imported == it || imported.startsWith("$it.") }
                    ?: return@forEach

                val allowed = target == owner || target == hub || target in sharedUtilities
                if (!allowed) {
                    violations += "${file.name} (блок $owner) імпортує $target"
                }
            }
        }

        assertTrue(
            "Блоки мають спілкуватися через GeneralData, а не напряму:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** Хаб мусить лишатися пасивним: жодних звернень до блоків із нього. */
    @Test
    fun `the hub depends on nothing but itself`() {
        val leaks = sourceFiles()
            .filter { blockOf(it) == hub }
            .flatMap { file -> internalImports(file).map { file.name to it } }
            .filterNot { (_, imported) -> imported.startsWith(hub) }

        assertTrue("GeneralData не має залежати від блоків: $leaks", leaks.isEmpty())
    }

    @Test
    fun `every block is represented in the sources`() {
        val present = sourceFiles().mapNotNull { blockOf(it) }.toSet()
        assertTrue("Не знайдено коду блоків: ${blocks - present}", present.containsAll(blocks))
    }

    private fun blockOf(file: File): String? {
        val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
        return blocks
            .filter { relative.startsWith(it.replace('.', '/') + "/") }
            // "services.bluetooth" має вигравати в "services", якби той колись з'явився.
            .maxByOrNull { it.length }
    }

    private fun internalImports(file: File): List<String> =
        file.readLines()
            .mapNotNull { line -> IMPORT_PREFIXES.firstNotNullOfOrNull { line.trim().substringAfter(it, "").ifEmpty { null } } }
            .map { it.substringBeforeLast('.') }

    private fun sourceFiles(): List<File> = sourceRoot.walkTopDown().filter { it.extension == "kt" }.toList()

    private val sourceRoot: File by lazy {
        val candidates = listOf(
            File("src/main/kotlin/com/kirianov/kiasoulevplus2"),
            File("app/src/main/kotlin/com/kirianov/kiasoulevplus2"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("Не знайдено корінь вихідних файлів; спробовано: ${candidates.map { it.absolutePath }}")
    }

    private companion object {
        val IMPORT_PREFIXES = listOf("import com.kirianov.kiasoulevplus2.")
    }
}
