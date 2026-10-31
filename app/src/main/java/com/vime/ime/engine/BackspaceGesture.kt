package com.vime.ime.engine

/** A stationary hold never clears. Only an intentional upward hold-and-release can clear. */
class BackspaceGesture(private val upDistance: Float, private val sidewaysLimit: Float) {
    companion object { const val HOLD_MS = 300L }
    private var started = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    var active = false; private set
    var armed = false; private set
    fun begin(now: Long, x: Float, y: Float) {
        started = now; startX = x; startY = y; lastX = x; lastY = y; active = true; armed = false
    }
    fun held(now: Long) = active && now - started >= HOLD_MS
    fun move(now: Long, x: Float, y: Float) {
        if (!active) return
        lastX = x; lastY = y
        if (kotlin.math.abs(x - startX) > sidewaysLimit || y - startY > upDistance) { cancel(); return }
        armed = held(now) && startY - y >= upDistance
    }
    fun refresh(now: Long) { move(now,lastX,lastY) }
    fun release(now: Long, x: Float, y: Float, sameKey: Boolean): Int? {
        move(now, x, y)
        val result = when { !active -> null; armed -> KeyHoldPolicy.CLEAR; sameKey -> -5; else -> null }
        cancel()
        return result
    }
    fun cancel() { active = false; armed = false }
}
