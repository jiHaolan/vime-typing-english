package com.vime.ime.data

import android.content.Context
import android.content.res.Configuration
import com.vime.ime.engine.KeyboardTuning

object KeyboardPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("vime_ime", Context.MODE_PRIVATE)
    private fun key(c: Context, name: String) = name + if (c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "_land" else ""
    private fun value(c: Context, name: String, default: Int) = prefs(c).getInt(key(c, name), prefs(c).getInt(name, default))
    fun height(c: Context) = KeyboardTuning.height(value(c,"height_pct", (prefs(c).getFloat("kb_scale", 1f) * 100).toInt()))
    fun width(c: Context) = KeyboardTuning.width(value(c,"width_pct", 100))
    fun position(c: Context) = KeyboardTuning.position(value(c,"position_pct", 50))
    fun bottom(c: Context) = KeyboardTuning.bottom(value(c,"bottom_dp", 0))
    fun set(c: Context, h: Int = height(c), w: Int = width(c), p: Int = position(c), b: Int = bottom(c)) {
        prefs(c).edit().putInt(key(c,"height_pct"), KeyboardTuning.height(h))
            .putInt(key(c,"width_pct"), KeyboardTuning.width(w)).putInt(key(c,"position_pct"), KeyboardTuning.position(p)).putInt(key(c,"bottom_dp"), KeyboardTuning.bottom(b)).apply()
    }
    fun haptics(c: Context): Int {
        val pref = c.getSharedPreferences("vime_appearance", Context.MODE_PRIVATE)
        return KeyboardTuning.haptic(pref.getInt("haptic_level", if (pref.getBoolean("haptics", true)) 2 else 0))
    }
    fun setHaptics(c: Context, level: Int) {
        c.getSharedPreferences("vime_appearance", Context.MODE_PRIVATE).edit().putInt("haptic_level", KeyboardTuning.haptic(level)).putBoolean("haptics", level > 0).apply()
    }
    val hapticNames = listOf("关闭", "轻", "中", "强")
    fun advanced(c: Context) = prefs(c).getBoolean("haptic_advanced", false)
    fun duration(c: Context, long: Boolean) = prefs(c).getInt(if (long) "haptic_long_ms" else "haptic_ms", if (long) 32 else 16).coerceIn(1,100)
    fun amplitude(c: Context, long: Boolean) = prefs(c).getInt(if (long) "haptic_long_amp" else "haptic_amp", 125).coerceIn(1,255)
    fun advanced(c: Context, enabled: Boolean, ms: Int, amp: Int, longMs: Int, longAmp: Int) {
        prefs(c).edit().putBoolean("haptic_advanced", enabled).putInt("haptic_ms",ms.coerceIn(1,100)).putInt("haptic_amp",amp.coerceIn(1,255))
            .putInt("haptic_long_ms",longMs.coerceIn(1,100)).putInt("haptic_long_amp",longAmp.coerceIn(1,255)).apply()
    }
}
