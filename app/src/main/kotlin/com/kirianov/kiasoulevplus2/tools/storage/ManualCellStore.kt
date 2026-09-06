// ====================================================================================
// СХОВИЩЕ РУЧНИХ НАПРУГ (ManualCellStore)
//
// Інтерфейс відділений від реалізації на SharedPreferences, щоб блок сховища можна було
// перевірити тестами без Android.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.storage

import android.content.Context
import com.kirianov.kiasoulevplus2.Data.CellData
import com.kirianov.kiasoulevplus2.tools.paths.CarPaths

interface ManualCellStore {
    /**
     * Перевести сховище на дані конкретного авто.
     *
     * Порожня реалізація навмисно: у пам'яті, де сховище живе одним об'єктом на
     * тест, переселяти нічого.
     */
    fun useCar(vin: String) {}

    fun load(): Map<Int, Double>
    fun save(voltages: Map<Int, Double>)
}

class SharedPreferencesCellStore(context: Context) : ManualCellStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Чиї це напруги. Порожньо — спадщина з часів, коли авто було одне.
     *
     * Тут префікс до ключа, а не окремий файл, як у решти сховищ: руками введені
     * напруги — це чернетка на випадок, коли шина мовчить, а не дані, які варто
     * зливати між телефонами. Розділити їх по авто все одно треба: комірки другої
     * машини не мають підмінювати комірки першої.
     */
    @Volatile
    private var carPrefix = ""

    override fun useCar(vin: String) {
        carPrefix = CarPaths.folderName(vin) + "_"
    }

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

    private fun key(index: Int) = "${carPrefix}cell_$index"

    private companion object {
        const val PREFS_NAME = "cell_voltage_prefs"
    }
}
