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

import android.content.Context
import com.kirianov.kiasoulevplus2.Data.MlSegment
import java.io.File
import java.io.IOException

interface MlStore {
    fun loadModel(): ModelSnapshot?
    fun saveModel(snapshot: ModelSnapshot)
    fun appendSegment(segment: MlSegment)
    fun readSegments(): List<MlSegment>
    fun clear()
}

class FileMlStore(private val directory: File) : MlStore {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val modelFile get() = File(directory, MODEL_FILE)
    private val logFile get() = File(directory, LOG_FILE)

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
        const val MODEL_FILE = "ml_model.json"
        const val LOG_FILE = "ml_segments.jsonl"

        /**
         * Близько десяти мегабайтів. Один відрізок — це приблизно 300 байтів і
         * щонайменше три кілометри, тож межа лежить далеко за будь-яким реальним
         * пробігом: вона стереже від зіпсованого файлу, а не від активного водія.
         */
        const val MAX_LOG_BYTES = 10L * 1024 * 1024
        const val KEEP_LINES = 20_000
    }
}
