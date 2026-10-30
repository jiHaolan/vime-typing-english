package com.vime.ime.data

import com.vime.ime.engine.LearningStats
import com.vime.ime.engine.ReviewSchedule
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only explicitly chosen vocabulary and review data, never input history or credentials. */
object LearningArchive {
    const val MAX_WORDS = 3000
    const val MAX_EVENTS = 10000
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_DATE = 253402300799999L
    data class Snapshot(val entries: List<WordBook.Entry>, val history: List<LearningStats.Event>)
    data class Backup(val createdAt: Long, val data: Snapshot)
    enum class Restore { MERGE, REPLACE }
    data class ImportResult(val added: Int, val kept: Int, val total: Int)

    fun read(input: InputStream): Backup {
        val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(bytes.size().toLong() + n <= MAX_BYTES) { "备份文件超过16MB" }
            bytes.write(buffer,0,n)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
        val tokens=JSONTokener(text)
        val root=tokens.nextValue() as? JSONObject ?: throw IllegalArgumentException("备份内容格式有误")
        require(tokens.nextClean()=='\u0000') { "备份文件末尾含有无效内容" }
        require(root.opt("format") == "vime-learning-backup") { "请选择本应用导出的学习备份" }
        require(number(root,"version") == 1L) { "此备份版本暂不支持，请更新应用" }
        val created = date(root,"createdAt")
        val words = root.getJSONArray("entries"); val events = root.getJSONArray("history")
        require(words.length() <= MAX_WORDS && events.length() <= MAX_EVENTS) { "备份记录数量超过支持范围" }
        val entries = (0 until words.length()).map { i ->
            val o = words.getJSONObject(i); val p = o.getJSONObject("progress")
            WordBook.Entry(string(o,"word"),string(o,"english"),string(o,"level"),date(o,"savedAt"),
                ReviewSchedule.Progress(integer(p,"streak"),date(p,"dueAt"),integer(p,"attempts"),integer(p,"correct")),date(o,"lastReviewedAt"))
        }
        val history = (0 until events.length()).map { i ->
            val o = events.getJSONObject(i); val remembered = o.opt("remembered")
            require(remembered is Boolean) { "备份中的复习结果格式有误" }
            LearningStats.Event(string(o,"word"),date(o,"at"),remembered)
        }
        return Backup(created,Snapshot(entries,history).also(::validate))
    }
    fun encode(data: Snapshot, createdAt: Long = System.currentTimeMillis()): ByteArray {
        validate(data); require(createdAt in 0..MAX_DATE)
        val words=JSONArray(); val events=JSONArray()
        data.entries.forEach { e -> words.put(JSONObject().put("word",e.word).put("english",e.english).put("level",e.level)
            .put("savedAt",e.savedAt).put("lastReviewedAt",e.lastReviewedAt).put("progress",JSONObject()
                .put("streak",e.progress.streak).put("dueAt",e.progress.dueAt).put("attempts",e.progress.attempts).put("correct",e.progress.correct))) }
        data.history.forEach { e -> events.put(JSONObject().put("word",e.word).put("at",e.at).put("remembered",e.remembered)) }
        return JSONObject().put("format","vime-learning-backup").put("version",1).put("createdAt",createdAt)
            .put("entries",words).put("history",events).toString().toByteArray(Charsets.UTF_8).also { require(it.size<=MAX_BYTES) { "备份文件超过16MB" } }
    }
    fun validate(data: Snapshot) {
        require(data.entries.size<=MAX_WORDS && data.history.size<=MAX_EVENTS) { "学习记录数量超过支持范围" }
        val words=HashSet<String>()
        data.entries.forEach { e ->
            require(listOf(e.word,e.english,e.level).all { Charsets.UTF_8.newEncoder().canEncode(it) }) { "词条文字编码有误" }
            require(e.word.isNotBlank() && e.word.length<=100 && e.word.none { it.code<32 || it.code==127 }) { "词条名称有误" }
            require(e.english.isNotBlank() && e.english.length<=300 && e.level.length<=40) { "词条释义或等级有误" }
            require(words.add(e.word)) { "备份中存在重复词条" }
            require(e.savedAt in 0..MAX_DATE && e.lastReviewedAt in 0..MAX_DATE && e.progress.dueAt in 0..MAX_DATE) { "学习日期有误" }
            require(e.progress.streak in 0..5 && e.progress.attempts>=0 && e.progress.correct in 0..e.progress.attempts) { "复习进度有误" }
        }
        data.history.forEach { e -> require(e.word in words && e.at in 0..MAX_DATE) { "复习记录与词条不匹配" } }
    }
    /** Abort a damaged legacy migration instead of silently replacing it with an empty book. */
    fun legacy(entries: String, history: String): Snapshot {
        require(entries.length.toLong()+history.length<=MAX_BYTES) { "旧版学习数据过大" }
        val words=JSONArray(entries); val events=JSONArray(history)
        require(words.length()<=MAX_WORDS && events.length()<=MAX_EVENTS) { "旧版学习记录数量超出范围" }
        return Snapshot((0 until words.length()).map { i -> words.getJSONObject(i).let { o ->
            WordBook.Entry(o.getString("word"),o.getString("en"),o.optString("level"),o.optLong("saved"),
                ReviewSchedule.Progress(o.optInt("streak"),o.optLong("due"),o.optInt("attempts"),o.optInt("correct")),o.optLong("lastReviewed"))
        } },(0 until events.length()).map { i -> events.getJSONObject(i).let {
            LearningStats.Event(it.getString("word"),it.getLong("at"),it.getBoolean("remembered"))
        } }).also(::validate)
    }
    private fun string(o:JSONObject,key:String)=(o.opt(key) as? String) ?: throw IllegalArgumentException("备份字段格式有误：$key")
    private fun number(o:JSONObject,key:String):Long {
        val v=o.opt(key); require(v is Int || v is Long) { "备份数字格式有误：$key" }; return (v as Number).toLong()
    }
    private fun integer(o:JSONObject,key:String)=number(o,key).also { require(it in 0..Int.MAX_VALUE.toLong()) { "复习计数超出范围" } }.toInt()
    private fun date(o:JSONObject,key:String)=number(o,key).also { require(it in 0..MAX_DATE) { "学习日期有误" } }
}
