package com.kirianov.kiasoulevplus2.Data

/**
 * Сирі відповіді ELM327, як їх отримав блок Bluetooth.
 * Це єдиний канал, яким кадри потрапляють до блока декодерів.
 */
data class CanExchange(
    val batteryFrames: CanFrames? = null,
    val cellFrames: CanFrames? = null,
    /** Відповідь на ручний запит з екрана «Експерименти». */
    val probeFrames: CanFrames? = null,

    /** Сирі рядки, зняті за одне вікно режиму монітора. */
    val monitor: MonitorCapture? = null,

    /** Один прохід тесту комірок: напруги в обрамленні двох замірів струму. */
    val cellSweep: CellSweepFrames? = null,
)

/**
 * Сирий прохід тесту комірок.
 *
 * Струм читається ДВІЧІ — до напруг і після. Прохід по 96 комірках це три запити й
 * до секунди часу; під розгоном струм за цю секунду встигає змінитися вдвічі, і
 * одне значення на початку означало б, що останні тридцять комірок ми приписали
 * чужій нагрузці.
 */
data class CellSweepFrames(
    val beforeResponse: String,
    val cellCommands: List<String>,
    val cellResponses: List<String>,
    val afterResponse: String,
    val atMs: Long,
    val sequence: Long,
)

/**
 * Пакет «команди та відповіді на них».
 *
 * [sequence] росте з кожним зчитуванням: без нього повторне однакове зчитування
 * не відрізнялося б від попереднього і декодер його б пропустив.
 */
data class CanFrames(
    val commands: List<String>,
    val responses: List<String>,
    val sequence: Long,
)
