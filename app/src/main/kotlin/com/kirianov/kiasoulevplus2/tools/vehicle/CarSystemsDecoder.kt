// ====================================================================================
// ДЕКОДЕР КАДРІВ СИСТЕМ АВТО (CarSystemsDecoder)
//
// Розкладає п'ять кадрів, які знайшлися у вихідниках SoulEVSpy і яких у нас не було:
// 4B0 (колеса), 433 (гальмо), 050 (світло, склоочисники, поворотники), 567
// (годинник) і 4F2 (швидкість плюс пара бітів, які ми хибно вважали запалюванням).
//
// ЧОМУ ОКРЕМИЙ ДЕКОДЕР, А НЕ ДОПИСАТИ ДО BroadcastDecoder. Той декодер живить
// розрахунки — пробіг, SOC, запас ходу; його формули перевірені на цій машині
// роками журналів. Ці — ще ні. Окремий файл і окреме поле стану означають, що
// неперевірене число фізично не може потрапити в прогноз, доки ми самі його туди
// не перенесемо.
//
// КОЖЕН КАДР ПЕРЕВІРЯЄТЬСЯ НА ПРАВДОПОДІБНІСТЬ. Адаптер, що захлинувся, віддає
// рядок правильної довжини з самих 0xFF, і саме такий кадр виглядає найпереконливіше:
// 65535/30 це 2184 км/год, а година 255 — все ще число. Тому кадр, який не проходить
// перевірку, не оновлює нічого: краще прочерк, ніж тихо неправильне.
//
// Чистий об'єкт без стану: перевіряється тестами без адаптера й без авто.
// ====================================================================================

package com.kirianov.kiasoulevplus2.tools.vehicle

import com.kirianov.kiasoulevplus2.Data.BrakeState
import com.kirianov.kiasoulevplus2.Data.CabinControls
import com.kirianov.kiasoulevplus2.Data.CanBroadcastFrame
import com.kirianov.kiasoulevplus2.Data.CarClock
import com.kirianov.kiasoulevplus2.Data.CarSystems
import com.kirianov.kiasoulevplus2.Data.DriveState
import com.kirianov.kiasoulevplus2.Data.LightsMode
import com.kirianov.kiasoulevplus2.Data.TurnSignal
import com.kirianov.kiasoulevplus2.Data.WheelSpeeds
import com.kirianov.kiasoulevplus2.Data.WiperSpeed

object CarSystemsDecoder {

    /** ID кадрів, які розбирає саме цей декодер. */
    val KNOWN_IDS = setOf(WHEELS, BRAKE, CABIN, CLOCK, DRIVE)

    /** Домішує до [previous] усе, що вдалося розібрати з [frames]. */
    fun merge(previous: CarSystems, frames: List<CanBroadcastFrame>): CarSystems =
        frames.fold(previous) { systems, frame -> apply(systems, frame) }

    private fun apply(systems: CarSystems, frame: CanBroadcastFrame): CarSystems {
        val b = frame.bytes
        return when (frame.id) {
            WHEELS -> wheels(b)?.let { systems.copy(wheels = it) } ?: systems
            BRAKE -> brake(b)?.let { systems.copy(brake = it) } ?: systems
            CABIN -> cabin(b)?.let { systems.copy(cabin = it) } ?: systems
            CLOCK -> clock(b)?.let { systems.copy(clock = it) } ?: systems
            DRIVE -> drive(b)?.let { systems.copy(drive = it) } ?: systems
            else -> systems
        }
    }

    /**
     * Колеса: по два байти на кожне, молодший перший, поділити на тридцять.
     *
     * Порядок колес узятий із SoulEVSpy як є: перше переднє ліве, потім переднє
     * праве, заднє ліве, заднє праве. Перевірити його можна лише в повороті — там
     * зовнішні колеса мусять іти швидше за внутрішні, — тож поки він теж здогад.
     */
    private fun wheels(b: List<Int>): WheelSpeeds? {
        if (b.size < 8) return null
        val speeds = (0 until 4).map { wheel -> word(b, wheel * 2) / WHEEL_STEPS_PER_KMH }
        if (speeds.any { it > MAX_PLAUSIBLE_SPEED_KMH }) return null
        return WheelSpeeds(
            known = true,
            frontLeftKmh = speeds[0],
            frontRightKmh = speeds[1],
            rearLeftKmh = speeds[2],
            rearRightKmh = speeds[3],
        )
    }

    private fun brake(b: List<Int>): BrakeState? {
        if (b.size < 3) return null
        return BrakeState(known = true, brakeBit = b[2] and BRAKE_BIT != 0)
    }

