package com.vime.ime.engine

/** Explicit local formatting only, not semantic AI rewriting. */
object LocalFormatting {
    enum class Action { SPACES, PARAGRAPHS, BULLETS, UPPERCASE }
    const val MAX_TEXT = 4000
    fun apply(text: String, action: Action): String? {
        if (text.isBlank() || text.length > MAX_TEXT) return null
        return when (action) {
            Action.SPACES -> text.lines().joinToString("\n") { it.trim().replace(Regex("[\\t ]+"), " ") }.trim()
            Action.PARAGRAPHS -> text.split(Regex("(?<=[。！？.!?])\\s*|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            Action.BULLETS -> text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { "• $it" }
            Action.UPPERCASE -> text.uppercase(java.util.Locale.ROOT)
        }
    }
}
