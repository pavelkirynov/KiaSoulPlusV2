package com.kirianov.kiasoulevplus2.Data

/**
 * Діапазон, за який рахується витрата.
 *
 * [distanceKm] = null означає «за всю поїздку»; решта — останні N кілометрів.
 */
enum class ConsumptionWindow(val distanceKm: Double?) {
    Trip(null),
    Last1Km(1.0),
    Last5Km(5.0),
    Last20Km(20.0),
}
