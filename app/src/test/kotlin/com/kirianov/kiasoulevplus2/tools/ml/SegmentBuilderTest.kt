package com.kirianov.kiasoulevplus2.tools.ml

import com.kirianov.kiasoulevplus2.Data.MlSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentBuilderTest {

    /**
     * Рівна їзда з відомою потужністю: відрізок має вийти з правильним пробігом
     * і правильною енергією. Це основа всього — на зсунутих відрізках жодна модель
     * не врятує.
     */
    @Test
    fun `builds a segment with the distance and energy that were actually driven`() {
        val drive = Drive(speedKmh = 60.0, powerKw = -10.0)

        val segment = drive.runUntilSegment(seconds = 400)

        assertNotNull("відрізок мав закритися", segment)
        segment!!
        assertEquals("пробіг", 5.0, segment.distanceKm, 0.15)
        assertEquals("тривалість", 300_000.0, segment.durationMs.toDouble(), 15_000.0)
        assertEquals("енергія", 0.833, segment.energyKwh, 0.05)
        assertEquals("витрата", 167.0, segment.whPerKm ?: 0.0, 10.0)
        assertEquals("середня швидкість", 16.67, segment.meanSpeedMps, 0.3)
    }

    /**
     * Провал у даних: адаптер відвалився на пів хвилини. Пробіг за цей час зріс,
     * а спожита енергія — ні. Такий відрізок мусить бути викинутий цілком, інакше
     * модель вивчить, що машина частину дороги їде задарма.
     */
    @Test
    fun `a hole in the data throws the segment away`() {
        val builder = SegmentBuilder()
        val drive = Drive(speedKmh = 60.0, powerKw = -10.0, builder = builder)

        drive.advance(seconds = 200)
        // Тридцять секунд тиші, і лічильник за цей час утік.
        drive.skip(seconds = 30)
        val segment = drive.runUntilSegment(seconds = 400)

        assertNotNull(segment)
        // Відрізок мусить бути зібраний уже ПІСЛЯ дірки, а не крізь неї.
        assertTrue("енергія має відповідати пробігу", (segment!!.whPerKm ?: 0.0) in 150.0..185.0)
    }

    /**
     * Затор і стоянка з кліматом — теж відрізок, просто без пробігу. Саме він
     * найчистіше показує постійний відбір, і викидати його було б втратою
     * найкращих даних, які взагалі бувають.
     */
    @Test
    fun `standing still with the climate on is still a segment`() {
        val drive = Drive(speedKmh = 0.0, powerKw = -1.5)

        val segment = drive.runUntilSegment(seconds = 1000)

        assertNotNull("стоянка мала закритися за часом", segment)
        segment!!
        assertEquals("пробігу немає", 0.0, segment.distanceKm, 1e-9)
        assertEquals("тривалість", 900_000.0, segment.durationMs.toDouble(), 5_000.0)
        assertEquals("спожито самим лише кліматом", 0.375, segment.energyKwh, 0.02)
        assertEquals("увесь час стояли", 1.0, segment.stoppedFraction, 0.01)
        assertEquals("середня потужність", -1.5, -(segment.averagePowerKw ?: 0.0), 0.05)
    }

    /** Рекуперація віднімається від тяги, а не додається до неї. */
    @Test
    fun `regeneration comes back off the total`() {
        val builder = SegmentBuilder()
        val drive = Drive(speedKmh = 60.0, powerKw = -10.0, builder = builder)
        drive.advance(seconds = 250)
        // Гальмування в кінці: рекуперації менше, ніж тяги, тож відрізок лишається
        // звичайною поїздкою — саме на такій і перевіряється знак.
        drive.powerKw = 10.0
        val segment = drive.runUntilSegment(seconds = 150)

        assertNotNull(segment)
        assertTrue("рекуперація мала бути порахована", segment!!.regenKwh > 0.0)
        assertTrue("чиста енергія менша за тягу", segment.energyKwh < segment.tractionKwh)
        assertTrue("і все ж додатна", segment.energyKwh > 0.0)
    }

    /**
     * Довгий спуск: авто їхало, а батарея поповнилась. Такий відрізок у науку не
     * йде — не тому, що дані брехливі, а тому, що модель не має ознаки висоти й
     * прочитає його як «на цій швидкості авто віддає мінус кіловат».
     *
     * Число, заради якого це зроблено: у журналі за три дні набралося 93 км, і один
     * такий відрізок опускав середню витрату з 15.6 до 13.5 кВт·год/100 км — запас
     * ходу з 320 до 370 км. Одна точка з сімнадцяти рухала відповідь на 15 %.
     */
    @Test
    fun `a long descent does not become a lesson`() {
        val builder = SegmentBuilder()
        val drive = Drive(speedKmh = 60.0, powerKw = 8.0, builder = builder)

        assertNull("спуск не мав стати відрізком", drive.runUntilSegment(seconds = 400))
        assertTrue("і причина мала бути названа", builder.lastAbortReason.startsWith("спуск"))
    }

    /** Перехід «їдемо → заряджаємось» розриває відрізок: змішувати їх не можна. */
    @Test
    fun `switching to charging starts a new segment`() {
        val builder = SegmentBuilder()
        val drive = Drive(speedKmh = 60.0, powerKw = -10.0, builder = builder)
        drive.advance(seconds = 200)

        drive.charging = true
        assertNull("відрізок мав початися заново", drive.runUntilSegment(seconds = 100))
    }

    /** Розрив зв'язку забуває незакритий відрізок. */
    @Test
    fun `losing the connection forgets the open segment`() {
        val builder = SegmentBuilder()
        val drive = Drive(speedKmh = 60.0, powerKw = -10.0, builder = builder)
        drive.advance(seconds = 250)
        assertTrue(builder.hasOpenSegment)

        builder.reset()

        assertTrue("нічого не мало лишитися", !builder.hasOpenSegment)
    }

    /**
     * Знімок без нових даних приходить постійно: сховище оновлюється і з інших
     * причин. Такий знімок не має ані подвоювати енергію, ані рухати час.
     */
    @Test
    fun `a repeated snapshot changes nothing`() {
        val builder = SegmentBuilder()
        val sample = MlSample(elapsedMs = 1000, wallClockMs = 0, powerKw = -10.0, odometerKm = 1.0, speedKmh = 60.0)

        builder.accept(sample)
        assertNull(builder.accept(sample))
        assertNull(builder.accept(sample))
    }

    /**
     * Керує потоком знімків так, як їх бачить блок: раз на секунду, з лічильником
     * пробігу, що клацає кроком 0.1 км.
     */
    private class Drive(
        val speedKmh: Double,
        var powerKw: Double,
        val builder: SegmentBuilder = SegmentBuilder(),
        var charging: Boolean = false,
    ) {
        private var elapsedMs = 0L
        private var odometerKm = 1000.0

        fun advance(seconds: Int): MlSegment? {
            var produced: MlSegment? = null
            repeat(seconds) {
                elapsedMs += 1000
                odometerKm += speedKmh / 3600.0
                val result = builder.accept(
                    MlSample(
                        elapsedMs = elapsedMs,
                        wallClockMs = elapsedMs,
                        powerKw = powerKw,
                        // Лічильник віддає лише десяті: саме так приходить кадр 4F0.
                        odometerKm = Math.floor(odometerKm * 10.0) / 10.0,
                        speedKmh = speedKmh,
                        ambientTempC = 15.0,
                        batteryTempC = 20.0,
                        charging = charging,
                    ),
                )
                if (result != null && produced == null) produced = result
            }
            return produced
        }

        /** Тиша: час і пробіг ідуть, а знімків немає. */
        fun skip(seconds: Int) {
            elapsedMs += seconds * 1000L
            odometerKm += speedKmh * seconds / 3600.0
        }

        fun runUntilSegment(seconds: Int): MlSegment? = advance(seconds)
    }
}
