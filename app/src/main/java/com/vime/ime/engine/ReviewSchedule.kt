package com.vime.ime.engine

/** Transparent baseline spacing, not a claim to reproduce a commercial learning algorithm. */
object ReviewSchedule {
    data class Progress(val streak: Int = 0, val dueAt: Long = 0, val attempts: Int = 0, val correct: Int = 0)
    private val days = longArrayOf(1, 3, 7, 14, 30)
    fun answer(old: Progress, remembered: Boolean, now: Long): Progress {
        val streak = if (remembered) (old.streak.toLong() + 1).coerceIn(1L,days.size.toLong()).toInt() else 0
        val delay = if (remembered) days[streak - 1] * 86_400_000L else 10 * 60_000L
        val attempts = (old.attempts.toLong() + 1).coerceIn(0L,Int.MAX_VALUE.toLong()).toInt()
        val correct = (old.correct.toLong() + if (remembered) 1 else 0).coerceIn(0L,attempts.toLong()).toInt()
        return Progress(streak, now + delay, attempts, correct)
    }
}
