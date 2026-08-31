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
