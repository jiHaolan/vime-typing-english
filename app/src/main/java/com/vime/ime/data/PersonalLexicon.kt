package com.vime.ime.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PhraseRule(val trigger: String, val text: String, val priority: Int = 50, val enabled: Boolean = true)
object PhraseCodec {
    const val MAX = 1000
    fun validate(r: PhraseRule) {
        require(r.trigger.matches(Regex("[a-z]{1,64}"))) { "触发码需为1—64个英文字母" }
        require(r.text.isNotBlank() && r.text.length <= 300 && r.text.none { it == '\u0000' || it == '\u007f' }) { "短语需为1—300个字符" }
        require(r.priority in 1..100) { "优先级需在1—100之间" }
    }
    fun read(text: String): List<PhraseRule> {
        require(text.toByteArray().size <= 1024 * 1024) { "文件超过1MB" }
        val root = JSONObject(text); require(root.getString("format") == "vime-phrases-1") { "不是可用的短语文件" }
        val array = root.getJSONArray("phrases"); require(array.length() <= MAX) { "最多保存1000条短语" }
        return (0 until array.length()).map { i -> array.getJSONObject(i).let {
            PhraseRule(it.getString("trigger"), it.getString("text"), it.getInt("priority"), it.getBoolean("enabled"))
        }.also(::validate) }.distinctBy { it.trigger to it.text }
    }
    fun write(items: List<PhraseRule>): String {
        require(items.size <= MAX); items.forEach(::validate)
        val array = JSONArray(); items.forEach { r -> array.put(JSONObject().put("trigger", r.trigger).put("text", r.text).put("priority", r.priority).put("enabled", r.enabled)) }
        return JSONObject().put("format", "vime-phrases-1").put("phrases", array).toString(2).also { require(it.toByteArray().size <= 1024*1024) { "短语总大小超过1MB" } }
    }
}
object PersonalLexicon {
    private lateinit var prefs: android.content.SharedPreferences
    @Volatile private var rules = emptyList<PhraseRule>()
    private val counts = LinkedHashMap<String, Int>()
    @Synchronized fun init(c: Context) {
        if (::prefs.isInitialized) return
        prefs = c.getSharedPreferences("personal_lexicon", Context.MODE_PRIVATE)
        rules = runCatching { PhraseCodec.read(prefs.getString("phrases", "")!!) }.getOrDefault(emptyList())
        runCatching { val o = JSONObject(prefs.getString("counts", "{}")!!); o.keys().forEach { counts[it] = o.optInt(it, 0).coerceIn(0, 100000) } }
    }
    fun list() = rules.toList()
    @Synchronized fun save(items: List<PhraseRule>) {
        val encoded = PhraseCodec.write(items); rules = items.toList(); prefs.edit().putString("phrases", encoded).apply()
    }
    @Synchronized fun merge(items: List<PhraseRule>) {
        items.forEach(PhraseCodec::validate)
        save((items + rules).distinctBy { it.trigger to it.text })
    }
    fun query(raw: String, nine: Boolean = false): List<Cand> = rules.filter { it.enabled &&
        (if (nine) it.trigger.map(CandidateIndex::digit).joinToString("") == raw else it.trigger == raw)
    }.sortedByDescending { it.priority }.map { DictRepository.candidate(it.text,raw,raw.length) }
    @Synchronized fun record(raw: String, c: Cand, allowed: Boolean) {
        if (!allowed || !InputLearningPrefs.choicesEnabled || raw.isEmpty() || c.word.length > 64) return
        val key = raw.take(128) + "\t" + c.word
        counts[key] = ((counts[key] ?: 0) + 1).coerceAtMost(100000)
        while (counts.size > 2000) counts.remove(counts.keys.first())
        val o = JSONObject(); counts.forEach { (k, v) -> o.put(k, v) }; prefs.edit().putString("counts", o.toString()).apply()
    }
    @Synchronized fun count(raw: String, word: String) = counts[raw + "\t" + word] ?: 0
    @Synchronized fun rank(raw: String, items: List<Cand>) = items.sortedByDescending { count(raw, it.word) }
    @Synchronized fun clearManualCounts() {
        check(prefs.edit().remove("counts").commit()) { "词频清理未完成" }
        counts.clear()
    }
    @Synchronized fun clearPhrases() {
        check(prefs.edit().remove("phrases").commit()) { "短语清理未完成" }
        rules = emptyList()
    }
}
