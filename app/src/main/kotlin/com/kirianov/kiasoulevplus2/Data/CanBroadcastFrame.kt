package com.kirianov.kiasoulevplus2.Data

/**
 * Широкомовний кадр, зловлений у режимі монітора.
 *
 * [bytes] — байти даних одразу після CAN ID, тобто bytes[0] це b0 з карти кадрів.
 */
data class CanBroadcastFrame(
    val id: String,
    val bytes: List<Int>,
)

/**
 * Порція сирих рядків, знятих за одне вікно монітора.
 * [sequence] росте, щоб однакові вікна не злилися для підписників.
 */
data class MonitorCapture(
    val lines: List<String>,
    val sequence: Long,
)
