package com.vime.ime.engine

class VoiceSessionGate {
    private var generation = 0L
    private var active = false
    fun start(): Long { active = true; return ++generation }
    fun cancel() { active = false; generation++ }
    fun accepts(token: Long) = active && token == generation
}
