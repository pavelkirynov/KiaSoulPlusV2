// ====================================================================================
// ДЕКОДЕР ПРОБІГУ (OdometerDecoder)
//
// Розбирає відповідь щитка приладів на запит 22 B0 02. Пробіг лежить трьома
// байтами і рахується просто в кілометрах.
//
// УВАГА: зсув байтів не звірений із живим авто. Тому тут стоїть перевірка на
// правдоподібність: якщо прочитано не те місце, значення відкидається і додаток
// показує прочерк замість переконливої дурниці. Витрата за поїздку в кВт·год
// від пробігу не залежить і працює навіть тоді, коли пробіг не зчитався.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.battery

import com.kirianov.kiasoulevplus2.Data.VehicleData
import com.kirianov.kiasoulevplus2.tools.frames.FrameParser

object OdometerDecoder {

    private const val ODOMETER_INDEX = 9
    private const val MIN_FRAME_SIZE = ODOMETER_INDEX + 3

    /** Більше за це на одометрі не буває; менше нуля — тим паче. */
    private const val MAX_PLAUSIBLE_KM = 2_000_000.0

    fun decode(bytes: List<Int>): VehicleData {
        if (bytes.size < MIN_FRAME_SIZE) return VehicleData()

        val km = FrameParser.unsigned24(bytes, ODOMETER_INDEX).toDouble()
        return if (km in 1.0..MAX_PLAUSIBLE_KM) VehicleData(odometerKm = km) else VehicleData()
    }
}
