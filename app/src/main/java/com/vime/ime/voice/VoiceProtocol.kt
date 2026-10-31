package com.vime.ime.voice

import org.json.JSONObject
import java.io.OutputStream
import java.net.URI

object VoiceProtocol {
    const val ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
    const val MODEL = "FunAudioLLM/SenseVoiceSmall"
    const val HOLD_MS = 500L
    const val SAMPLE_RATE = 16000
    const val MAX_BYTES = SAMPLE_RATE * 2 * 60
    const val HAOLIVE_MAX_BYTES = SAMPLE_RATE * 2 * 30
    fun validateHaoLive(endpoint: String, debug: Boolean = false) {
        val uri = try { URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException("服务地址格式有误") }
        val local = debug && uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        require((uri.scheme == "https" || local) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) { "服务地址须使用 HTTPS，且不含账号、查询参数或片段" }
        require(uri.path == "/v1/transcribe") { "HaoLive 地址应以 /v1/transcribe 结尾" }
    }
    fun validate(config: VoiceConfig, debug: Boolean) {
        if (config.rawWav) validateHaoLive(config.endpoint, debug)
        else validate(config.endpoint, config.model, config.key, debug)
    }
    fun validate(endpoint: String, model: String, key: String, debug: Boolean = false) {
        val uri = try { URI(endpoint) } catch (_: Exception) { throw IllegalArgumentException("服务地址格式有误") }
        val local = debug && uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost")
        require((uri.scheme == "https" || local) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) { "服务地址须使用 HTTPS，且不含账号、查询参数或片段" }
        require(model.matches(Regex("[A-Za-z0-9._/-]{1,160}"))) { "请填写正确的模型名称" }
        require(key.isNotBlank() && key.length <= 4096 && key.all { it.code in 33..126 }) { "请填写有效 API 密钥" }
    }
    fun transcript(json: String): String {
        val text = (JSONObject(json).opt("text") as? String).orEmpty().trim()
        require(text.isNotEmpty()) { "未识别到文字，请再说一次" }
        require(text.length <= 4000) { "语音文字过长，请分段输入" }
        return text
    }
    fun error(code: Int) = when (code) {
        401, 403 -> "语音密钥无效或无权限，请在自定义联网服务中检查"
        402 -> "语音服务额度不足，请检查服务账户"
        429 -> "语音服务请求过多，请稍后重试"
        in 500..599 -> "语音服务暂不可用，请稍后重试"
        else -> "语音服务未完成请求（$code），请检查地址、模型和音频支持"
    }
    fun wavHeader(out: OutputStream, bytes: Int) {
        require(bytes in 0..MAX_BYTES && bytes % 2 == 0)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le(n: Int, count: Int) { repeat(count) { out.write(n ushr (it * 8) and 255) } }
        ascii("RIFF"); le(bytes + 36, 4); ascii("WAVEfmt "); le(16, 4); le(1, 2); le(1, 2)
        le(SAMPLE_RATE, 4); le(SAMPLE_RATE * 2, 4); le(2, 2); le(16, 2); ascii("data"); le(bytes, 4)
    }
}
