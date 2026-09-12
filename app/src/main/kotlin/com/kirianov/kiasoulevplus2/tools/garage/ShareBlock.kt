// ====================================================================================
// БЛОК ОБМІНУ ДАНИМИ АВТО (ShareBlock)
//
// Пакує все, що застосунок знає про одне авто, в один файл — і вміє прийняти такий
// файл із іншого телефона, ЗЛИВШИ його зі своїм.
//
// НАВІЩО. Одна машина, два водії, два телефони. Кожен бачить свою половину поїздок,
// і жодна з половин сама по собі не дає повної картини. Сервера в застосунку немає
// й поки не планується — а от файл переслати вміє будь-хто.
//
// ЗЛИТИ, А НЕ ЗАМІНИТИ — це головне. Узяти чуже замість свого означало б викинути
// половину науки; узяти своє замість чужого — не отримати нічого. Тому кожне
// сховище зливає СВОЄ по своєму правилу: журнал поїздок — об'єднанням без повторів,
// суми кривої — додаванням, облік зарядок — беручи свіжіший.
//
// ЧОМУ ПАКУНКИ ПІДПИСАНІ. Суми кривої додаються, а отже той самий пакунок, прийнятий
// двічі, порахував би ті самі проходи двічі й тихо зіпсував криву. Тому в пакунка є
// позначка, а вже прийняті записані поруч із даними авто. Це знання блока, а не
// сховищ: їм про пакунки знати не треба.
//
// Про самі сховища блок знає рівно стільки, скільки описано в CarDataStore, — уміння
// віддати й прийняти. Що там усередині, він не бачить.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.garage

import com.kirianov.kiasoulevplus2.Data.GeneralData
import com.kirianov.kiasoulevplus2.Data.ShareRequest
import com.kirianov.kiasoulevplus2.tools.paths.CarDataStore
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

class ShareBlock(
    private val root: File,
    private val cache: File,
    private val stores: List<CarDataStore>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun start(scope: CoroutineScope) {
        GeneralData.state
            .map { it.garage.share.request }
            .distinctUntilChanged()
            .onEach { request ->
                when (request) {
                    ShareRequest.None -> return@onEach
                    ShareRequest.Export -> withContext(ioDispatcher) { export() }
                    ShareRequest.Import -> withContext(ioDispatcher) { import() }
                }
                GeneralData.updateShare { it.copy(request = ShareRequest.None) }
            }
            .launchIn(scope)
    }

    private fun export() {
        val vin = GeneralData.state.value.garage.activeVin
        if (vin.isEmpty()) {
            GeneralData.updateShare { it.copy(note = "Авто ще не визначено — ділитися нічим") }
            return
        }

        val staging = File(cache, STAGING_OUT).apply { deleteRecursively(); mkdirs() }
        stores.forEach { it.exportTo(staging) }
        File(staging, STAMP_FILE).writeText(stampFor(vin))

        val bundle = File(cache, "$vin$BUNDLE_SUFFIX")
        val packed = runCatching { zip(staging, bundle) }.isSuccess
        GeneralData.updateShare {
            if (packed) {
                it.copy(exportedPath = bundle.absolutePath, note = "")
            } else {
                it.copy(note = "Не вдалося зібрати файл")
            }
        }
    }

    private fun import() {
        val share = GeneralData.state.value.garage.share
        val source = File(share.importPath)
        if (!source.isFile) {
            GeneralData.updateShare { it.copy(note = "Файл не знайдено", importPath = "") }
            return
        }

        val staging = File(cache, STAGING_IN).apply { deleteRecursively(); mkdirs() }
        if (runCatching { unzip(source, staging) }.isFailure) {
            GeneralData.updateShare { it.copy(note = "Файл не читається", importPath = "") }
            return
        }

        val stamp = runCatching { File(staging, STAMP_FILE).readText().trim() }.getOrDefault("")
        if (stamp.isEmpty()) {
            GeneralData.updateShare { it.copy(note = "Це не файл обміну", importPath = "") }
            return
        }

        val vin = GeneralData.state.value.garage.activeVin
        if (vin.isEmpty()) {
            GeneralData.updateShare { it.copy(note = "Спершу оберіть авто", importPath = "") }
            return
        }
        if (alreadyApplied(vin, stamp)) {
            GeneralData.updateShare {
                it.copy(note = "Цей файл уже приймали — вдруге не додаємо", importPath = "")
            }
            return
        }

        val changes = stores.mapNotNull { it.mergeFrom(staging).takeIf(String::isNotEmpty) }
        rememberApplied(vin, stamp)

        GeneralData.updateShare {
            it.copy(
                importPath = "",
                note = if (changes.isEmpty()) {
                    "Нового в цьому файлі не було"
                } else {
                    "Прийнято: " + changes.joinToString("; ")
                },
            )
        }
    }

    /**
     * Позначка пакунка: чиє авто, коли зібрано.
     *
     * Часу з точністю до мілісекунди досить: двічі зібрати пакунок в одну й ту саму
     * мілісекунду неможливо навіть навмисно, а зібраний удруге пакунок з тими самими
     * даними — це вже новий пакунок, і прийняти його вдруге справді не можна.
     */
    private fun stampFor(vin: String): String = "$vin ${nowMs()}"

    private fun appliedFile(vin: String) = File(CarPaths.directoryFor(root, vin), APPLIED_FILE)

    private fun alreadyApplied(vin: String, stamp: String): Boolean =
        runCatching { appliedFile(vin).readLines().any { it.trim() == stamp } }.getOrDefault(false)

    private fun rememberApplied(vin: String, stamp: String) {
        runCatching {
            val file = appliedFile(vin)
            file.parentFile?.mkdirs()
            file.appendText(stamp + "\n")
        }
    }

    private fun zip(directory: File, target: File) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            directory.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                out.putNextEntry(ZipEntry(file.name))
                file.inputStream().buffered().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /**
     * Розпакування з перевіркою імені кожного запису.
     *
     * Ім'я всередині архіву — це рядок із чужого файлу, і рядок «../../щось» вивів би
     * запис за межі теки застосунку. Тому беремо лише голе ім'я файлу й нічого
     * більше: наш пакунок вкладених тек і не має.
     */
    private fun unzip(source: File, target: File) {
        target.mkdirs()
        ZipInputStream(source.inputStream().buffered()).use { input ->
            var entry: ZipEntry? = input.nextEntry
            while (entry != null) {
                val name = File(entry.name).name
                if (!entry.isDirectory && name.isNotEmpty()) {
                    File(target, name).outputStream().buffered().use { input.copyTo(it) }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    private companion object {
        const val STAGING_OUT = "share-out"
        const val STAGING_IN = "share-in"
        const val STAMP_FILE = "bundle.txt"
        const val APPLIED_FILE = "applied-bundles.txt"
        const val BUNDLE_SUFFIX = ".kiasoul"
    }
}