    private fun cabin(b: List<Int>): CabinControls? {
        if (b.size < 4) return null
        val byte1 = b[1]
        val byte2 = b[2]

        val lights = when (byte1 and MASK_LIGHTS) {
            0x01 -> LightsMode.Parking
            0x02 -> LightsMode.On
            0x03 -> LightsMode.Automatic
            else -> LightsMode.Off
        }
        val turn = when (byte2 and MASK_TURN_SIGNAL) {
            0x10 -> TurnSignal.Right
            0x20 -> TurnSignal.Left
            else -> TurnSignal.Off
        }
        val wipers = when (byte2 and MASK_WIPERS) {
            0x01 -> WiperSpeed.Normal
            0x02 -> WiperSpeed.Intermittent
            0x04 -> WiperSpeed.Fast
            else -> WiperSpeed.Off
        }

        return CabinControls(
            known = true,
            lights = lights,
            turnSignal = turn,
            wipers = wipers,
            wiperStep = if (wipers == WiperSpeed.Intermittent) wiperStep(byte1) else 0,
        )
    }

    /**
     * Щабель перебійного режиму: старша половина байта 1, і що вона менша, то
     * швидше ходить щітка. Порядок саме такий у SoulEVSpy — 0x80 найповільніший,
     * 0x00 найшвидший.
     */
    private fun wiperStep(byte1: Int): Int = when (byte1 and MASK_WIPERS_STEP) {
        0x80 -> 0
        0x60 -> 1
        0x40 -> 2
        0x20 -> 3
        else -> 4
    }

    /**
     * Годинник: години, хвилини, секунди трьома байтами.
     *
     * Перевірка тут заодно найкраще ловить сміття з адаптера: 0xFF не буває ні
     * годиною, ні хвилиною.
     */
    private fun clock(b: List<Int>): CarClock? {
        if (b.size < 4) return null
        val hour = b[1]
        val minute = b[2]
        val second = b[3]
        if (hour > MAX_HOUR || minute > MAX_MINUTE || second > MAX_SECOND) return null
        return CarClock(known = true, hour = hour, minute = minute, second = second)
    }

    /**
     * Швидкість і пара непояснених бітів з одного кадру.
     *
     * Швидкість — самим байтом 1, без старшого біта байта 2, який SoulEVSpy до неї
     * додає: з тим бітом виходило 128 км/год на нерухомій машині, а без нього —
     * рівно те саме, що в кадрі 4F0.
     *
     * Біти 6-7 байта 2 зберігаються ЗНАЧЕННЯМ, а не прапорцем. Ми вважали їх
     * запалюванням, і журнал це заперечив: нуль на 91.5 км/год, одиниця на місці.
     * Якщо це лічильник кадру, він переберає 0-1-2-3, і саме це тепер видно.
     */
    private fun drive(b: List<Int>): DriveState? {
        if (b.size < 8) return null
        return DriveState(
            known = true,
            speedKmh = b[1] / 2.0,
            counterBits = (b[2] and MASK_COUNTER_BITS) shr COUNTER_BITS_SHIFT,
        )
    }

    private fun word(b: List<Int>, low: Int): Int = b[low] or (b[low + 1] shl 8)

    /** Кадр 4B0 рахує оберти колеса, а не кілометри: тридцять кроків на км/год. */
    private const val WHEEL_STEPS_PER_KMH = 30.0

    /**
     * Стеля правдоподібності швидкості колеса, км/год.
     *
     * Триста — це не «а раптом поїдемо швидше», а межа, за якою число вже точно
     * не швидкість: кадр із самих 0xFF дає 2184.
     */
    private const val MAX_PLAUSIBLE_SPEED_KMH = 300.0

    private const val BRAKE_BIT = 0x08

    private const val MASK_LIGHTS = 0x03
    private const val MASK_WIPERS_STEP = 0xF0
    private const val MASK_WIPERS = 0x07
    private const val MASK_TURN_SIGNAL = 0x30

    /**
     * Біти 6-7 байта 2. При повороті ключа вони справді змінюються з 00 на C0 —
     * тому ми й вважали їх запалюванням, — але в дорозі поводяться не як ключ.
     */
    private const val MASK_COUNTER_BITS = 0xC0
    private const val COUNTER_BITS_SHIFT = 6

    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59
    private const val MAX_SECOND = 59

    private const val WHEELS = "4B0"
    private const val BRAKE = "433"
    private const val CABIN = "050"
    private const val CLOCK = "567"
    private const val DRIVE = "4F2"
}
