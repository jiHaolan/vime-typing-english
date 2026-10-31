package com.vime.ime.engine

import android.content.Context
import android.os.Looper
import com.vime.ime.data.Cand
import com.vime.ime.data.DictRepository
import com.vime.ime.data.InputLearningPrefs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal object RimeNative {
    init { System.loadLibrary("vime_rime") }
    external fun initialize(shared: String, user: String): String
    external fun candidates(raw: String, offset: Int, limit: Int): Array<String>
    external fun learn(raw: String, word: String, consumed: Int)
    external fun shutdown()
}

/** All native calls are serialized; heavy asset deployment is always off the UI thread. */
object RimeEngine {
    enum class State { LOADING, READY, FAILED }
    @Volatile var state = State.LOADING; private set
    @Volatile var version = ""; private set
    @Volatile var failure = ""; private set
    @Volatile var startupMs = 0L; private set
    @Volatile var datasetId = ""; private set
    @Volatile var sharedPath = ""; private set

    private fun checked(parent: File, name: String): File = File(parent, name).also {
        check(it.canonicalPath == it.absolutePath) { "Unexpected linked dictionary path" }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun removeGenerated(file: File) {
        check(file.canonicalPath == file.absolutePath) { "Unexpected linked generated cache" }
        if (file.isDirectory) checkNotNull(file.listFiles()).forEach(::removeGenerated)
        check(!file.exists() || file.delete()) { "Generated dictionary cache could not be removed" }
    }
    private fun writeAtomic(file: File, block: (FileOutputStream) -> Unit) {
        val temp = checked(checkNotNull(file.parentFile), file.name + ".tmp")
        FileOutputStream(temp).use { stream -> block(stream); stream.fd.sync() }
        check(temp.renameTo(file)) { "词典资源保存失败" }
    }
    @Synchronized fun initialize(context: Context) {
        if (state == State.READY) return
        check(Looper.myLooper() != Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val manifest = context.assets.open("rime/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            check(manifest.getString("format") == "vime-rime-runtime-1" && manifest.getBoolean("compiled"))
            val id = manifest.getString("dataset_id")
            require(id.matches(Regex("[a-f0-9]{64}")))
            val files = manifest.getJSONObject("files")
            val names = files.keys().asSequence().toList().sorted()
            val required = setOf("default.yaml", "vime_pinyin.schema.yaml", "vime_nine.schema.yaml",
                "build/default.yaml", "build/vime_pinyin.schema.yaml", "build/vime_nine.schema.yaml", "build/vime_pinyin.table.bin",
                "build/vime_pinyin.prism.bin", "build/vime_nine.prism.bin", "build/vime_pinyin.reverse.bin")
            require(names.toSet() == required)
            names.forEach { name ->
                require(name.matches(Regex("(?:build/)?[A-Za-z0-9_.-]+")) && ".." !in name)
                require(files.getString(name).matches(Regex("[a-f0-9]{64}")))
            }
            val canonical = names.joinToString(",", "{", "}") { "\"$it\":\"${files.getString(it)}\"" }
            val computedId = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            check(computedId == id) { "Dictionary dataset identity differs" }
            val root = checked(context.filesDir.canonicalFile, "rime").apply { mkdirs() }
            val datasets = checked(root, "datasets").apply { mkdirs() }
            val dataset = checked(datasets, id).apply { mkdirs() }
            val dir = checked(dataset, "shared").apply { mkdirs() }
            names.forEach { name ->
                val parent = if (name.startsWith("build/")) checked(dir, "build").apply { mkdirs() } else dir
                val dest = checked(parent, name.substringAfterLast('/'))
                val expected = files.getString(name)
                val current = if (dest.isFile) hash(dest) else ""
                if (current != expected) {
                    writeAtomic(dest) { stream -> context.assets.open("rime/$name").use { it.copyTo(stream) } }
                    check(hash(dest) == expected) { "Dictionary asset hash differs" }
                }
            }
            val user = checked(root, "user").apply { mkdirs() }
            val marker = checked(root, "active-dataset.txt")
            val previous = if (marker.isFile && marker.length() <= 128) marker.readText().trim() else ""
            if (previous != id) {
                RimeNative.shutdown()
                // This is Rime's generated cache. Root user dictionaries, custom
                // phrases/configurations and the learning database are retained.
                removeGenerated(checked(user, "build"))
            }
            version = RimeNative.initialize(dir.absolutePath, user.absolutePath)
            writeAtomic(marker) { it.write(id.toByteArray(Charsets.UTF_8)) }
            datasetId = id; sharedPath = dir.absolutePath
            // Retire only app-generated public assets after the replacement is
            // verified and active. Cleanup failure must not disable a ready IME.
            runCatching {
                removeGenerated(checked(root, "shared"))
                datasets.listFiles()?.filter { it.name != id && it.name.matches(Regex("[a-f0-9]{64}")) }
                    ?.forEach(::removeGenerated)
            }
            startupMs = android.os.SystemClock.elapsedRealtime() - started
            state = State.READY; failure = ""
        } catch (e: Throwable) {
            runCatching { RimeNative.shutdown() }
            state = State.FAILED; failure = e.javaClass.simpleName
        }
    }
    fun query(raw: String, offset: Int = 0, limit: Int = 12): List<Cand> {
        if (state != State.READY || raw.length !in 1..128 ||
            !(raw.all { it in 'a'..'z' || it == '\'' } || raw.all { it in '2'..'9' })) return emptyList()
        return synchronized(this) { RimeNative.candidates(raw, offset.coerceAtLeast(0), limit.coerceIn(1, 256)).mapNotNull { row ->
            val p = row.split('\t'); if (p.size != 3) return@mapNotNull null
            val consumed = p[1].toIntOrNull() ?: return@mapNotNull null
            val used=consumed.coerceIn(1,raw.length)
            DictRepository.candidate(p[0],raw.take(used),used,true)
        } }
    }
    fun learn(raw: String, c: Cand) {
        if (state != State.READY || !c.fromRime || !InputLearningPrefs.choicesEnabled) return
        synchronized(this) {
            if (state == State.READY && InputLearningPrefs.choicesEnabled) RimeNative.learn(raw, c.word, c.consumed)
        }
    }
    /** Releases all native handles before erasing this app's learned Rime data. */
    @Synchronized fun resetPersonalization(context: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        state = State.LOADING
        try {
            RimeNative.shutdown()
            val root = context.filesDir.canonicalFile
            val user = File(root, "rime/user")
            fun remove(file: File) {
                check(file.canonicalPath == file.absolutePath) { "Unexpected linked user-data path" }
                if (file.isDirectory) checkNotNull(file.listFiles()).forEach(::remove)
                check(!file.exists() || file.delete()) { "User data could not be removed" }
            }
            // Canonical root anchors every child; no symlink traversal or shared dictionary deletion.
            remove(user)
            version = ""; failure = ""
        } catch (error: Throwable) {
            state = State.FAILED; failure = error.javaClass.simpleName
            throw error
        }
        initialize(context)
        return state == State.READY
    }
    fun label() = when (state) {
        State.READY -> "智能拼音 · Rime $version"
        State.LOADING -> "智能拼音词典准备中 · 暂用基础词库"
        State.FAILED -> "智能拼音未启用（$failure）· 当前为基础词库"
    }
}
