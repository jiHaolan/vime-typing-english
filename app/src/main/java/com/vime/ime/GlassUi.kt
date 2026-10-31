package com.vime.ime

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.graphics.ColorUtils

/** Static light wells and neutral material. Never blurs text or captures another app. */
object GlassUi {
    fun scene(context: Context, keyboard: Boolean = false): Drawable {
        val color = context.uiColor(if (keyboard) R.color.kb_bg else R.color.panel_bg)
        return if (com.vime.ime.data.ThemePref.skin(context) == 6) ColorDrawable(color) else Scene(context, color)
    }
    fun surface(context: Context, radius: Float = 24f): Drawable =
        if (com.vime.ime.data.ThemePref.skin(context) == 6) android.graphics.drawable.GradientDrawable().apply {
            setColor(context.uiColor(R.color.surface)); cornerRadius = radius * context.resources.displayMetrics.density
            setStroke(context.resources.displayMetrics.density.toInt().coerceAtLeast(1), context.uiColor(R.color.line))
        } else Surface(context, radius)
    fun interactive(context: Context): Drawable = RippleDrawable(
        ColorStateList.valueOf(context.uiColor(R.color.key_press)), surface(context, 18f), null)

    fun page(root: View) {
        root.background = scene(root.context)
        fun visit(view: View) {
            if (view !== root && (view is LinearLayout || view is ScrollView)) {
                val bg = view.background
                if (bg is ColorDrawable && bg.color == view.context.uiColor(R.color.panel_bg)) view.background = null
            }
            if (view.tag == "glass_card") {
                view.background = surface(view.context)
                // Platform elevation casts a filled shadow through translucent
                // surfaces. The material's rim supplies depth without that haze.
                view.elevation = 0f
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private class Scene(context: Context, private val base: Int) : Drawable() {
        private val night = ColorUtils.calculateLuminance(base) < .25
        private val accent = context.uiColor(R.color.acc)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var moon: Shader? = null
        private var warm: Shader? = null
        override fun onBoundsChange(bounds: Rect) {
            val w = bounds.width().coerceAtLeast(1).toFloat()
            val h = bounds.height().coerceAtLeast(1).toFloat()
            moon = RadialGradient(w * .92f, h * .12f, w * 1.1f,
                intArrayOf(if (night) 0x487C9CC4 else 0x487DA5D1, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            warm = RadialGradient(w * .06f, h * .68f, w * 1.05f,
                intArrayOf(ColorUtils.setAlphaComponent(accent, if (night) 17 else 12), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: Canvas) {
            paint.shader = null; paint.color = base; canvas.drawRect(bounds, paint)
            paint.color = Color.WHITE; paint.shader = moon; canvas.drawRect(bounds, paint)
            paint.shader = warm; canvas.drawRect(bounds, paint); paint.shader = null
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.OPAQUE
    }

    private class Surface(context: Context, radiusDp: Float) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val radius = radiusDp * density
        private val night = ColorUtils.calculateLuminance(context.uiColor(R.color.panel_bg)) < .25
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frame = RectF()
        private var shader: Shader? = null
        override fun onBoundsChange(bounds: Rect) {
            frame.set(bounds); frame.inset(.5f * density, .5f * density)
            shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.coerceAtLeast(bounds.top + 1).toFloat(),
                if (night) 0x1DFFFFFF else 0xCFFFFFFF.toInt(), if (night) 0x0CFFFFFF else 0x70FFFFFF,
                Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: Canvas) {
            paint.style = Paint.Style.FILL; paint.shader = shader; paint.color = Color.WHITE
            canvas.drawRoundRect(frame, radius, radius, paint)
            paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = density
            paint.color = if (night) 0x34FFFFFF else 0xE6FFFFFF.toInt()
            canvas.drawRoundRect(frame, radius, radius, paint)
            // Directional top reflection is cached with the material, independent of typing.
            paint.color = if (night) 0x4CFFFFFF else 0xFFFFFFFF.toInt()
            canvas.drawLine(frame.left + radius, frame.top, frame.right - radius, frame.top, paint)
            paint.style = Paint.Style.FILL
        }
        override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
