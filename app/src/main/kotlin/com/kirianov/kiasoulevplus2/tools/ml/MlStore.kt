// ====================================================================================
// СХОВИЩЕ МОДЕЛІ (MlStore)
//
// Два файли в теці застосунку:
//
//   ml_model.json     — накопичені статистики. Пишеться через тимчасовий файл із
//                       перейменуванням: обірваний на середині запис не має
//                       перетворити модель на сміття.
//   ml_segments.jsonl — журнал відрізків, дописується рядками. З нього модель можна
//                       зібрати наново — після зміни набору ознак або на вимогу.
//
// Інтерфейс відділений від реалізації так само, як у ManualCellStore: блок прогнозу
// перевіряється тестами без Android.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import com.kirianov.kiasoulevplus2.tools.paths.CarDataStore
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths
import java.io.File
import java.io.IOException

interface MlStore {
    /**
     * Перевести сховище на дані конкретного авто.
     *
     * Порожня реалізація навмисно: у пам'яті, де сховище живе одним об'єктом на
     * тест, переселяти нічого. Значення має лише файлова реалізація.
     */
    fun useCar(vin: String) {}

    fun loadModel(): ModelSnapshot?
    fun saveModel(snapshot: ModelSnapshot)
    fun appendSegment(segment: MlSegment)
    fun readSegments(): List<MlSegment>
    fun clear()
}

class FileMlStore(private val root: File) : MlStore, CarDataStore {

    /** Тека поточного авто. Порожня — корінь: так лежала спадщина до гаража. */
    @Volatile
    private var carDirectory: File = root

    private val directory: File get() = carDirectory

    /**
     * Перевести сховище на дані конкретного авто.
     *
     * Дані кожного авто живуть у власній підтеці, і перемкнути її можна на ходу:
     * VIN стає відомим лише після підключення, тобто пізніше, ніж застосунок
     * піднявся.
     *
     * ПЕРЕЇЗД СПАДЩИНИ РОБИТЬСЯ ТУТ, і це навмисно. До появи гаража всі дані
     * лежали просто в корені теки застосунку — у того, хто оновився, там
     * і лежить уся його історія. Хто саме має її переселити? Той, хто знає імена
     * своїх файлів, тобто це сховище; будь-хто інший мусив би знати чужі імена.
     * Переїзд відбувається рівно один раз: далі файл на новому місці вже є.
     */
    override fun useCar(vin: String) {
        val target = CarPaths.directoryFor(root, vin)
        adoptLegacy(target)
        carDirectory = target
    }

    private fun adoptLegacy(target: File) {
        runCatching {
            target.mkdirs()
            for (name in OWN_FILES) {
                val legacy = File(root, name)
                val moved = File(target, name)
                if (legacy.isFile && !moved.exists()) legacy.renameTo(moved)
            }
        }
    }

    private val modelFile get() = File(directory, MODEL_FILE)
    private val logFile get() = File(directory, LOG_FILE)

    /**
     * Назовні йде ЛИШЕ журнал відрізків, без знімка моделі.
     *
     * Журнал — це сирі дані: що, коли й на чому проїхали. Знімок — накопичені
     * статистики, зібрані з цього журналу на конкретній машині з конкретною
     * ємністю. Віддавати знімок означало б віддати чужу впевненість разом із чужим
     * апріорі, а зібрати те саме з журналу приймальна сторона вміє сама — і зробить
     * це вже зі СВОЄЮ ємністю.
     */
    override fun exportTo(directory: File) {
        runCatching {
            directory.mkdirs()
            if (logFile.isFile) logFile.copyTo(File(directory, LOG_FILE), overwrite = true)
        }
    }

    /**
     * ГОЛОВНЕ ЗЛИТТЯ З УСІХ. Тут лежить уся наука про поїздки, і саме заради нього
     * «поділитися» й затівалося.
     *
     * Об'єднання без повторів за часом початку відрізка. Час тут придатний за ключ
     * тому, що відрізок починається від тику лічильника пробігу — дві поїздки не
     * можуть початися в ту саму мілісекунду, а той самий відрізок, що приїхав двома
     * різними шляхами, має однаковий час і зливається в один.
     *
     * Знімок моделі після злиття видаляється навмисно: він зібраний зі старого
     * журналу й новим відрізкам уже не відповідає. Модель побачить, що знімка немає,
     * і чесно збереться з журналу наново — з усього, і свого, і прийнятого.
     */
    override fun mergeFrom(directory: File): String {
        val incoming = runCatching {
            File(directory, LOG_FILE).takeIf { it.isFile }?.readLines().orEmpty()
        }.getOrDefault(emptyList())
        if (incoming.isEmpty()) return ""

        val mine = runCatching { logFile.takeIf { it.isFile }?.readLines().orEmpty() }
            .getOrDefault(emptyList())

        val byTime = linkedMapOf<Long, String>()
        (mine + incoming).forEach { line ->
            val segment = MlCodec.decodeSegment(line) ?: return@forEach
            byTime.putIfAbsent(segment.startedAtMs, line)
        }
        val added = byTime.size - mine.count { MlCodec.decodeSegment(it) != null }
        if (added <= 0) return ""

        return runCatching {
            this.directory.mkdirs()
            val merged = byTime.entries.sortedBy { it.key }.joinToString("\n") { it.value }
            val temporary = File(this.directory, "$LOG_FILE.tmp")
            temporary.writeText(merged + "\n")
            temporary.renameTo(logFile)
            // Знімок зібрано зі старого журналу — новим відрізкам він не відповідає.
            modelFile.delete()
            "у журнал поїздок додано $added відрізків"
        }.getOrDefault("")
    }

