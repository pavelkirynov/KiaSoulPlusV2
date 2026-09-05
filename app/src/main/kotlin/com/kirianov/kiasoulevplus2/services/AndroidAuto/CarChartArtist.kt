// ====================================================================================
// КАРТИНКА НА ВЕСЬ ЕКРАН (CarChartArtist)
//
// Перекладає готовий опис із CarChartModel у пікселі. Жодних рішень тут немає: що
// саме показувати, вирішено в моделі, яка перевіряється тестами без Android.
//
// ДВА ЧИСЛА, ЯКІ ВИЗНАЧИЛИ ВСЕ РЕШТА.
//
// Розмір. Обкладинка їде до хоста через Binder разом із рештою метаданих, а там
// межа близько мегабайта на посилку. ARGB_8888 при 560 точках коштував би 1.25 МБ
// і посилка просто не доїхала б — а це не «трохи гірша картинка», а порожній екран
// плеера. Тому RGB_565: прозорість обкладинці не потрібна взагалі, зате байтів
// удвічі менше, і ті самі 560 точок коштують 613 КБ із запасом.
//
// Частота. Кожне оновлення метаданих — привід хостові перемалювати екран, і на
// балакучий сервіс він ображається. Тому картинка перемальовується лише тоді, коли
// змінився сам опис: у ньому числа вже заокруглені, тож дрібне тремтіння показників
// до нього не доходить.
// ====================================================================================

package com.kirianov.kiasoulevplus2.services.AndroidAuto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path

object CarChartArtist {

    /** Сторона картинки, точок. Див. пояснення про Binder у шапці файлу. */
    const val SIZE = 560

    private var cachedKey: CarChart? = null
    private var cached: Bitmap? = null

    /** Картинка для опису. Той самий опис двічі не малюється. */
    fun bitmapOf(chart: CarChart): Bitmap {
        cached?.let { if (cachedKey == chart) return it }
        val fresh = draw(chart)
        cachedKey = chart
        cached = fresh
        return fresh
    }

    private fun draw(chart: CarChart): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(COLOR_BACKGROUND)

        if (!chart.hasCurves) {
            drawMessage(canvas, chart)
            return bitmap
        }

        val right = plotRight(chart)
        drawGrid(canvas, chart, right)
        chart.series.forEach { drawSeries(canvas, it, right) }
        drawTickLabels(canvas, chart, right)
        return bitmap
    }

    /**
     * Порожнє полотно з поясненням. Картинка без жодного слова читалася б як
     * несправність, а не як «замірів ще немає».
     */
    private fun drawMessage(canvas: Canvas, chart: CarChart) {
        val paint = textPaint(SIZE * 0.060f, COLOR_CAPTION)
        var y = SIZE * 0.40f
        wrap(chart.message, paint, SIZE - 2 * PAD).forEach { line ->
            canvas.drawText(line, PAD, y, paint)
            y += paint.textSize * 1.4f
        }
    }

    private fun drawGrid(canvas: Canvas, chart: CarChart, right: Float) {
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GRID
            strokeWidth = SIZE * 0.004f
        }
        chart.leftTicks.forEach { tick ->
            val y = yPixel(tick.at)
            canvas.drawLine(plotLeft(), y, right, y, grid)
        }
        chart.bottomTicks.forEach { tick ->
            val x = xPixel(tick.at, right)
            canvas.drawLine(x, plotTop(), x, plotBottom(), grid)
        }
    }

    private fun drawSeries(canvas: Canvas, series: ChartSeries, right: Float) {
        if (series.points.size < 2) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (series.axis == ChartSeries.Axis.Left) COLOR_ENERGY else COLOR_VOLTS
            strokeWidth = SIZE * 0.014f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (series.dashed) {
                pathEffect = DashPathEffect(floatArrayOf(SIZE * 0.02f, SIZE * 0.02f), 0f)
            }
        }

        val path = Path()
        series.points.forEachIndexed { index, point ->
            val x = xPixel(point.x, right)
            val y = yPixel(point.y)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawTickLabels(canvas: Canvas, chart: CarChart, right: Float) {
        val left = textPaint(SIZE * 0.055f, COLOR_ENERGY).apply { textAlign = Paint.Align.RIGHT }
        chart.leftTicks.forEach { tick ->
            canvas.drawText(tick.text, plotLeft() - SIZE * 0.012f, yPixel(tick.at) + left.textSize / 3f, left)
        }

        val voltLabel = textPaint(SIZE * 0.055f, COLOR_VOLTS)
        chart.rightTicks.forEach { tick ->
            canvas.drawText(
                tick.text,
                right + SIZE * 0.012f,
                yPixel(tick.at) + voltLabel.textSize / 3f,
                voltLabel,
            )
        }

        val bottom = textPaint(SIZE * 0.055f, COLOR_CAPTION).apply { textAlign = Paint.Align.CENTER }
        chart.bottomTicks.forEach { tick ->
            canvas.drawText(tick.text, xPixel(tick.at, right), plotBottom() + SIZE * 0.055f, bottom)
        }

        val units = textPaint(SIZE * 0.050f, COLOR_CAPTION)
        canvas.drawText(chart.leftUnit, PAD, plotTop() - SIZE * 0.015f, units)
        if (chart.rightUnit.isNotEmpty()) {
            units.textAlign = Paint.Align.RIGHT
            canvas.drawText(chart.rightUnit, SIZE - PAD, plotTop() - SIZE * 0.015f, units)
        }
        units.textAlign = Paint.Align.CENTER
        canvas.drawText("% шкали", SIZE / 2f, SIZE - SIZE * 0.015f, units)
    }

    // --- Геометрія полотна ------------------------------------------------------

    /**
     * Полотно займає майже весь квадрат, і заголовка на ньому немає.
     *
     * ЧОМУ. Хост і так друкує назву та підпис поруч із обкладинкою — це заголовок
     * «треку» й «виконавця». Малювати їх ще раз усередині картинки означало
     * витрачати чверть висоти на другий примірник того самого тексту. А обкладинка
     * на машинному екрані виявилася маленькою: там кожен піксель на рахунку.
     */
    private fun plotLeft() = SIZE * 0.15f
    private fun plotTop() = SIZE * 0.10f
    private fun plotBottom() = SIZE * 0.86f

    /** Праворуч лишаємо місце під підписи напруги, лише коли вони є. */
    private fun plotRight(chart: CarChart) =
        if (chart.rightTicks.isEmpty()) SIZE * 0.95f else SIZE * 0.85f

    private fun xPixel(share: Double, right: Float): Float =
        plotLeft() + (right - plotLeft()) * share.toFloat().coerceIn(0f, 1f)

    private fun yPixel(share: Double): Float =
        plotBottom() - (plotBottom() - plotTop()) * share.toFloat().coerceIn(0f, 1f)

    private fun textPaint(size: Float, argb: Int, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb
            textSize = size
            isFakeBoldText = bold
        }

    /** Перенос по словах: рядок, обрізаний посередині, гірший за дрібніший шрифт. */
    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        text.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > width && line.isNotEmpty()) {
                lines += line.toString()
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private val PAD = SIZE * 0.05f

    private val COLOR_BACKGROUND = Color.rgb(18, 20, 24)
    private val COLOR_TEXT = Color.WHITE
    private val COLOR_CAPTION = Color.rgb(170, 176, 186)
    private val COLOR_GRID = Color.rgb(48, 52, 60)
    private val COLOR_ENERGY = Color.rgb(126, 217, 87)
    private val COLOR_VOLTS = Color.rgb(120, 180, 255)
}
