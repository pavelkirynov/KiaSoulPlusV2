package com.kirianov.kiasoulevplus2.services.AndroidAuto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

// Змініть com.example на com.kirianov:
import com.kirianov.kiasoulevplus2.services.AndroidAuto.MainCarScreen

class CarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: android.content.Intent): androidx.car.app.Screen {
                return MainCarScreen(carContext)
            }
        }
    }
}
