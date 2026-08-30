// ====================================================================================
// ЗЧИТУВАННЯ ОСНОВНИХ ПОКАЗНИКІВ ВВБ (BatteryDecoder)
//
// Тонкий шар: надсилає кадр 21 01 через ElmCANBridge і віддає розбір далі.
// Уся математика живе в BmsFrameParser + BmsResponseDecoder і тестується окремо.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.BmsCommands
import com.kirianov.kiasoulevplus2.Data.BmsData
import com.kirianov.kiasoulevplus2.services.bluetooth.ElmCANBridge

class BatteryDecoder(private val canBridge: ElmCANBridge) {

    suspend fun getBatteryData(): BmsData {
        val raw = canBridge.sendCANCommand(BmsCommands.HEADER_BMS, BmsCommands.REQUEST_BATTERY_MAIN)
        return BmsResponseDecoder.decode(BmsFrameParser.parse(raw))
    }
}
