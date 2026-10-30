package com.vime.ime.data

import android.content.Context
import android.os.Looper

/** Cached flags: checking whether input may be learned never reads disk. */
object InputLearningPrefs {
    @Volatile var choicesEnabled = true; private set
    @Volatile var exposureEnabled = true; private set
    private var initialized = false
    @Synchronized fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences("input_learning", Context.MODE_PRIVATE)
        choicesEnabled = prefs.getBoolean("choices_enabled", true)
        exposureEnabled = prefs.getBoolean("exposure_enabled", true)
        initialized = true
    }
    /** InputEngine owns the native-learning barrier around this operation. */
    internal fun saveChoices(context: Context, enabled: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(context.getSharedPreferences("input_learning", 0).edit().putBoolean("choices_enabled", enabled).commit())
        choicesEnabled = enabled
    }
    fun saveExposure(context: Context, enabled: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(context.getSharedPreferences("input_learning", 0).edit().putBoolean("exposure_enabled", enabled).commit())
        exposureEnabled = enabled
    }
}
