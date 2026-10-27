package com.vime.ime.engine

/** User-saved snippets in RAM, expire after ten minutes. No background clipboard listener. */
class ClipboardSession {
    data class Entry(val text: String, val savedAt: Long)
    private val entries = ArrayList<Entry>()
    fun list(now: Long): List<Entry> {
        entries.removeAll { now - it.savedAt >= 600_000L || it.savedAt > now }
        return entries.toList()
    }
    fun save(text: String, sensitive: Boolean, now: Long): Boolean {
        list(now)
        if (sensitive || text.isBlank() || text.length > 2000) return false
        entries.removeAll { it.text == text }
        entries.add(0, Entry(text, now))
        while (entries.size > 10) entries.removeAt(entries.lastIndex)
        return true
    }
    fun clear() = entries.clear()
}
