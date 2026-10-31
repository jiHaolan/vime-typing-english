package com.vime.ime

import android.app.Activity
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import kotlin.math.max

/** Shared native surfaces and controls. No animation or layout changes in the input path. */
object Ui {
    private data class WindowPadding(val left:Int,val top:Int,val right:Int,val bottom:Int)
    fun dp(view: View, n: Int) = (view.resources.displayMetrics.density * n).toInt()
    fun window(activity: Activity) {
        (activity.findViewById<ViewGroup>(android.R.id.content))?.getChildAt(0)?.let(GlassUi::page)
        activity.window.decorView.post { com.vime.ime.data.CustomTheme.apply(activity.window.decorView) }
        val edge = Build.VERSION.SDK_INT >= 30
        val background=activity.uiColor(R.color.panel_bg)
        // Insets expose the window background beneath transparent system bars.
        // A custom light palette can be active while the base theme is dark.
        activity.window.setBackgroundDrawable(ColorDrawable(background))
        WindowCompat.setDecorFitsSystemWindows(activity.window,!edge)
        activity.window.statusBarColor = if(edge) android.graphics.Color.TRANSPARENT else background
        activity.window.navigationBarColor = if(edge) android.graphics.Color.TRANSPARENT else background
        if(edge) {
            val content=activity.findViewById<View>(android.R.id.content)
            if(content != null && content.getTag(R.id.windowSafePadding)==null) {
                val base=WindowPadding(content.paddingLeft,content.paddingTop,content.paddingRight,content.paddingBottom)
                content.setTag(R.id.windowSafePadding,base)
                ViewCompat.setOnApplyWindowInsetsListener(content) { view,insets ->
                    val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
                    // IME includes the navigation area. Taking max avoids double bottom padding.
                    val bottom=base.bottom+max(bars.bottom,ime.bottom)
                    val changed=view.paddingLeft!=base.left+bars.left || view.paddingTop!=base.top+bars.top || view.paddingRight!=base.right+bars.right || view.paddingBottom!=bottom
                    view.setPadding(base.left+bars.left,base.top+bars.top,base.right+bars.right,bottom)
                    if(changed && ime.bottom>bars.bottom) view.doOnLayout {
                        // Keep the entire focused editor visible, including its
                        // final line and padding, after the viewport shrinks.
                        val focus=activity.currentFocus
                        if(focus is android.widget.EditText) focus.requestRectangleOnScreen(Rect(0,0,focus.width,focus.height),true)
                    }
                    insets
                }
            }
            if(content != null) ViewCompat.requestApplyInsets(content)
        }
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(background) > .5
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
    fun label(activity: Activity, value: String, size: Float = 15f, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; includeFontPadding = true
        setTextColor(activity.uiColor(if (secondary) R.color.ink_2 else R.color.ink))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(this, 3).toFloat(), 1f)
    }
    fun space(column: LinearLayout, height: Int) {
        column.addView(View(column.context), LinearLayout.LayoutParams(1, dp(column, height)))
    }
    fun heading(activity: Activity, column: LinearLayout, value: String, subtitle: String) {
        val title = label(activity, value, 24f, true)
        ViewCompat.setAccessibilityHeading(title, true)
        column.addView(title); space(column, 8)
        column.addView(label(activity, subtitle, 14f, secondary = true)); space(column, 20)
    }
    fun card(column: LinearLayout): LinearLayout = LinearLayout(column.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(this, 20), dp(this, 20), dp(this, 20), dp(this, 20))
        background = GlassUi.surface(context)
        elevation = 0f
        column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(column, 16) })
    }
    fun button(activity: Activity, value: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 15f; isAllCaps = false; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minimumWidth = 0; minWidth = 0; minHeight = dp(this, 52)
        setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12))
        setTextColor(activity.uiColor(if (primary) R.color.on_acc else R.color.ink))
        setBackgroundResource(if (primary) R.drawable.pill_primary_bg else R.drawable.pill_bg)
        backgroundTintList = null
        elevation = 0f; stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (activity.resources.displayMetrics.density * 8).toInt() }
        setOnClickListener { action() }
    }
    fun back(activity: Activity, value: String, action: () -> Unit) = button(activity, value, action = action).apply {
        setBackgroundResource(R.drawable.candidate_bg)
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        minHeight = dp(this, 48)
        setPadding(0, dp(this, 12), dp(this, 16), dp(this, 12))
    }
    fun section(activity: Activity, column: LinearLayout, title: String) {
        space(column, 12)
        column.addView(label(activity, title, 17f, true).also { ViewCompat.setAccessibilityHeading(it, true) })
        space(column, 8)
    }
    fun input(view: TextView) {
        view.setTextColor(view.context.uiColor(R.color.ink))
        view.setHintTextColor(view.context.uiColor(R.color.ink_2))
        view.setBackgroundResource(R.drawable.pill_bg)
        view.setPadding(dp(view, 16), dp(view, 14), dp(view, 16), dp(view, 14))
        view.minHeight = dp(view, 56)
        view.textSize = 16f
    }
}

