package com.vime.ime.data

/** Curated local next-word suggestions. No harvesting or persistence of user input. */
class AssociationIndex(private val pairs: Map<String, List<String>>) {
    fun next(previous: String, limit: Int = 12): List<String> {
        if (previous.isBlank() || limit <= 0) return emptyList()
        val key = if (pairs.containsKey(previous)) previous else
            pairs.keys.filter { it.length <= previous.length && previous.endsWith(it) }.maxByOrNull { it.length }
        return pairs[key].orEmpty().asSequence().filter { it.isNotBlank() && it != previous }.distinct().take(limit).toList()
    }
}
