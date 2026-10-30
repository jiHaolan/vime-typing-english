package com.vime.ime.engine

import android.content.Context
import android.os.Looper
import com.vime.ime.data.Cand
import com.vime.ime.data.DictRepository
import org.json.JSONObject
import java.io.File
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
    @Synchronized fun initialize(context: Context) {
        if (state == State.READY) return
        check(Looper.myLooper() != Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val dir = File(context.filesDir, "rime/shared").apply { mkdirs() }
            val manifest = context.assets.open("rime/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
            val files = manifest.getJSONObject("files")
            files.keys().forEach { name ->
                require(name.matches(Regex("(?:build/)?[A-Za-z0-9_.-]+")) && ".." !in name)
                val dest = File(dir, name)
                dest.parentFile?.mkdirs()
                val expected = files.getString(name)
                val current = if (dest.isFile) MessageDigest.getInstance("SHA-256").digest(dest.readBytes()).joinToString("") { "%02x".format(it) } else ""
                if (current != expected) {
                    val temp = File(dir, "$name.tmp")
                    context.assets.open("rime/$name").use { input -> temp.outputStream().use(input::copyTo) }
                    check(temp.renameTo(dest)) { "词典资源保存失败" }
                }
            }
            val user = File(context.filesDir, "rime/user").apply { mkdirs() }
            version = RimeNative.initialize(dir.absolutePath, user.absolutePath)
            startupMs = android.os.SystemClock.elapsedRealtime() - started
            state = State.READY; failure = ""
        } catch (e: Throwable) {
            state = State.FAILED; failure = e.javaClass.simpleName
        }
    }
    fun query(raw: String, offset: Int = 0, limit: Int = 12): List<Cand> {
        if (state != State.READY || raw.length !in 1..128 ||
            !(raw.all { it in 'a'..'z' || it == '\'' } || raw.all { it in '2'..'9' })) return emptyList()
        return synchronized(this) { RimeNative.candidates(raw, offset.coerceAtLeast(0), limit.coerceIn(1, 256)).mapNotNull { row ->
            val p = row.split('\t'); if (p.size != 3) return@mapNotNull null
            val consumed = p[1].toIntOrNull() ?: return@mapNotNull null
            Cand(p[0], DictRepository.getEn(p[0]), DictRepository.getLvl(p[0]), consumed.coerceIn(1, raw.length), true)
        } }
    }
    fun learn(raw: String, c: Cand) {
        if (state != State.READY || !c.fromRime) return
        synchronized(this) { RimeNative.learn(raw, c.word, c.consumed) }
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
