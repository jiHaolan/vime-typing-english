package com.vime.ime.data

import android.content.Context
import org.json.JSONObject

/** Publish a complete immutable dictionary at once. Typing never reads half an import. */
object DictRepository {
    private data class Snapshot(val index: CandidateIndex, val en: Map<String, String>,
        val levels: Map<String, String>, val enLevels: Map<String, String>, val cards: Map<String, Card>,
        val reverse: Map<String, String>, val englishKeys: List<String>, val associations: AssociationIndex,
        val pinyinCount: Int, val wordCount: Int, val pack: LexiconPack?, val definitions: Map<String, Card>)
    @Volatile private var snapshot: Snapshot? = null
    enum class State { IDLE, LOADING, READY, FAILED }
    @Volatile var state = State.IDLE; private set
    @Volatile var loadError: String? = null; private set
    @Volatile var packError: String? = null; private set
    val ready get() = snapshot != null
    val installedPack get() = snapshot?.pack

    @Synchronized fun load(context: Context) {
        if (ready) return
        state = State.LOADING
        try {
            // Keep built-in phrases usable while a large optional pack is being indexed.
            snapshot = build(context, null)
            val pack = try { LexiconStore.read(context) } catch (e: Exception) {
                packError = "扩展词典未启用：${e.message}"; null
            }
            if (pack != null) try { snapshot = build(context, pack) }
            catch (_: Exception) { packError = "扩展词典加载失败，继续使用内置词库" }
            state = State.READY; loadError = null
        } catch (e: Exception) { state = State.FAILED; loadError = e.message ?: e.javaClass.simpleName }
    }
    @Synchronized fun install(context: Context, bytes: ByteArray) {
        LexiconStore.install(context, bytes) { pack ->
            val next = build(context, pack)
            val publish: () -> Unit = { snapshot = next; state = State.READY; loadError = null; packError = null }
            publish
        }
    }
    @Synchronized fun uninstall(context: Context) {
        LexiconStore.remove(context) {
            val next = build(context, null)
            val publish: () -> Unit = { snapshot = next; state = State.READY; loadError = null; packError = null }
            publish
        }
    }
    private fun build(context: Context, pack: LexiconPack?): Snapshot {
        val root = JSONObject(context.assets.open("dict.android.json").bufferedReader().use { it.readText() })
        val words = HashMap<String, MutableList<String>>()
        val frequencies = HashMap<String, Int>()
        val en = HashMap<String, String>()
        val enLevels = HashMap<String, String>()
        val cards = HashMap<String, Card>()
        val pw = root.getJSONObject("pw")
        pw.keys().forEach { py ->
            val rows = pw.getJSONArray(py)
            words[py] = (0 until rows.length()).map { rows.getJSONArray(it).getString(0) }.toMutableList()
            frequencies[py] = if (rows.length() > 0) rows.getJSONArray(0).optInt(1, 0) else 0
        }
        val rawEn = root.getJSONObject("en")
        rawEn.keys().forEach { en[it] = rawEn.getString(it) }
        val rawLevels = root.getJSONObject("lvl")
        rawLevels.keys().forEach { enLevels[it.lowercase()] = rawLevels.getString(it) }
        val rawCards = root.getJSONObject("card")
        rawCards.keys().forEach { word ->
            val row = rawCards.getJSONArray(word)
            cards[word] = Card(row.optString(0), row.optString(1), row.optString(2))
        }
        fun add(py: String, word: String, gloss: String, reading: String, priority: Boolean = false) {
            val list = words.getOrPut(py) { ArrayList() }
            if (word !in list) { if (priority) list.add(0, word) else list.add(word) }
            en.putIfAbsent(word, gloss)
            cards.putIfAbsent(word, Card(reading, gloss, ""))
            frequencies.putIfAbsent(py, if (priority) 1000 else 1)
        }
        LexiconSupplement.entries.forEach { add(it.pinyin, it.word, it.english, it.reading, true) }
        val definitions = HashMap<String, Card>()
        pack?.entries?.forEach {
            add(it.pinyin, it.word, it.gloss, it.reading)
            val previous = definitions[it.word]
            definitions[it.word] = if (previous == null) Card(it.reading, it.gloss, "") else
                Card(previous.py, (previous.d1 + " / " + it.gloss).take(3000), "")
        }
        val levels = HashMap<String, String>()
        val reverse = HashMap<String, String>()
        val tokens = Regex("[A-Za-z][A-Za-z-]+")
        val suffixes = listOf("ing", "ed", "es", "s", "ly", "er", "est")
        fun rank(level: String?) = when (level) { LevelPref.CET4 -> 1; LevelPref.CET6 -> 2; LevelPref.TOEFL -> 3; else -> 0 }
        for ((word, gloss) in en.toSortedMap()) {
            var best = 0
            for (match in tokens.findAll(gloss)) {
                val token = match.value.lowercase().trim('-')
                if (token.length < 2) continue
                reverse.putIfAbsent(token, word)
                var r = rank(enLevels[token])
                for (suffix in suffixes) {
                    if (token.length > suffix.length + 2 && token.endsWith(suffix)) r = maxOf(r, rank(enLevels[token.removeSuffix(suffix)]))
                }
                best = maxOf(best, r)
            }
            if (best > 0) levels[word] = listOf("", LevelPref.CET4, LevelPref.CET6, LevelPref.TOEFL)[best]
        }
        val assoc = JSONObject(context.assets.open("associations.json").bufferedReader().use { it.readText() })
        val pairs = HashMap<String, List<String>>()
        assoc.keys().forEach { key -> val arr = assoc.getJSONArray(key); pairs[key] = (0 until arr.length()).map { arr.getString(it) } }
        val immutableWords = words.mapValues { it.value.toList() }
        return Snapshot(CandidateIndex(immutableWords, frequencies, en, levels), en, levels, enLevels, cards,
            reverse, reverse.keys.sorted(), AssociationIndex(pairs), words.size, words.values.sumOf { it.size }, pack, definitions)
    }
    fun candidates(input: String, limit: Int = 12) = snapshot?.index?.query(input, limit = limit).orEmpty()
    fun t9Candidates(input: String, limit: Int = 12) = snapshot?.index?.query(input, nine = true, limit = limit).orEmpty()
    fun enCandidates(prefix: String, limit: Int = 12): List<Cand> {
        val s = snapshot ?: return emptyList()
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val p = prefix.lowercase()
        var i = s.englishKeys.binarySearch(p).let { if (it < 0) -it - 1 else it }
        val pool = ArrayList<String>()
        while (i < s.englishKeys.size && pool.size < 400 && s.englishKeys[i].startsWith(p)) pool.add(s.englishKeys[i++])
        return pool.sortedWith(compareBy<String> { it.length }.thenBy { it }).take(limit)
            .map { Cand(it, s.reverse[it].orEmpty(), s.enLevels[it].orEmpty()) }
    }
    fun getEn(word: String) = snapshot?.en?.get(word).orEmpty()
    fun getLvl(word: String) = snapshot?.levels?.get(word).orEmpty()
    fun getEnLevel(word: String) = snapshot?.enLevels?.get(word.lowercase()).orEmpty()
    fun getCard(word: String) = snapshot?.cards?.get(word)
    fun definition(word: String): WordDefinition? {
        val s = snapshot ?: return null
        s.definitions[word]?.let { return WordDefinition(word, it.py, it.d1, "${s.pack?.name} · ${s.pack?.license}") }
        val card = s.cards[word]
        val meaning = s.en[word].orEmpty().ifEmpty { card?.d1.orEmpty() }
        if (meaning.isEmpty()) return null
        return WordDefinition(word, card?.py.orEmpty(), meaning, "内置离线词库")
    }
    fun associations(previous: String, limit: Int = 12): List<Cand> {
        val s = snapshot ?: return emptyList()
        return s.associations.next(previous, limit).map { Cand(it, s.en[it].orEmpty(), s.levels[it].orEmpty()) }
    }
    val pinyinCount get() = snapshot?.pinyinCount ?: 0
    val wordCount get() = snapshot?.wordCount ?: 0
    val enCount get() = snapshot?.en?.size ?: 0
    val lvlCount get() = snapshot?.levels?.size ?: 0
}
