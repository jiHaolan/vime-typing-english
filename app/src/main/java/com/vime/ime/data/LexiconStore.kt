package com.vime.ime.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream

object LexiconStore {
    private fun target(context: Context) = File(context.filesDir, "dictionary/active.vimelex")
    @Synchronized fun read(context: Context): LexiconPack? {
        val atomic = AtomicFile(target(context))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().use(LexiconPackCodec::read)
    }
    fun boundedBytes(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        input.use { while (true) {
            val n = it.read(buf); if (n < 0) break
            require(out.size() + n <= LexiconPackCodec.MAX_COMPRESSED) { "词典包超过16MB" }
            out.write(buf, 0, n)
        } }
        return out.toByteArray()
    }
    /** Validation and repository construction happen before committing. Existing pack survives failures. */
    @Synchronized fun install(context: Context, bytes: ByteArray, publish: (LexiconPack) -> (() -> Unit)) {
        require(bytes.size <= LexiconPackCodec.MAX_COMPRESSED)
        val pack = LexiconPackCodec.read(bytes.inputStream())
        val commitSnapshot = publish(pack)
        val file = target(context); file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        commitSnapshot()
    }
    @Synchronized fun remove(context: Context, publish: () -> (() -> Unit)) {
        val commitSnapshot = publish()
        AtomicFile(target(context)).delete()
        commitSnapshot()
    }
}
