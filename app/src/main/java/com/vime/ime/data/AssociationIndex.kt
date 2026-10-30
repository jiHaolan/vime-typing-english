package com.vime.ime.data

/** Curated local next-word suggestions. No harvesting or persistence of user input. */
class AssociationIndex(private val pairs: Map<String, List<String>>) {
    private val longest = pairs.keys.maxOfOrNull { it.length } ?: 0
    fun next(previous: String, limit: Int = 12): List<String> {
        if (previous.isBlank() || limit <= 0) return emptyList()
        val key = (minOf(longest, previous.length) downTo 1).asSequence()
            .map { previous.takeLast(it) }.firstOrNull { pairs.containsKey(it) }
        return pairs[key].orEmpty().asSequence().filter { it.isNotBlank() && it != previous }.distinct().take(limit).toList()
    }
}
