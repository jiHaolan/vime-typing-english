package com.vime.ime.engine

import com.vime.ime.data.Cand

/** Keep decoder segmentation and single-character reading weights; rerank only comparable words. */
object EverydayCandidateOrder {
    fun rank(items: List<Cand>, frequency: (String) -> Int, choices: (String) -> Int): List<Cand> {
        val result = ArrayList<Cand>(items.size)
        var start = 0
        while (start < items.size) {
            val first = items[start]
            val characters = first.word.codePointCount(0, first.word.length)
            var end = start + 1
            while (end < items.size && items[end].consumed == first.consumed && items[end].word.codePointCount(0, items[end].word.length) == characters) end++
            val group = items.subList(start, end)
            result.addAll(if (characters == 1) group else group.sortedWith(
                compareByDescending<Cand> { choices(it.word).takeIf { count -> count >= 2 } ?: 0 }
                    .thenByDescending { frequency(it.word) }))
            start = end
        }
        return result
    }
}
