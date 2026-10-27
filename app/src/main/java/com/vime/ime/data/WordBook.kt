package com.vime.ime.data

import android.content.Context
import com.vime.ime.engine.ReviewSchedule
import com.vime.ime.engine.LearningStats
import org.json.JSONArray
import org.json.JSONObject

/** Explicitly saved vocabulary only. Never harvest chat text or clipboard contents. */
class WordBook(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vime_wordbook", Context.MODE_PRIVATE)
    data class Entry(val word: String, val english: String, val level: String, val savedAt: Long,
                     val progress: ReviewSchedule.Progress = ReviewSchedule.Progress(), val lastReviewedAt: Long = 0L)

    fun all(): List<Entry> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(o.getString("word"), o.getString("en"), o.optString("level"), o.optLong("saved"),
                ReviewSchedule.Progress(o.optInt("streak"), o.optLong("due"), o.optInt("attempts"), o.optInt("correct")), o.optLong("lastReviewed"))
        }
    }.getOrDefault(emptyList())

    fun save(word: String, english: String, level: String): Boolean {
        if (word.isBlank() || english.isBlank() || word.length > 100 || english.length > 300) return false
        val items = all()
        if (items.any { it.word == word }) return false
        if (items.size >= 3000) return false
        write(items + Entry(word, english, level, System.currentTimeMillis()))
        return true
    }
    fun contains(word: String): Boolean = all().any { it.word == word }
    fun answer(word: String, remembered: Boolean, now: Long = System.currentTimeMillis()) {
        val items = all()
        if (items.none { it.word == word }) return
        val events = (history() + LearningStats.Event(word, now, remembered)).takeLast(10000)
        val array = JSONArray()
        events.forEach { e -> array.put(JSONObject().put("word", e.word).put("at", e.at).put("remembered", e.remembered)) }
        write(items.map { if (it.word == word) it.copy(progress = ReviewSchedule.answer(it.progress, remembered, now), lastReviewedAt = now) else it }, array)
    }
    fun history(): List<LearningStats.Event> = runCatching {
        val array = JSONArray(prefs.getString("review_history", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { LearningStats.Event(it.getString("word"), it.getLong("at"), it.getBoolean("remembered")) } }
    }.getOrDefault(emptyList())
    private fun write(items: List<Entry>, history: JSONArray? = null) {
        val array = JSONArray()
        items.forEach { e -> array.put(JSONObject().apply {
            put("word", e.word); put("en", e.english); put("level", e.level); put("saved", e.savedAt)
            put("streak", e.progress.streak); put("due", e.progress.dueAt)
            put("attempts", e.progress.attempts); put("correct", e.progress.correct)
            put("lastReviewed", e.lastReviewedAt)
        }) }
        prefs.edit().putString("entries", array.toString()).also { if (history != null) it.putString("review_history", history.toString()) }.apply()
    }
}
