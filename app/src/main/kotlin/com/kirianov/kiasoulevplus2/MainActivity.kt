// ====================================================================================
// ГОЛОВНА АКТИВНІСТЬ ДОДАТКА (MainActivity)
//
// Тільки показує Compose-UI. Блоки піднімає App: інакше опитування зупинялося б,
// щойно екран телефона закриють, і Android Auto показував би порожні значення.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kirianov.kiasoulevplus2.Interface.AppNavigation

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppNavigation() }
    }
}
