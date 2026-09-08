package com.kirianov.kiasoulevplus2.services.bluetooth

import com.kirianov.kiasoulevplus2.tools.vehicle.BroadcastDecoder
import com.kirianov.kiasoulevplus2.tools.vehicle.CarSystemsDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Черга фільтрів монітора визначає, як часто приходить кожна величина, а через це —
 * наскільки швидко вчиться модель. Зіпсувати її можна одним необережним рядком і
 * не помітити місяцями, тож розкладка застережена тестом.
 */
class MonitorRotationTest {

    private val rotation = BluetoothBlock.MONITOR_ROTATION

    /**
     * Кожен кадр, який застосунок УМІЄ розібрати, мусить і питатися.
     *
     * Список навмисно не переписаний тут руками, а взятий із самих декодерів:
     * додати декодер і забути дописати ID у чергу — помилка, яку інакше не видно
     * взагалі. Кадр не приходить, поле стоїть прочерком, і причина «його ніхто не
     * замовляв» виглядає точно так само, як «машина його не передає».
     */
    @Test
    fun `every frame either decoder knows is asked for`() {
        val asked = rotation.toSet()
        (BroadcastDecoder.KNOWN_IDS + CarSystemsDecoder.KNOWN_IDS).forEach { id ->
            assertTrue("кадр $id мусить питатися: $asked", id in asked)
        }
    }

    /** І навпаки: у черзі не має бути ID, який нікому розбирати. */
    @Test
    fun `nothing is asked for in vain`() {
        val decoded = BroadcastDecoder.KNOWN_IDS + CarSystemsDecoder.KNOWN_IDS
        rotation.toSet().forEach { id ->
            assertTrue("кадр $id ніхто не розбирає", id in decoded)
        }
    }

    /** Пробіг і швидкість — основа кожного відрізка, тож кожне друге вікно їхнє. */
    @Test
    fun `odometer and speed take every other window`() {
        assertEquals(rotation.size / 2, rotation.count { it == "4F0" })
        assertTrue(
            "4F0 має стояти через один",
            rotation.filterIndexed { index, _ -> index % 2 == 0 }.all { it == "4F0" },
        )
    }

    /**
     * Точний SOC мусить приходити частіше за решту повільних кадрів: саме з нього
     * вчиться крива ємності, і рідкий SOC означав би грубу криву.
     */
    @Test
    fun `the precise charge comes more often than the slow signals`() {
        val precise = rotation.count { it == "598" }
        listOf("594", "200", "581", "653").forEach { id ->
            assertTrue(
                "598 ($precise) має питатися частіше за $id (${rotation.count { it == id }})",
                precise > rotation.count { it == id },
            )
        }
    }

    /** Кожен запис має бути справжнім CAN ID, інакше «AT CRA» мовчки не спрацює. */
    @Test
    fun `every entry is a real can id`() {
        rotation.forEach { id ->
            assertEquals("ID має бути тризначним шістнадцятковим: $id", 3, id.length)
            assertTrue("не шістнадцяткове: $id", id.all { it.isDigit() || it in 'A'..'F' })
        }
    }
}
