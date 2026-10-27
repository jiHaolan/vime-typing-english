package com.vime.ime.data

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.vime.ime.R

object ThemePref {
    val skinNames = listOf("靛蓝", "竹青", "暖杏", "樱粉", "石墨", "海蓝", "自定义")
    private val appStyles = intArrayOf(R.style.ImeThemeSkin0, R.style.ImeThemeSkin1, R.style.ImeThemeSkin2, R.style.ImeThemeSkin3, R.style.ImeThemeSkin4, R.style.ImeThemeSkin5)
    private val keyboardStyles = intArrayOf(R.style.ImeKeyboardThemeSkin0, R.style.ImeKeyboardThemeSkin1, R.style.ImeKeyboardThemeSkin2, R.style.ImeKeyboardThemeSkin3, R.style.ImeKeyboardThemeSkin4, R.style.ImeKeyboardThemeSkin5)
    fun skin(context: Context) = context.getSharedPreferences("vime_appearance", Context.MODE_PRIVATE).getInt("skin", 0).coerceIn(0, 6)
    fun setSkin(context: Context, index: Int) {
        context.getSharedPreferences("vime_appearance", Context.MODE_PRIVATE).edit().putInt("skin", index.coerceIn(0, 6)).apply()
    }
    fun signature(context: Context) = "${mode(context)}:${skin(context)}:${CustomTheme.version(context)}"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    fun mode(context: Context) = context.getSharedPreferences("vime_appearance", Context.MODE_PRIVATE)
        .getString("theme", SYSTEM).let { if (it in listOf(LIGHT, DARK)) it!! else SYSTEM }
    fun set(context: Context, value: String) {
        require(value in listOf(SYSTEM, LIGHT, DARK))
        context.getSharedPreferences("vime_appearance", Context.MODE_PRIVATE).edit().putString("theme", value).apply()
    }
    fun keyboardContext(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        val night = when(mode(context)) {
            LIGHT -> Configuration.UI_MODE_NIGHT_NO
            DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return ContextThemeWrapper(context.createConfigurationContext(config), keyboardStyles[skin(context).coerceAtMost(5)])
    }
    fun apply(activity: AppCompatActivity) {
        activity.delegate.localNightMode = when(mode(activity)) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        activity.setTheme(appStyles[skin(activity).coerceAtMost(5)])
    }
}
