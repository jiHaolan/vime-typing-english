package com.vime.ime.engine

import com.vime.ime.data.*

object InputEngine {
    /** Custom phrases precede engine results, and their slot count is accounted for on every page. */
    fun query(raw: String, nine: Boolean, offset: Int = 0, limit: Int = 12, personalized: Boolean = true): List<Cand> {
        val manual = if (personalized) PersonalLexicon.query(raw, nine) else emptyList()
        val shortcuts = if (!nine) InternetPhrases.query(raw) else emptyList()
        val front = (manual + shortcuts).distinctBy { it.word }
        val start = offset.coerceAtLeast(0)
        val selected = front.drop(start).take(limit)
        val remaining = limit - selected.size
        if (remaining <= 0) return selected
        val engineStart = (start - front.size).coerceAtLeast(0)
        val native = if (RimeEngine.state == RimeEngine.State.READY) {
            val filtered = ArrayList<Cand>(); val excluded = front.map { it.word }.toSet();var cursor = 0
            while (filtered.size < engineStart + remaining && cursor < 2048) {
                val batch = RimeEngine.query(raw,cursor,minOf(64,engineStart+remaining-filtered.size+front.size))
                if (batch.isEmpty()) break
                cursor += batch.size
                filtered.addAll(batch.filter { it.word !in excluded })
            }
            filtered.drop(engineStart).take(remaining)
        } else {
            val items = if (nine) DictRepository.t9Candidates(raw, 100) else DictRepository.candidates(raw, 100)
            (if (personalized) PersonalLexicon.rank(raw, items) else items).filter { c -> front.none { it.word==c.word } }.drop(engineStart).take(remaining)
        }
        return (selected + native).distinctBy { it.word }
    }
    fun selected(raw: String, c: Cand, allowed: Boolean) {
        if (!allowed) return
        PersonalLexicon.record(raw, c, true)
        RimeEngine.learn(raw, c)
    }
}
