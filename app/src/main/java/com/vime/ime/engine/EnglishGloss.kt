package com.vime.ime.engine

/** One dictionary sense in the narrow strip; the unabridged entry remains in the word card. */
object EnglishGloss {
    fun primary(value: String): String = value.trim()
        .split(Regex("\\s*[/;；\\n]\\s*|,\\s*|，"), limit = 2).firstOrNull().orEmpty()
        .replace(Regex("\\([^)]*\\)|（[^）]*）|\\[[^]]*]"), "")
        .replace(Regex("^(?:(?:adj|adv|n|v|vt|vi|prep|pron|conj|interj)\\.\\s*)+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ").trim()
    fun label(value: String): String {
        val first = primary(value)
        if (first.length <= 22) return first
        val boundary = first.lastIndexOf(' ', 21)
        return first.take(if (boundary >= 10) boundary else 21).trimEnd() + "…"
    }
}
