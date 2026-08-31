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

    val error: String? = null,
) {
    val hasBytes: Boolean get() = bytes.isNotEmpty()
}

data class ProbeState(
    val pending: ProbeRequest? = null,
    val results: List<ProbeResult> = emptyList(),
) {
    fun plus(result: ProbeResult) = copy(results = (listOf(result) + results).take(MAX_RESULTS))

    companion object {
        const val MAX_RESULTS = 10
    }
}
