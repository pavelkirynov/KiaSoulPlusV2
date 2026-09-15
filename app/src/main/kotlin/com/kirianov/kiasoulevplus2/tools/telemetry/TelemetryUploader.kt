package com.kirianov.kiasoulevplus2.tools.telemetry

/**
 * Хто фактично відправляє готове тіло запиту на сервер.
 *
 * Інтерфейс, а не конкретний клас, навмисно: цикл і збірка знімка нічого не знають
 * про мережу, а в тестах підставляється фейк. [send] повертає true лише коли сервер
 * прийняв дані — інакше [TelemetryBlock] лишить їх у черзі на наступну спробу.
 */
interface TelemetryUploader {
    fun send(jsonArray: String): Boolean
}
