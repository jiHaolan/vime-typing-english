package com.vime.ime.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 中/英输入态（T15）。
 *
 * 为什么单独一个文件而不是塞进 LevelPref：两者虽然共享 `vime_prefs`，
 * 但语义完全不同 —— 分级是「背哪些词」，语言态是「现在打什么字」。
 * 混在一个 object 里，后面加快捷开关或按 App 记忆语言态时会互相踩。
 *
 * 默认中文：这是中文输入法，英文态是用户主动选的。
 */
object ModePref {

    const val ZH = "zh"
    const val EN = "en"

    private const val PREFS = "vime_prefs"
    private const val KEY = "ime_lang"

    @Volatile
    var isEnglish: Boolean = false
        private set

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        isEnglish = p.getString(KEY, ZH) == EN
        prefs = p
    }

    fun setEnglish(en: Boolean) {
        if (isEnglish == en) return
        isEnglish = en
        prefs?.edit()?.putString(KEY, if (en) EN else ZH)?.apply()
    }

    fun toggle(): Boolean {
        setEnglish(!isEnglish)
        return isEnglish
    }
}
