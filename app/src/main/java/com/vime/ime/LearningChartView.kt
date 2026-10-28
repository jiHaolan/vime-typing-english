package com.vime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.vime.ime.engine.LearningStats

/** Accessible summaries and exact counts are also shown as text beside each chart. */
class LearningChartView(context: Context, private val days: List<LearningStats.Day>? = null,
    private val halfLife: Double = 1.0) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        val peak = days?.maxOfOrNull { it.count } ?: 0
        val step = maxOf(1, kotlin.math.ceil(peak / 4.0).toInt())
        val maximum = maxOf(1, (peak / step + 1) * step)
        val left = maxOf(40 * density, paint.measureText(if (days == null) "100%" else "$maximum") + 12 * density)
        val right = width - 12 * density
        val top = maxOf(20 * density, -paint.fontMetrics.top + 6 * density)
        val bottom = height - maxOf(34 * density, paint.fontSpacing + 12 * density)
        if (right <= left || bottom <= top) return
        paint.strokeWidth = density; paint.color = context.uiColor(R.color.line)
        val ticks = if (days == null) listOf(0, 50, 100) else (0..maximum step step).toList()
        val scale = if (days == null) 100 else maximum
        for (value in ticks) {
            val y = bottom - (bottom - top) * value / scale
            paint.color = context.uiColor(R.color.line)
            c.drawLine(left, y, right, y, paint)
            paint.color = context.uiColor(R.color.ink_2)
            paint.textAlign = Paint.Align.RIGHT
            c.drawText(if (days == null) "$value%" else "$value", left - 8 * density, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        paint.color = context.uiColor(R.color.ink_2)
        paint.textAlign = Paint.Align.RIGHT
        if (days == null) {
            val path = Path()
            val horizon = maxOf(7.0, halfLife * 2).coerceAtMost(60.0)
            for (i in 0..100) {
                val x = left + (right - left) * i / 100
                val y = bottom - (bottom - top) * LearningStats.retention(horizon * i / 100, halfLife).toFloat() / 100
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            paint.color = context.uiColor(R.color.acc_ink); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            c.drawPath(path, paint); paint.style = Paint.Style.FILL; paint.pathEffect = null
            paint.color = context.uiColor(R.color.ink_2); paint.textAlign = Paint.Align.LEFT
            c.drawText("复习后", left, height - 10 * density, paint)
            paint.textAlign = Paint.Align.CENTER; c.drawText("${horizon / 2} 天", (left + right) / 2, height - 10 * density, paint)
            paint.textAlign = Paint.Align.RIGHT; c.drawText("${horizon.toInt()} 天", right, height - 10 * density, paint)
        } else {
            val cell = (right - left) / 7
            days.forEachIndexed { i, day ->
                val x = left + cell * i
                paint.color = context.uiColor(R.color.acc_ink)
                val h = (bottom - top) * day.count / maximum
                c.drawRoundRect(x + cell * .19f, bottom - h, x + cell * .81f, bottom, 4 * density, 4 * density, paint)
                paint.color = context.uiColor(R.color.ink_2); paint.textAlign = Paint.Align.CENTER
                if (i % 3 == 0) c.drawText(java.text.SimpleDateFormat("M/d", java.util.Locale.getDefault()).format(java.util.Date(day.at)), x + cell / 2, height - 10 * density, paint)
                if (day.count > 0 && resources.configuration.fontScale <= 1.3f) c.drawText("${day.count}", x + cell / 2, bottom - h - 5 * density, paint)
            }
        }
    }
}
