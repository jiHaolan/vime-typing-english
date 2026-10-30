package com.vime.ime.voice

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One explicit recording, one request; never redirects credentials or retries an upload. */
class VoiceTransport {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; connection?.disconnect() }
    fun transcribe(config: VoiceConfig, file: File, debug: Boolean): String {
        VoiceProtocol.validate(config, debug)
        val maxBytes = if (config.rawWav) VoiceProtocol.HAOLIVE_MAX_BYTES else VoiceProtocol.MAX_BYTES
        check(file.length() in 45..(maxBytes + 44).toLong()) { "录音文件有误或超过时长限制" }
        val boundary = "vime-" + UUID.randomUUID()
        val prefix = ("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n${config.model}\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n").toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val conn = URL(config.endpoint).openConnection() as HttpURLConnection; connection = conn
        try {
            if (cancelled) throw IOException("cancelled")
            conn.requestMethod = "POST"; conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000; conn.readTimeout = 30000; conn.doOutput = true
            if (config.rawWav) {
                conn.setRequestProperty("Content-Type", "audio/wav")
                conn.setFixedLengthStreamingMode(file.length())
                conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            } else {
                conn.setRequestProperty("Authorization", "Bearer ${config.key}")
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.setFixedLengthStreamingMode(prefix.size + file.length() + suffix.size)
                conn.outputStream.use { out -> out.write(prefix); file.inputStream().use { it.copyTo(out) }; out.write(suffix) }
            }
            if (cancelled) throw IOException("cancelled")
            val code = conn.responseCode
            if (code != 200) throw IllegalArgumentException(if (config.rawWav) "电脑语音服务未完成请求（$code），请检查服务是否就绪" else VoiceProtocol.error(code))
            val bytes = conn.inputStream.use { it.readBytesBounded(65536) }
            if (cancelled) throw IOException("cancelled")
            return VoiceProtocol.transcript(String(bytes, Charsets.UTF_8))
        } finally { conn.disconnect(); connection = null }
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "语音服务返回内容过长" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
