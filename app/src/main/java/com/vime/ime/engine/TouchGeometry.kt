package com.vime.ime.engine

/** Fill small key gaps and tolerate release jitter, without retaining a key after a real slide. */
object TouchGeometry {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float, margin: Float = 0f) =
            x >= left - margin && x < right + margin && y >= top - margin && y < bottom + margin
        fun distanceSquared(x: Float, y: Float): Float {
            val dx = maxOf(left - x, 0f, x - right)
            val dy = maxOf(top - y, 0f, y - bottom)
            return dx * dx + dy * dy
        }
    }
    fun hit(boxes: List<Box>, x: Float, y: Float, current: Int = -1, slop: Float = 0f, gap: Float = 0f): Int {
        if (current in boxes.indices && boxes[current].contains(x, y, slop)) return current
        boxes.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { return it }
        return boxes.indices.minByOrNull { boxes[it].distanceSquared(x, y) }
            ?.takeIf { boxes[it].distanceSquared(x, y) <= gap * gap } ?: -1
    }
}
