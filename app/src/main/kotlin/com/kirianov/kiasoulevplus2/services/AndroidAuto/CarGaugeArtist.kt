// ====================================================================================
// МАЛЮВАЛЬНИК ПРИЛАДІВ ДЛЯ ANDROID AUTO (CarGaugeArtist)
//
// Перекладає [CarGauge] — опис приладу числами — у картинку, яку хост поставить
// замість піктограми рядка. Більше нічого: що саме показувати, вирішено в
// CarMediaModel, під тестами. Тут лише пікселі.
//
// ЧОМУ ЦЕ ЄДИНА ЩІЛИНА. Медіа-браузер не дає власного вигляду: хост малює свій
// список. Але іконку рядка він бере як картинку й показує як є — отже, в неї
// можна вкласти прилад. Це не «свій екран», це рівно один квадрат на розділ.
//
// ПРО РОЗМІР. Картинка їде до хоста через Binder разом з усім списком, а там межа
// близько мегабайта на посилку. 192×192 — це 144 КБ; два прилади в корені дають
// під триста, і це з добрим запасом. Робити їх учетверо більшими означало б
// ризикувати тим, що список не доїде взагалі.
//
// ПРО ЧАСТОТУ. Кожне перемальовування — це ще й нове сповіщення хостові, а він на
// балакучий сервіс просто перестає відповідати. Тому числа в CarGauge уже
// заокруглені, а тут картинки кешуються: той самий прилад із тими самими числами
// малюється один раз.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

object CarGaugeArtist {

    /**
     * Сторона картинки, точок.
     *
     * Розмір упирається не в смак, а в Binder: усі картинки одного розділу їдуть
     * до хоста однією посилкою, а вона обмежена приблизно мегабайтом. При ARGB_8888
     * 256 точок коштують 256 КБ, і двох приладів кореня разом — пів мегабайта:
     * лишається запас. 320 точок коштували б уже 800 КБ на двох, і посилка стояла б
     * на межі — а зрив посилки означає порожню медіатеку, а не дрібну картинку.
     */
    const val SIZE = 256

    private val cache = LinkedHashMap<CarGauge, Bitmap>()

    /**
     * Картинка для приладу. Той самий прилад двічі не малюється: значення в ньому
     * уже заокруглені, тож кеш влучає майже завжди.
     */
    fun bitmapOf(gauge: CarGauge): Bitmap = cache.getOrPut(gauge) {
        trimCache()
        draw(gauge)
    }

    private fun trimCache() {
        while (cache.size >= MAX_CACHED) {
            val oldest = cache.keys.firstOrNull() ?: return
            cache.remove(oldest)
        }
    }

