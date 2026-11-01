package com.vime.ime.voice

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One pinned model in private storage. Imports never read keyboard or learning data. */
object OfflineModelStore {
    const val BYTES = 92_665_824L
    const val SHA256 = "3300f743cf34f686004cf600a1ea206c7eee50ecaa01e95b4aff418876f613c8"
    const val DOWNLOAD_URL = "https://huggingface.co/cstr/x-asr-zh-en-GGUF/resolve/acb1a95eac809719a2c86d1048471f96fc6444ad/x-asr-zh-en-q4_k.gguf?download=true"
    private val lock = Any()
    private var verifiedPath = ""
    private var verifiedModified = -1L
    private fun file(context: Context) = File(context.filesDir, "offline-voice/x-asr-zh-en-q4_k.gguf")
    private fun background() = check(Looper.myLooper() != Looper.getMainLooper()) { "Model I/O must run off the main thread" }
    fun present(context: Context) = file(context).let { model ->
        listOf(model, File(model.path + ".bak")).any { it.isFile && it.length() == BYTES }
    }

    fun importModel(context: Context, input: InputStream, checkCancelled: () -> Unit, progress: (Long) -> Unit) {
        background()
        synchronized(lock) {
            checkCancelled()
            val model = file(context)
            check(model.parentFile!!.isDirectory || model.parentFile!!.mkdirs()) { "无法创建模型目录" }
            val atomic = AtomicFile(model)
            val output = atomic.startWrite()
            var committed = false
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0) { "无法读取模型文件" }
                    total += count
                    require(total <= BYTES) { "模型文件过大，请选择指定的中英文模型" }
                    hash.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    progress(total)
                }
                require(total == BYTES && hash.digest().joinToString("") { "%02x".format(it) } == SHA256) {
                    "模型文件不匹配或未下载完整，请重新下载"
                }
                checkCancelled()
                atomic.finishWrite(output)
                committed = true
                check(model.isFile && model.length() == BYTES) { "模型未能保存，请重试" }
                verifiedPath = ""
                verifiedModified = -1
                // Trust the private on-disk file only after reading it back.
                withModel(context, checkCancelled) {}
            } catch (failure: Throwable) {
                if (!committed) atomic.failWrite(output)
                throw failure
            }
        }
    }

    fun <T> withModel(context: Context, checkCancelled: () -> Unit, action: (File) -> T): T {
        background()
        return synchronized(lock) {
            checkCancelled()
            val model = file(context)
            // AtomicFile also recovers an interrupted previous import, when applicable.
            val atomic = AtomicFile(model)
            require(model.exists() || File(model.path + ".bak").exists()) { "请先在离线语音设置中导入模型" }
            atomic.openRead().use { input ->
                require(model.length() == BYTES) { "离线模型不完整，请重新导入" }
                if (verifiedPath != model.absolutePath || verifiedModified != model.lastModified()) {
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(count > 0)
                        total += count
                        require(total <= BYTES) { "离线模型文件有误，请重新导入" }
                        hash.update(buffer, 0, count)
                    }
                    require(total == BYTES && hash.digest().joinToString("") { "%02x".format(it) } == SHA256) { "离线模型校验失败，请重新导入" }
                    verifiedPath = model.absolutePath
                    verifiedModified = model.lastModified()
                }
            }
            checkCancelled()
            action(model)
        }
    }

    fun remove(context: Context, checkCancelled: () -> Unit) {
        background()
        synchronized(lock) {
            checkCancelled()
            AtomicFile(file(context)).delete()
            val model = file(context)
            check(listOf(model, File(model.path + ".bak"), File(model.path + ".new")).none { it.exists() }) { "模型未能移除，请重试" }
            verifiedPath = ""
            verifiedModified = -1
        }
    }
}
