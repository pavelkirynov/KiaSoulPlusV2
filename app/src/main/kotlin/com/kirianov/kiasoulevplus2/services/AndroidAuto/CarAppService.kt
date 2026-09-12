package com.kirianov.kiasoulevplus2.services.AndroidAuto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.kirianov.kiasoulevplus2.BuildConfig

class CarAppService : CarAppService() {

    /**
     * У debug-збірці приймаємо будь-який хост, щоб працювала розробка з емулятором DHU.
     * У release хост звіряється зі списком підписів Android Auto: інакше під'єднатися
     * до сервісу зміг би будь-який сторонній застосунок.
     */
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = MainCarScreen(carContext)
    }
}
