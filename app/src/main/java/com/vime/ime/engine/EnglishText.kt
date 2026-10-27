package com.vime.ime.engine

import java.util.Locale

/** Suggestions never own composition. Only an explicit pick may replace the verified prefix. */
object EnglishText {
    fun prefix(before: String): String {
        val word = before.takeLastWhile { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' || it == '-' }
        return word.takeIf { it.length in 1..48 && it.first().isLetter() }.orEmpty()
    }
    fun matchingCase(word: String, prefix: String): String = when {
        prefix.all { it.isUpperCase() } -> word.uppercase(Locale.ROOT)
        prefix.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }
}
