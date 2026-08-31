// ====================================================================================
// ГОЛОВНА АКТИВНІСТЬ ДОДАТКА (MainActivity)
//
// Точка входу: піднімає блоки та показує Compose-UI. Сама нічого не координує —
// обмін між блоками йде через GeneralData.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.kirianov.kiasoulevplus2.Interface.AppNavigation

class MainActivity : ComponentActivity() {

    private val blocks by lazy { AppBlocks(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blocks.start(lifecycleScope)
        setContent { AppNavigation() }
    }

    override fun onDestroy() {
        super.onDestroy()
        blocks.stop()
    }
}
