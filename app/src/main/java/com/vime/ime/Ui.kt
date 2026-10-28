package com.vime.ime

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat

/** Shared native surfaces and controls. No animation or layout changes in the input path. */
object Ui {
    fun dp(view: View, n: Int) = (view.resources.displayMetrics.density * n).toInt()
    fun window(activity: Activity) {
        activity.window.decorView.post { com.vime.ime.data.CustomTheme.apply(activity.window.decorView) }
        activity.window.statusBarColor = activity.uiColor(R.color.panel_bg)
        activity.window.navigationBarColor = activity.uiColor(R.color.panel_bg)
        val light = androidx.core.graphics.ColorUtils.calculateLuminance(activity.uiColor(R.color.panel_bg)) > .5
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
    fun label(activity: Activity, value: String, size: Float = 15f, bold: Boolean = false, secondary: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; includeFontPadding = true
        setTextColor(activity.uiColor(if (secondary) R.color.ink_2 else R.color.ink))
        if (bold) typeface = Typeface.DEFAULT_BOLD
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
        setBackgroundResource(R.drawable.card_bg)
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

