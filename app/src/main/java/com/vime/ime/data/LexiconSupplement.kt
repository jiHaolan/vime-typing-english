package com.vime.ime.data

/** Small, traceable corrections; never invent a gloss for an unknown word. */
object LexiconSupplement {
    data class Entry(val word: String, val pinyin: String, val english: String, val reading: String, val source: String)
    val entries = listOf(
        Entry("金币", "jinbi", "gold coin", "jin1 bi4", "https://en.wiktionary.org/wiki/金幣"),
        Entry("木薯", "mushu", "cassava", "mu4 shu3", "Local CC-CEDICT: 木薯 木薯 [mu4 shu3] /cassava, a tropical tuber plant/")
    )
}