    init {
        // Прибрати покоління з перевернутим знаком. Мовчки: файлів може й не
        // бути, і це нормально — так буде в кожного, хто поставив застосунок
        // уже після виправлення.
        LEGACY_FILES.forEach { name -> runCatching { File(directory, name).delete() } }
    }

    override fun loadModel(): ModelSnapshot? = try {
        val file = modelFile
        if (file.isFile) MlCodec.decodeModel(file.readText()) else null
    } catch (_: IOException) {
        null
    }

    /**
     * Спершу у тимчасовий файл, потім перейменування. Модель зберігається на ходу,
     * і живлення може зникнути будь-якої миті: часткова заміна лишила б застосунок
     * без моделі, а перейменування або сталося, або ні.
     */
    override fun saveModel(snapshot: ModelSnapshot) {
        try {
            directory.mkdirs()
            val temporary = File(directory, "$MODEL_FILE.tmp")
            temporary.writeText(MlCodec.encodeModel(snapshot))
            if (!temporary.renameTo(modelFile)) {
                modelFile.writeText(temporary.readText())
                temporary.delete()
            }
        } catch (_: IOException) {
            // Не зберегли — не біда: наступний відрізок спробує ще раз, а поки що
            // модель жива в пам'яті. Псувати через це поїздку немає сенсу.
        }
    }

    override fun appendSegment(segment: MlSegment) {
        try {
            directory.mkdirs()
            logFile.appendText(MlCodec.encodeSegment(segment) + "\n")
            if (logFile.length() > MAX_LOG_BYTES) trimLog()
        } catch (_: IOException) {
            // Те саме: журнал — зручність для перенавчання, а не умова роботи.
        }
    }

    override fun readSegments(): List<MlSegment> = try {
        val file = logFile
        if (!file.isFile) {
            emptyList()
        } else {
            file.readLines().mapNotNull(MlCodec::decodeSegment)
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: OutOfMemoryError) {
        emptyList()
    }

    override fun clear() {
        try {
            modelFile.delete()
            logFile.delete()
        } catch (_: IOException) {
            // Нічого не вдієш і нічого не зламається.
        }
    }

    /** Лишає свіжу половину журналу: старе вже враховане в статистиках. */
    private fun trimLog() {
        val kept = logFile.readLines().takeLast(KEEP_LINES)
        val temporary = File(directory, "$LOG_FILE.tmp")
        temporary.writeText(kept.joinToString("\n", postfix = "\n"))
        if (!temporary.renameTo(logFile)) temporary.delete()
    }

    private companion object {
        /** Свої файли: їх і переселяємо, коли з'являється авто. */
        val OWN_FILES get() = listOf(MODEL_FILE, LOG_FILE)

        /**
         * Друге покоління файлів. Усе, що записано першим, вчилося з
         * перевернутим знаком струму: тяга зараховувалася як рекуперація, і
         * відрізки лежать у журналі з від'ємною енергією. Донавчати на них не
         * можна й перенавчати теж — їх треба забути. Тому нові імена: старі
         * файли не читаються, а прибираються при першому ж запуску.
         */
        const val MODEL_FILE = "ml_model-v2.json"
        const val LOG_FILE = "ml_segments-v2.jsonl"

        /** Файли першого покоління: лишилися на диску, і їх треба прибрати. */
        val LEGACY_FILES = listOf("ml_model.json", "ml_segments.jsonl")

        /**
         * Близько десяти мегабайтів. Один відрізок — це приблизно 300 байтів і
         * щонайменше три кілометри, тож межа лежить далеко за будь-яким реальним
         * пробігом: вона стереже від зіпсованого файлу, а не від активного водія.
         */
        const val MAX_LOG_BYTES = 10L * 1024 * 1024
        const val KEEP_LINES = 20_000
    }
}
