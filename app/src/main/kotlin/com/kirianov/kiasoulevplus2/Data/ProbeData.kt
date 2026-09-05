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
) {
    fun plus(result: ProbeResult) = copy(results = (listOf(result) + results).take(MAX_RESULTS))

    companion object {
        const val MAX_RESULTS = 10
    }
}
