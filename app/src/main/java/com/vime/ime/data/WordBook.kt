package com.vime.ime.data

import android.content.Context
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import com.vime.ime.engine.ReviewSchedule
import java.util.concurrent.Executors

/** Explicit vocabulary only. Every instance shares one worker and transactional store. */
class WordBook(context: Context) {
    private val app = context.applicationContext
    data class Entry(val word: String, val english: String, val level: String, val savedAt: Long,
                     val progress: ReviewSchedule.Progress = ReviewSchedule.Progress(), val lastReviewedAt: Long = 0L)

    fun load(done: (Result<LearningArchive.Snapshot>)->Unit) = submit(done) { it.snapshot() }
    fun contains(word: String, done: (Result<Boolean>)->Unit) = submit(done) { it.contains(word) }
    fun save(word: String, english: String, level: String, done: (Result<Boolean>)->Unit) = submit(done) {
        it.save(Entry(word,english,level,System.currentTimeMillis()))
    }
    fun answer(expected: Entry, remembered: Boolean, done: (Result<Boolean>)->Unit) = submit(done) {
        it.answer(expected,remembered,System.currentTimeMillis())
    }
    fun remove(word: String, done: (Result<Boolean>)->Unit) = submit(done) { it.remove(word) }
    fun clear(done: (Result<Unit>)->Unit) = submit(done) { it.clear() }
    fun restore(data: LearningArchive.Snapshot, mode: LearningArchive.Restore, done: (Result<LearningArchive.ImportResult>)->Unit) = submit(done) { it.restore(data,mode) }
    private fun <T> submit(done: (Result<T>)->Unit, action: (LearningDatabase)->T) {
        worker.execute {
            val result = runCatching { action(database ?: LearningDatabase(app).also { database=it }) }
            main.post { done(result) }
        }
    }
    companion object {
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r,"vime-learning-io").apply { isDaemon=true } }
        private val main = Handler(Looper.getMainLooper())
        // Confined to the worker; all callers receive completed results on main.
        // LearningDatabase retains applicationContext exclusively, never an Activity or IME view.
        @SuppressLint("StaticFieldLeak")
        private var database: LearningDatabase? = null
    }
}
