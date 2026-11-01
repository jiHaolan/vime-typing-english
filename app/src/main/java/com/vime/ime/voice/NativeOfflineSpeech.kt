package com.vime.ime.voice

/** Private JNI boundary: one CPU decode at a time; cancellation never commits text. */
object NativeOfflineSpeech {
    private val loaded by lazy { System.loadLibrary("vime_xasr") }
    private fun load() { loaded }
    fun create(): Long { load(); return nativeCreate().also { check(it != 0L) { "无法准备离线语音" } } }
    fun cancel(job: Long) { if (job != 0L) nativeCancel(job) }
    fun destroy(job: Long) { if (job != 0L) nativeDestroy(job) }
    fun activeJobsForChecks(): Int { load(); return nativeActiveJobs() }
    fun startedForChecks(job: Long): Boolean = nativeStarted(job)
    fun transcribe(job: Long, model: String, pcm: ShortArray): String? {
        load()
        require(pcm.size in 1..480_000) { "语音过长，请分段输入" }
        return nativeTranscribe(job, model, pcm)?.toString(Charsets.UTF_8)?.trim()
    }
    private external fun nativeCreate(): Long
    private external fun nativeCancel(job: Long)
    private external fun nativeDestroy(job: Long)
    private external fun nativeActiveJobs(): Int
    private external fun nativeStarted(job: Long): Boolean
    private external fun nativeTranscribe(job: Long, model: String, pcm: ShortArray): ByteArray?
}
