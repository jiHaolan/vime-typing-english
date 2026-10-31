package com.vime.ime.data

/** consumed is the number of composing characters replaced, never a guessed suffix. */
data class Cand(val word: String, val en: String, val lvl: String, val consumed: Int = 0, val fromRime: Boolean = false,
                val readingCode: String = "")
data class Card(val py: String, val d1: String, val d2: String)
