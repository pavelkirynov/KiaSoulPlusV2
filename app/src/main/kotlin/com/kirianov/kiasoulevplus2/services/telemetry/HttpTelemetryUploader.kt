// ====================================================================================
// ВІДПРАВНИК ТЕЛЕМЕТРІЇ ЧЕРЕЗ HTTP (HttpTelemetryUploader)
//
// Голий HttpURLConnection, без жодної мережевої бібліотеки — так само, як увесь
// проєкт обходиться без сторонніх залежностей. POST масиву знімків у REST Supabase.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.telemetry

import com.kirianov.kiasoulevplus2.Data.TelemetryConfig
import com.kirianov.kiasoulevplus2.tools.telemetry.TelemetryUploader
import java.net.HttpURLConnection
import java.net.URL

class HttpTelemetryUploader(
    private val baseUrl: String = TelemetryConfig.URL,
    private val apiKey: String = TelemetryConfig.KEY,
    private val table: String = TelemetryConfig.TABLE,
) : TelemetryUploader {

    override fun send(jsonArray: String): Boolean {
        val connection = (URL("$baseUrl/rest/v1/$table").openConnection() as HttpURLConnection)
        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
                // Не просимо сервер повертати вставлені рядки — нам вистачить коду.
                setRequestProperty("Prefer", "return=minimal")
            }
            connection.outputStream.use { it.write(jsonArray.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            // Будь-яка мережева невдача — це «не прийнято»: знімок лишиться в черзі.
            false
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
