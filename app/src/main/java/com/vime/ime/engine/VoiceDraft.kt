package com.vime.ime.engine

/** RAM-only, bounded and short-lived. Never auto-insert a recognition result. */
class VoiceDraft(private val ttl: Long = 120_000) {
    private var value: String? = null
    private var savedAt = 0L
    @Synchronized fun put(text: String, now: Long): Boolean {
        clear()
        if (text.isBlank() || text.length > 4000) return false
        value = text; savedAt = now
        return true
    }
    @Synchronized fun peek(now: Long): String? {
        if (now < savedAt || now - savedAt >= ttl) clear()
        return value
    }
    @Synchronized fun clear() { value = null; savedAt = 0L }
}

object VoiceTransfer { val draft = VoiceDraft() }