    private fun draw(gauge: CarGauge): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        when (gauge.kind) {
            CarGauge.Kind.Arc -> drawArc(canvas, gauge)
            CarGauge.Kind.Bar -> drawBar(canvas, gauge)
            // Без шкали малювати нічого: саме число й підпис. Порожня плитка тут
            // краща за трикутник із знаком оклику, який хост підставляє замість
            // відсутньої іконки, — той читається як несправність авто.
            CarGauge.Kind.Plain -> Unit
        }
        drawText(canvas, gauge)
        return bitmap
    }

    /**
     * Дуга заряду. Розімкнена знизу, як стрілочний прилад: так одразу видно, де в
     * неї початок і кінець, і повна дуга не плутається з порожньою.
     */
    private fun drawArc(canvas: Canvas, gauge: CarGauge) {
        val inset = SIZE * 0.12f
        val box = RectF(inset, inset, SIZE - inset, SIZE - inset)
        val stroke = SIZE * 0.10f

        val track = paint(COLOR_TRACK, stroke)
        val value = paint(colorForCharge(gauge.fill), stroke)

        canvas.drawArc(box, START_ANGLE, SWEEP_ANGLE, false, track)
        canvas.drawArc(box, START_ANGLE, (SWEEP_ANGLE * gauge.fill).toFloat(), false, value)
    }

    /**
     * Смуга потужності від центру. Нульова риска посередині лишається видимою
     * завжди: без неї смуга в нулі виглядала б як зламаний прилад.
     */
    private fun drawBar(canvas: Canvas, gauge: CarGauge) {
        val height = SIZE * 0.16f
        val top = SIZE * 0.18f
        val margin = SIZE * 0.10f
        val left = margin
        val right = SIZE - margin
        val middle = SIZE / 2f

        canvas.drawRect(left, top, right, top + height, paint(COLOR_TRACK, 0f).apply { style = Paint.Style.FILL })

        val half = (right - left) / 2f
        val end = middle + (half * gauge.fill).toFloat()
        val color = if (gauge.fill >= 0.0) COLOR_SPEND else COLOR_REGEN
        canvas.drawRect(
            minOf(middle, end),
            top,
            maxOf(middle, end),
            top + height,
            paint(color, 0f).apply { style = Paint.Style.FILL },
        )

        canvas.drawRect(
            middle - SIZE * 0.008f,
            top - SIZE * 0.03f,
            middle + SIZE * 0.008f,
            top + height + SIZE * 0.03f,
            paint(COLOR_TEXT, 0f).apply { style = Paint.Style.FILL },
        )
    }

    private fun drawText(canvas: Canvas, gauge: CarGauge) {
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textAlign = Paint.Align.CENTER
            textSize = SIZE * 0.22f
            isFakeBoldText = true
        }
        val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CAPTION
            textAlign = Paint.Align.CENTER
            textSize = SIZE * 0.11f
        }

        // Без шкали число живе саме по собі, тож ставимо його по центру плитки й
        // даємо крупніше: місця, яке займали дуга чи смуга, тут ніхто не займає.
        val plain = gauge.kind == CarGauge.Kind.Plain
        if (plain) value.textSize = SIZE * 0.30f

        fitWidth(value, gauge.label)
        fitWidth(caption, gauge.caption)

        canvas.drawText(gauge.label, SIZE / 2f, if (plain) SIZE * 0.52f else SIZE * 0.60f, value)
        canvas.drawText(gauge.caption, SIZE / 2f, if (plain) SIZE * 0.70f else SIZE * 0.76f, caption)
    }

    /**
     * Зменшити шрифт, поки рядок не влізе в плитку.
     *
     * Обрізаний підпис гірший за дрібний: «остання заря» на машинному екрані читач
     * дочитає як завгодно. А довжина рядка тут не під нашим контролем — вона
     * залежить від значення й від мови.
     */
    private fun fitWidth(paint: Paint, text: String) {
        if (text.isEmpty()) return
        val limit = SIZE * (1f - 2f * TEXT_MARGIN)
        var width = paint.measureText(text)
        while (width > limit && paint.textSize > MIN_TEXT_SIZE) {
            paint.textSize *= limit / width
            width = paint.measureText(text)
        }
    }

    private fun paint(argb: Int, stroke: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        strokeWidth = stroke
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Колір заряду: зелений, поки спокійно, і червоний, коли вже треба шукати розетку. */
    private fun colorForCharge(fill: Double): Int = when {
        fill <= 0.10 -> COLOR_LOW
        fill <= 0.25 -> COLOR_WARN
        else -> COLOR_OK
    }

    private const val START_ANGLE = 135f
    private const val SWEEP_ANGLE = 270f

    private const val MAX_CACHED = 24

    /** Поля з обох боків плитки, частка сторони. */
    private const val TEXT_MARGIN = 0.06f

    /** Дрібніше не зменшуємо: такий підпис однаково не прочитати в русі. */
    private const val MIN_TEXT_SIZE = 12f

    private val COLOR_TRACK = Color.argb(70, 255, 255, 255)
    private val COLOR_TEXT = Color.WHITE
    private val COLOR_CAPTION = Color.argb(180, 255, 255, 255)
    private val COLOR_OK = Color.rgb(126, 217, 87)
    private val COLOR_WARN = Color.rgb(255, 196, 61)
    private val COLOR_LOW = Color.rgb(255, 99, 71)
    private val COLOR_SPEND = Color.rgb(255, 149, 61)
    private val COLOR_REGEN = Color.rgb(126, 217, 87)
}
