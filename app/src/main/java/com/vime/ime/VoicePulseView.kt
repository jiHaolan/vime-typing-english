package com.vime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.provider.Settings
import android.util.AttributeSet
import android.view.View

/** Listening animation; amplitude comes from recognizer RMS. */
class VoicePulseView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    var listening = false
        set(value) { field = value; invalidate() }
    var rms = 0f
        set(value) { field = value.coerceIn(0f, 12f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = context.uiColor(R.color.acc)
        val animate = listening && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        val phase = if (animate) (android.os.SystemClock.uptimeMillis() % 1200) / 1200f * 6.283f else 0f
        for (i in 0..4) {
            val amount = if (listening) .2f + rms / 20f + (if (animate) .12f * kotlin.math.sin(phase + i) else 0f) else .15f
            val h = height * amount.coerceIn(.12f, .9f)
            val x = width * (.16f + i * .16f)
            canvas.drawRoundRect(x - width * .035f, (height - h) / 2, x + width * .035f, (height + h) / 2, 5f, 5f, paint)
        }
        if (animate && isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(60)
    }
    override fun onDetachedFromWindow() { listening = false; super.onDetachedFromWindow() }
}
