package com.vime.ime.engine

/** Tracks Android pointer IDs (not transient pointer indices). A release consumes only its own key. */
class PointerKeys<T> {
    private val keys = LinkedHashMap<Int, T>()
    val size: Int get() = keys.size
    fun down(id: Int, key: T) { keys[id] = key }
    fun move(id: Int, key: T?) { if (keys.containsKey(id)) { if (key == null) keys.remove(id) else keys[id] = key } }
    fun get(id: Int): T? = keys[id]
    fun release(id: Int): T? = keys.remove(id)
    fun values(): List<T> = keys.values.toList()
    fun clear() { keys.clear() }
}
