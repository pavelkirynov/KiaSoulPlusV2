// ====================================================================================
// СХОВИЩЕ РУЧНИХ НАПРУГ (ManualCellStore)
//
// Інтерфейс відділений від реалізації на SharedPreferences, щоб блок сховища можна було
// перевірити тестами без Android.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.storage

import android.content.Context
import com.kirianov.kiasoulevplus2.Data.CellData

interface ManualCellStore {
    fun load(): Map<Int, Double>
    fun save(voltages: Map<Int, Double>)
}

class SharedPreferencesCellStore(context: Context) : ManualCellStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Map<Int, Double> =
        (0 until CellData.TOTAL_CELLS)
            .associateWith { index -> prefs.getFloat(key(index), 0f).toDouble() }
            .filterValues { it > 0.0 }

    override fun save(voltages: Map<Int, Double>) {
        prefs.edit().apply {
            voltages.forEach { (index, voltage) -> putFloat(key(index), voltage.toFloat()) }
            apply()
        }
    }

    private fun key(index: Int) = "cell_$index"

    private companion object {
        const val PREFS_NAME = "cell_voltage_prefs"
    }
}
