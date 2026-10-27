package com.vime.ime.engine

/** Transparent baseline spacing, not a claim to reproduce a commercial learning algorithm. */
object ReviewSchedule {
    data class Progress(val streak: Int = 0, val dueAt: Long = 0, val attempts: Int = 0, val correct: Int = 0)
    private val days = longArrayOf(1, 3, 7, 14, 30)
    fun answer(old: Progress, remembered: Boolean, now: Long): Progress {
        val streak = if (remembered) (old.streak + 1).coerceAtMost(days.size) else 0
        val delay = if (remembered) days[streak - 1] * 86_400_000L else 10 * 60_000L
        return Progress(streak, now + delay, old.attempts + 1, old.correct + if (remembered) 1 else 0)
    }
}
