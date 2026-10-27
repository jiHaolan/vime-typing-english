package com.vime.ime.engine

/** Preserve press order when two thumbs lift in the opposite order. Cancellation discards pending taps. */
class OrderedReleases<T> {
    private data class Slot<T>(var ready: Boolean = false, var value: T? = null)
    private val active = HashMap<Int, Slot<T>>()
    private val order = java.util.ArrayDeque<Slot<T>>()
    fun down(id: Int) {
        val slot = Slot<T>()
        active[id] = slot
        order.addLast(slot)
    }
    fun release(id: Int, value: T?): List<T> {
        val slot = active.remove(id) ?: return emptyList()
        slot.ready = true; slot.value = value
        val result = ArrayList<T>()
        while (!order.isEmpty() && order.first.ready) {
            order.removeFirst().value?.let(result::add)
        }
        return result
    }
    fun clear() { active.clear(); order.clear() }
}
