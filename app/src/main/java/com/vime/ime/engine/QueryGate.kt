package com.vime.ime.engine

/** UI-thread generation gate. Late background results may not replace a newer editor/query. */
class QueryGate {
    private var generation = 0L
    fun next(): Long = ++generation
    fun accepts(token: Long) = token == generation
}
