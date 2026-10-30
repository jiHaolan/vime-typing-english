package com.vime.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.vime.ime.engine.VoiceSessionGate
import com.vime.ime.voice.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import kotlin.math.sqrt

/** Explicit network backends. Records only during the held gesture. */
internal class CloudVoiceController(private val context: Context,
    private val state: (String?, Boolean, Float) -> Unit, private val hint: (String) -> Unit) {
    private class Session(val token: Long) {
        @Volatile var cancelled = false
        @Volatile var released = false
        val transport = VoiceTransport()
        val finish = java.util.concurrent.CountDownLatch(1)
    }
    private val gate = VoiceSessionGate()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    var active = false; private set
    init { File(context.cacheDir, "voice").listFiles()?.forEach { it.delete() } }
    fun cancel() {
        gate.cancel(); active = false
        session?.let { it.cancelled = true; it.released = true; it.finish.countDown(); it.transport.cancel() }; session = null
        state(null, false, 0f)
    }
    fun close() { cancel(); worker.shutdownNow() }
    fun release() {
        session?.let { if (!it.released) { it.released = true; it.finish.countDown(); state("正在联网识别…", false, 0f) } }
    }
    fun start(commit: (String) -> Unit) {
        cancel()
        val config = if (VoiceSettings.mode(context) == VoiceSettings.HAOLIVE) VoiceSettings.haoLive(context) else VoiceSettings.read(context)
        if (!config.rawWav && config.key.isBlank()) { hint("自定义联网服务尚未配置，请在语音输入设置中填写密钥，或切回手机语音"); return }
        try { VoiceProtocol.validate(config, BuildConfig.DEBUG) }
        catch (e: IllegalArgumentException) { hint(e.message ?: "语音设置有误"); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hint("授权后返回输入框，按住空格2秒说话"); return
        }
        val s = Session(gate.start()); session = s; active = true
        val maxBytes = if (config.rawWav) VoiceProtocol.HAOLIVE_MAX_BYTES else VoiceProtocol.MAX_BYTES
        fun diagnostic(outcome: String) = VoiceDiagnostics.record(context,
            if (config.rawWav) "haolive" else "cloud", if (config.rawWav) "电脑语音 · X-ASR" else "自定义联网服务", "中文 / English", outcome)
        diagnostic("正在录音")
        state("正在录音 · 松手结束", true, 0f)
        worker.submit {
            var file: File? = null
            try {
                val dir = File(context.cacheDir, "voice").apply { mkdirs() }
                val recording = File.createTempFile("record-", ".wav", dir); file = recording
                val min = AudioRecord.getMinBufferSize(VoiceProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0) { "麦克风不支持当前录音格式" }
                val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
                var bytes = 0
                try {
                    require(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风暂不可用，请关闭其他录音后重试" }
                    if (s.cancelled) return@submit
                    require(!s.released) { "录音太短，请按住空格2秒后继续说话" }
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
                    RandomAccessFile(recording, "rw").use { wav ->
                        wav.write(ByteArray(44)); val chunk = ShortArray(1024)
                        while (!s.cancelled && !s.released && bytes < maxBytes) {
                            val count = recorder.read(chunk, 0, minOf(chunk.size, (maxBytes - bytes) / 2), AudioRecord.READ_BLOCKING)
                            require(count > 0) { "麦克风录音中断，请重试" }
                            val pcm = ByteArray(count * 2); var energy = 0.0
                            repeat(count) { i -> pcm[i * 2] = chunk[i].toByte(); pcm[i * 2 + 1] = (chunk[i].toInt() shr 8).toByte(); energy += chunk[i].toDouble() * chunk[i] }
                            wav.write(pcm); bytes += pcm.size
                            val rms = (sqrt(energy / count) / 1200).coerceIn(0.0, 10.0).toFloat()
                            main.post { if (gate.accepts(s.token) && !s.released) state("正在录音 · 松手结束", true, rms) }
                        }
                        val header = java.io.ByteArrayOutputStream(); VoiceProtocol.wavHeader(header, bytes)
                        wav.seek(0); wav.write(header.toByteArray())
                    }
                } finally { runCatching { recorder.stop() }; recorder.release() }
                if (s.cancelled) return@submit
                require(bytes >= VoiceProtocol.SAMPLE_RATE * 2 / 5) { "录音太短，请按住空格2秒后继续说话" }
                if (!s.released) {
                    main.post { if (gate.accepts(s.token) && !s.released) state("已达${maxBytes / (VoiceProtocol.SAMPLE_RATE * 2)}秒 · 松手识别", false, 0f) }
                    s.finish.await()
                }
                if (s.cancelled) return@submit
                s.released = true
                main.post { if (gate.accepts(s.token)) state("正在联网识别…", false, 0f) }
                val text = s.transport.transcribe(config, recording, BuildConfig.DEBUG)
                main.post { if (gate.accepts(s.token)) { diagnostic("识别完成"); cancel(); commit(text) } }
            } catch (e: Exception) {
                main.post { if (gate.accepts(s.token)) {
                    val message = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "语音输入失败" else if (config.rawWav) "无法连接电脑语音，请确认电脑服务已启动且 USB 测试连接正常" else "语音网络连接失败，请检查网络后重试"
                    diagnostic(message); cancel(); hint(message)
                } }
            } finally { file?.delete() }
        }
    }
}
