package com.vime.ime.data

/** Small offline dictionary lookup, not a statistical Chinese sentence decoder. */
class CandidateIndex(
    private val words: Map<String, List<String>>,
    private val frequencies: Map<String, Int>,
    private val english: Map<String, String>,
    private val levels: Map<String, String>
) {
    private val keys = words.keys.sorted()
    private val rank = compareByDescending<String> { frequencies[it] ?: 0 }.thenBy { it.length }.thenBy { it }
    private val t9 = keys.groupBy { key -> key.map(::digit).joinToString("") }.mapValues { it.value.sortedWith(rank) }
    private val digits = t9.keys.sorted()
    private fun prefixIndex(nine: Boolean): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        for (key in keys.sortedWith(rank)) {
            val typed = if (nine) key.map(::digit).joinToString("") else key
            for (len in 1..minOf(2, typed.length)) {
                val list = result.getOrPut(typed.take(len)) { ArrayList() }
                if (list.size < 96) list.add(key)
            }
        }
        return result
    }
    private val shortPrefixes = prefixIndex(false)
    private val shortDigits = prefixIndex(true)
    private data class Request(val text: String, val nine: Boolean, val limit: Int)
    private val cache = object : LinkedHashMap<Request, List<Cand>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Request, List<Cand>>?) = size > 128
    }

    fun query(raw: String, nine: Boolean = false, limit: Int = 50): List<Cand> {
        if (raw.isEmpty() || raw.length > 64 || limit <= 0) return emptyList()
        val input = raw.lowercase()
        if (nine && input.any { it !in '2'..'9' }) return emptyList()
        if (!nine && input.any { it !in 'a'..'z' }) return emptyList()
        val request = Request(input, nine, limit.coerceAtMost(100))
        synchronized(cache) { cache[request]?.let { return it } }
        val result = lookup(input, nine, request.limit)
        synchronized(cache) { cache[request] = result }
        return result
    }

    private fun lookup(input: String, nine: Boolean, limit: Int): List<Cand> {
        val found = LinkedHashMap<String, Cand>()
        if (!nine) InternetPhrases.query(input).take(limit).forEach { found[it.word] = it }
        fun append(key: String, consumed: Int, take: Int = limit) {
            for (word in words[key].orEmpty().take(take)) {
                if (found.size >= limit) return
                found.putIfAbsent(word, Cand(word, english[word].orEmpty(), levels[word].orEmpty(), consumed))
            }
        }
        fun exact(prefix: String, consumed: Int) {
            val matching = if (nine) t9[prefix].orEmpty() else listOf(prefix)
            matching.forEach { append(it, consumed) }
        }
        exact(input, input.length)
        if (found.size >= limit) return found.values.toList()
        val index = if (nine) digits else keys
        var pos = index.binarySearch(input).let { if (it < 0) -it - 1 else it }
        // A bounded prefix scan avoids sorting the entire dictionary on every keystroke.
        val completionKeys = ArrayList<String>()
        if (input.length <= 2) completionKeys.addAll((if (nine) shortDigits else shortPrefixes)[input].orEmpty())
        while (input.length > 2 && pos < index.size && index[pos].startsWith(input) && completionKeys.size < 512) {
            val key = index[pos++]
            if (nine) completionKeys.addAll(t9[key].orEmpty()) else completionKeys.add(key)
        }
        completionKeys.sortedWith(rank)
            .forEach { append(it, input.length, 1) }
        // Only LEFT prefixes can be consumed. The old suffix fallback lost preceding input.
        if (found.isEmpty()) {
            assemble(input, nine)?.let { phrase -> found[phrase] = Cand(phrase, english[phrase].orEmpty(), levels[phrase].orEmpty(), input.length) }
            for (end in input.length - 1 downTo 1) {
                val size = found.size
                exact(input.substring(0, end), end)
                if (found.size > size || found.size >= limit) break
            }
        }
        return found.values.toList()
    }

    /** Bounded dictionary-only segmentation. Not a context/language model and never invents glosses. */
    private fun assemble(input: String, nine: Boolean): String? {
        if (input.length !in 4..48) return null
        val score = DoubleArray(input.length + 1) { Double.NEGATIVE_INFINITY }
        val phrase = arrayOfNulls<String>(input.length + 1)
        score[0] = 0.0; phrase[0] = ""
        for (end in 1..input.length) for (start in maxOf(0, end - 18) until end) {
            if (phrase[start] == null) continue
            val typed = input.substring(start, end)
            val key = if (nine) t9[typed]?.firstOrNull() else typed.takeIf { words.containsKey(it) }
            val word = words[key]?.firstOrNull() ?: continue
            val next = score[start] + kotlin.math.ln(((frequencies[key] ?: 1).coerceAtLeast(1)).toDouble()) - 14.0
            if (next > score[end]) { score[end] = next; phrase[end] = phrase[start] + word }
        }
        return phrase[input.length]
    }

    companion object {
        fun digit(c: Char): Char = when (c) {
            in 'a'..'c' -> '2'; in 'd'..'f' -> '3'; in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'; in 'm'..'o' -> '6'; in 'p'..'s' -> '7'
            in 't'..'v' -> '8'; in 'w'..'z' -> '9'; else -> '0'
        }
    }
}
