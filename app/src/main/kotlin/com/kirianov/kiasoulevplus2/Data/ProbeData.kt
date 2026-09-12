package com.kirianov.kiasoulevplus2.Data

/**
 * Ручний запит до шини з екрана «Експерименти».
 * [sequence] росте, щоб повторний однаковий запит теж виконався.
 */
data class ProbeRequest(
    val header: String,
    val command: String,
    val sequence: Long,
)

/** Одне можливе прочитання: «якщо величина лежить тут, вона дорівнює цьому». */
data class ByteCandidate(
    val index: Int,
    val width: Int,
    val value: Long,
)

/**
 * Знайдене місце відомого значення: «число зі щитка лежить тут, у такому вигляді».
 * [divisor] показує масштаб: 10 означає, що в кадрі величина в десятих.
 */
data class ValueMatch(
    val index: Int,
    val width: Int,
    val divisor: Int,
    val bigEndian: Boolean,
    val rawValue: Long,
)

/**
 * Відповідь на ручний запит разом із розібраними байтами.
 * [error] заповнюється, коли адаптер не відповів: текст помилки корисніший за порожнечу.
 */
data class ProbeResult(
    val header: String,
    val command: String,
    val rawResponse: String,
    val bytes: List<Int> = emptyList(),

    /** Підібрані блоком прочитання, схожі на пробіг. Рахує блок, не екран. */
    val odometerCandidates: List<ByteCandidate> = emptyList(),

    /** Точні збіги з відомим значенням, якщо воно задане. */
    val matches: List<ValueMatch> = emptyList(),

    val error: String? = null,
) {
    val hasBytes: Boolean get() = bytes.isNotEmpty()
}

/**
 * Знімок шини: що який кадр показував у певний момент.
 *
 * Потрібен для пошуку невідомих ознак. Прямої ознаки увімкненого запалювання в
 * наших нотатках немає, і вгадувати біти вже виходило дорого. Тому — вимір: зняти
 * шину при вимкненому авто, потім при готовому до руху, і порівняти.
 *
 * [label] — чим цей знімок був: «двигун вимкнено», «готове до руху». Пише сам
 * користувач, бо через тиждень «А» і «Б» уже нічого не означають.
 */
data class BusSnapshot(
    val label: String,
    val atMs: Long,
    val frames: Map<String, List<Int>> = emptyMap(),
) {
    val hasFrames: Boolean get() = frames.isNotEmpty()
}

/**
 * Прохання записати шину протягом [seconds]: слухати без фільтра довгим вікном і
 * вести журнал того, що на ній зʼявляється й міняється.
 *
 * Знімок ([SweepRequest]) ловить те, що на шині Є ПОСТІЙНО. А натискання кнопки —
 * замок, поворотник, наближення ключа — це подія на частку секунди: кадр зʼявився
 * й зник. Щоб її впіймати, треба слухати підряд і записувати КОЖНУ зміну, а не
 * один зріз.
 */
data class RecordRequest(val seconds: Int, val sequence: Long)

/**
 * Одна зафіксована зміна на шині: кадр [id] став [bytes] о [atMs].
 *
 * Записуємо не кожен кадр, а лише КОЛИ ВІН ЗМІНИВСЯ, — інакше за тридцять секунд
 * набіжали б тисячі однакових рядків, серед яких натискання не знайти. Кадр, що
 * весь час однаковий, дає один рядок; кадр, що смикнувся від кнопки, — рядок саме
 * в ту мить.
 */
data class BusEvent(val atMs: Long, val id: String, val bytes: List<Int>)

/**
 * Результат запису шини: журнал змін за вікно.
 *
 * [running] — чи запис іще триває: екран показує «йде запис», а журнал зливається
 * у файл лише коли скінчилось. [totalLines] — скільки сирих рядків загалом
 * пройшло: за ним видно, чи шина взагалі говорила, чи мовчала.
 */
data class BusRecording(
    val startedAtMs: Long,
    val seconds: Int,
    val events: List<BusEvent> = emptyList(),
    val totalLines: Int = 0,
    val running: Boolean = false,
) {
    /** Скільки різних кадрів засвітилося за запис. */
    val distinctIds: Int get() = events.map { it.id }.toSet().size

    companion object {
        /**
         * Скільки змін тримати в памʼяті. Триста вистачає з запасом: на стоячому
         * авто зі знятим запалюванням шина майже мовчить, а нам цікаві саме
         * поодинокі сплески від кнопок.
         */
        const val MAX_EVENTS = 300
    }
}

/** Прохання послухати шину без фільтра, щоб побачити, які кадри на ній узагалі є. */
data class SweepRequest(val sequence: Long)

/** Куди класти знімок. Двох досить: шукаємо різницю між двома станами авто. */
enum class BusSlot { A, B }

data class ProbeState(
    val pending: ProbeRequest? = null,
    val results: List<ProbeResult> = emptyList(),

    /** Відоме значення, яке шукаємо у відповідях: наприклад, пробіг зі щитка. */
    val targetValue: Long? = null,

    /**
     * Останнє, що показував кожен кадр. Накопичується вікно за вікном.
     *
     * Одне вікно монітора слухає рівно один ID — інакше адаптер захлинається, — тож
     * побачити всю шину відразу неможливо в принципі. Зате можна пам'ятати, чим
     * скінчився кожен ID, і знімок збирати вже з цієї пам'яті.
     */
    val liveFrames: Map<String, List<Int>> = emptyMap(),

    /** Два знімки для порівняння. Третій не потрібен: шукаємо різницю між двома станами. */
    val snapshotA: BusSnapshot? = null,
    val snapshotB: BusSnapshot? = null,

    val sweep: SweepRequest? = null,

    /** Прохання записати шину; його виконує блок Bluetooth і скидає в null. */
    val record: RecordRequest? = null,

    /** Останній запис шини: журнал змін за вікно. */
    val recording: BusRecording? = null,
) {
    fun plus(result: ProbeResult) = copy(results = (listOf(result) + results).take(MAX_RESULTS))

    companion object {
        const val MAX_RESULTS = 10
    }
}
