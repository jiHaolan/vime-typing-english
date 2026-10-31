package com.vime.ime.engine

import com.vime.ime.data.*
import android.content.Context
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

object InputEngine {
    private val learningLock = ReentrantLock()
    private val resetting = AtomicBoolean(false)
    private val learningEpoch = AtomicLong()
    /** Explicit custom phrases precede engine results; imported dictionaries cannot flood the first slots. */
    fun query(raw: String, nine: Boolean, offset: Int = 0, limit: Int = 12, personalized: Boolean = true): List<Cand> {
        val manual = if (personalized) PersonalLexicon.query(raw, nine) else emptyList()
        val shortcuts = if (!nine) InternetPhrases.query(raw) else emptyList()
        val front = (manual + shortcuts).distinctBy { it.word }
        val start = offset.coerceAtLeast(0)
        val selected = front.drop(start).take(limit)
        val remaining = limit - selected.size
        if (remaining <= 0) return selected
        val engineStart = (start - front.size).coerceAtLeast(0)
        val ready = RimeEngine.state == RimeEngine.State.READY
        // A fixed head makes ordering independent of requested page/limit. Decode
        // and enrich only64 native candidates, not the whole dictionary per touch.
        val base = if (ready) RimeEngine.query(raw, 0, 64) else
            if (nine) DictRepository.t9Candidates(raw, 100) else DictRepository.candidates(raw, 100)
        val head = EverydayCandidateOrder.rank(
            (base + DictRepository.optionalCandidates(raw,nine)).distinctBy { it.word },
            DictRepository::wordFrequency,
            { word -> if (personalized) PersonalLexicon.count(raw,word) else 0 }
        ).filter { c -> front.none { it.word == c.word } }
        val native = head.drop(engineStart).take(remaining).toMutableList()
        if (ready && native.size < remaining) {
            val excluded = (front + head).map { it.word }.toMutableSet()
            var cursor = base.size
            var skipped = (engineStart - head.size).coerceAtLeast(0)
            while (native.size < remaining && cursor < 2048) {
                val batch = RimeEngine.query(raw,cursor,64)
                if (batch.isEmpty()) break
                cursor += batch.size
                for (candidate in batch) if (excluded.add(candidate.word)) {
                    if (skipped > 0) skipped-- else if (native.size < remaining) native.add(candidate)
                }
            }
        }
        return (selected + native).distinctBy { it.word }
    }
    fun selected(raw: String, c: Cand, allowed: Boolean) {
        val epoch = learningEpoch.get()
        // A background reset must never make committing text wait for database teardown.
        if (!allowed || !InputLearningPrefs.choicesEnabled || resetting.get() || !learningLock.tryLock()) return
        try {
            if (!InputLearningPrefs.choicesEnabled || resetting.get() || epoch != learningEpoch.get()) return
            PersonalLexicon.record(raw, c, true)
            RimeEngine.learn(raw, c)
        } finally { learningLock.unlock() }
    }
    fun setChoiceLearning(context: Context, enabled: Boolean) {
        check(Looper.myLooper() != Looper.getMainLooper())
        learningLock.lock()
        try {
            // Wait for any accepted native update; late callers recheck the flag.
            synchronized(RimeEngine) { InputLearningPrefs.saveChoices(context, enabled) }
            learningEpoch.incrementAndGet()
        } finally { learningLock.unlock() }
    }
    fun resetPersonalization(context: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(resetting.compareAndSet(false, true)) { "正在重置选词习惯" }
        learningEpoch.incrementAndGet()
        learningLock.lock()
        try {
            val ready = RimeEngine.resetPersonalization(context)
            PersonalLexicon.clearManualCounts()
            return ready
        } finally { learningLock.unlock(); resetting.set(false) }
    }
}
