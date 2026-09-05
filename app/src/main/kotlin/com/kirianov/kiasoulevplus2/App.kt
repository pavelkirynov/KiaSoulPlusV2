// ====================================================================================
// ДОДАТОК (App)
//
// Блоки живуть тут, а не в MainActivity. Раніше вони піднімалися на lifecycleScope
// активності, тому опитування CAN зупинялося, щойно екран телефона закривався — а
// саме так і буває, коли водій користується магнітолою. Тепер блоки живуть увесь
// час, поки живий процес, і медіа-сервіс Android Auto бачить свіжі дані навіть якщо
// екран телефона ніхто не відкривав.
// ====================================================================================

package com.kirianov.kiasoulevplus2

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppBlocks(this).start(scope)
    }
}
