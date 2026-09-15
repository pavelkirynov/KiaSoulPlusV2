// ====================================================================================
// ПОРІВНЯННЯ ДВОХ ЗНІМКІВ ШИНИ (FrameDiff)
//
// Інструмент для однієї конкретної роботи: знайти на шині біт, якого ми не знаємо.
//
// Прямої ознаки увімкненого запалювання в наших нотатках по CAN немає, а вгадувати
// біти вже виходило дорого — на неправильно прочитаному знаку струму модель одного
// разу вчилася навиворіт цілий тиждень. Тож замість здогадів — вимір: зняти шину
// двічі, у двох станах авто, і подивитися, ЩО саме змінилося.
//
// Порівняння побайтне й побітне водночас, і бітове тут головне. Ознака «авто готове
// до руху» — це майже напевно один біт у якомусь байті; побачити «B4: 04 → 06» без
// підказки «біт 1» означає ще довго дивитися на числа.
//
// Чисті функції над списками чисел: перевіряються тестами без шини й без Android.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.frames

/**
 * Одна відмінність: у кадрі [id] байт номер [index] був [from], став [to].
 *
 * [changedBits] — номери бітів, які перемкнулися, від молодшого (0) до старшого (7).
 */
data class ByteChange(
    val id: String,
    val index: Int,
    val from: Int,
    val to: Int,
    val changedBits: List<Int>,
) {
    /** Рядок для екрана: «B4  04 → 06  біти 1». */
    fun describe(): String {
        val bits = if (changedBits.isEmpty()) "" else "  біти ${changedBits.joinToString(",")}"
        return "B%d  %02X → %02X%s".format(index, from, to, bits)
    }
}

/** Що сталося з цілим кадром між двома знімками. */
data class FrameChange(
    val id: String,
    val changes: List<ByteChange>,
    /** Кадр був лише в одному зі знімків: у другому його не застали. */
    val onlyInOne: Boolean = false,
    val lengthChanged: Boolean = false,
)

object FrameDiff {

    /**
     * Порівняти два знімки шини.
     *
     * Кадри, яких немає в одному зі знімків, потрапляють у результат окремо і без
     * побайтного розбору: «не застали» і «змінилося» — різні звістки, і плутати їх
     * не можна. Вікно монітора коротке, тож відсутність кадру часто означає лише
     * те, що він не встиг прийти.
     *
     * Кадри без жодної зміни не повертаються взагалі: на шині їх десятки, і саме
     * тиша навколо однієї зміни й робить її помітною.
     */
    fun compare(before: Map<String, List<Int>>, after: Map<String, List<Int>>): List<FrameChange> {
        val ids = (before.keys + after.keys).sorted()
        val result = mutableListOf<FrameChange>()

        for (id in ids) {
            val a = before[id]
            val b = after[id]
            if (a == null || b == null) {
                result += FrameChange(id = id, changes = emptyList(), onlyInOne = true)
                continue
            }
            val changes = bytesOf(id, a, b)
            if (changes.isEmpty() && a.size == b.size) continue
            result += FrameChange(id = id, changes = changes, lengthChanged = a.size != b.size)
        }
        return result
    }

    /** Побайтне порівняння в межах спільної довжини. */
    private fun bytesOf(id: String, a: List<Int>, b: List<Int>): List<ByteChange> {
        val common = minOf(a.size, b.size)
        val changes = mutableListOf<ByteChange>()
        for (index in 0 until common) {
            val from = a[index]
            val to = b[index]
            if (from == to) continue
            changes += ByteChange(id, index, from, to, changedBits(from, to))
        }
        return changes
    }

    /** Номери бітів, які відрізняються, від молодшого до старшого. */
    fun changedBits(from: Int, to: Int): List<Int> {
        val mask = from xor to
        return (0..7).filter { bit -> mask shr bit and 1 == 1 }
    }
}
