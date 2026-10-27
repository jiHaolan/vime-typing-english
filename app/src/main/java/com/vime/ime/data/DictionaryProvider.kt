package com.vime.ime.data

data class WordDefinition(val word: String, val reading: String, val meaning: String, val source: String)

/** Network providers require a separately authorized backend, never an API secret in the IME. */
interface DictionaryProvider {
    val id: String
    fun lookup(word: String): WordDefinition?
}
object OfflineDictionaryProvider : DictionaryProvider {
    override val id = "local"
    override fun lookup(word: String): WordDefinition? = DictRepository.definition(word)
}
