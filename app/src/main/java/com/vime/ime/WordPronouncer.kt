package com.vime.ime

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.vime.ime.engine.EnglishGloss
import java.util.Locale

/** Lazy, lifecycle-owned speech. Only the explicitly chosen dictionary sense goes to the phone's TTS. */
class WordPronouncer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var serial = 0L
    private var engineSerial = 0L
    private var timeout: Runnable? = null
    private var pending: (() -> Unit)? = null
    private var update: ((String) -> Unit)? = null
    private var utterance: String? = null

    fun speak(english: String, status: (String) -> Unit) {
        if (closed) return
        stop()
        val text = EnglishGloss.primary(english).take(240)
        if (text.isBlank() || text.none { it in 'a'..'z' || it in 'A'..'Z' }) {
            status("暂无英文发音"); return
        }
        update = status
        val token = serial
        status("正在准备发音…")
        timeout = Runnable { if (serial == token && !closed) fail("发音超时 · 请检查手机语音引擎") }
            .also { main.postDelayed(it, 45000) }
        val play = {
            if (!closed && serial == token) play(text)
        }
        if (ready) { play(); return }
        pending = play
        if (engine != null) return
        val initId = ++engineSerial
        try {
            engine = TextToSpeech(context.applicationContext) { result -> main.post {
                if (!closed && initId == engineSerial) {
                    if (result == TextToSpeech.SUCCESS) {
                        ready = true
                        engine?.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(id: String?) = event(id, "正在发音…")
                            override fun onDone(id: String?) = event(id, "再次播放英文")
                            @Deprecated("API callback")
                            override fun onError(id: String?) = event(id, "发音失败 · 请检查语音包", true)
                            override fun onError(id: String?, errorCode: Int) = event(id, "发音失败 · 请检查语音包", true)
                        })
                        pending?.also { pending = null; it() }
                    } else fail("手机语音引擎未就绪 · 请检查发音设置")
                }
            } }
        } catch (_: Exception) { fail("手机没有可用语音引擎 · 请检查发音设置") }
    }

    private fun play(text: String) {
        val tts = engine ?: return
        try {
            val available = tts.setLanguage(Locale.US)
            if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
                fail("缺少英语语音包 · 请打开发音设置"); return
            }
            tts.setSpeechRate(0.85f)
            utterance = "vime-word-$serial"
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.ERROR)
                fail("无法播放 · 请检查手机语音引擎")
        } catch (_: Exception) { fail("无法播放 · 请检查手机语音引擎") }
    }
    private fun event(id: String?, label: String, error: Boolean = false) { main.post {
        if (!closed && id != null && id == utterance) {
            update?.invoke(label)
            if (label == "再次播放英文" || error) { timeout?.let { main.removeCallbacks(it) }; timeout = null }
            if (error) Toast.makeText(context, label, Toast.LENGTH_LONG).show()
        }
    } }
    private fun fail(message: String) {
        engineSerial++; utterance = null
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        pending = null; ready = false
        engine?.shutdown(); engine = null
        update?.invoke(message)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    fun stop() {
        serial++; pending = null; utterance = null
        timeout?.let { main.removeCallbacks(it) }; timeout = null
        engine?.stop()
        update?.invoke("播放英文发音"); update = null
    }
    fun close() { stop(); closed = true; engine?.shutdown(); engine = null; main.removeCallbacksAndMessages(null) }

    companion object {
        fun openSettings(context: Context) {
            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .recoverCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(context, "请在系统设置中搜索“文字转语音”", Toast.LENGTH_LONG).show() }
        }
    }
}
