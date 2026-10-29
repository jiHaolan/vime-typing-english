package com.vime.ime.voice

import android.content.Context
import android.os.Build
import com.vime.ime.BuildConfig

/** Last attempt metadata only. Never store audio, recognized words or editor contents. */
object VoiceDiagnostics {
    private fun prefs(c: Context) = c.getSharedPreferences("voice_diagnostics", Context.MODE_PRIVATE)
    fun record(c: Context, service: String, label: String, language: String, outcome: String, error: Int? = null) {
        prefs(c).edit().putString("service", service).putString("label", label).putString("language", language)
            .putString("outcome", outcome).apply {
                if (error == null) remove("error") else putInt("error", error)
            }.apply()
    }
    fun summary(c: Context): String? {
        val p = prefs(c)
        if (!p.contains("service")) return null
        val language = p.getString("language", "")
        val displayLanguage = when(language) { "zh-CN" -> "中文"; "en-US" -> "英语"; else -> language }
        val code = if (p.contains("error")) "（错误 ${p.getInt("error", 0)}）" else ""
        return "上次语音尝试\n识别服务：${p.getString("label", "手机语音")}\n识别语言：$displayLanguage" +
            "\n状态：${p.getString("outcome", "")}$code\n仅保存服务状态，不保存录音或识别文字。"
    }
    fun details(c: Context): String? = summary(c)?.let {
        "$it\n服务标识：${prefs(c).getString("service", "")}\n语言标识：${prefs(c).getString("language", "")}" +
            "\n输入法版本：${BuildConfig.VERSION_NAME}\n手机：${Build.MODEL}\nAndroid：${Build.VERSION.RELEASE}"
    }
}
