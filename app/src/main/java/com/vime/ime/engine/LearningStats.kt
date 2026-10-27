package com.vime.ime.engine

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.ln

object LearningStats {
    data class Event(val word: String, val at: Long, val remembered: Boolean)
    data class Day(val at: Long, val count: Int, val remembered: Int)
    private fun midnight(at: Long, zone: TimeZone): Calendar = Calendar.getInstance(zone).apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    fun recent(events: List<Event>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Day> {
        val day = midnight(now, zone).apply { add(Calendar.DAY_OF_YEAR, -6) }
        return (0..6).map {
            val start = day.timeInMillis; day.add(Calendar.DAY_OF_YEAR, 1); val end = day.timeInMillis
            val answers = events.filter { it.at in start until end && it.at <= now }
            Day(start, answers.size, answers.count { it.remembered })
        }
    }
    /** Due dates at or before now are grouped in today, including overdue words. */
    fun forecast(due: List<Long>, now: Long, zone: TimeZone = TimeZone.getDefault()): List<Day> {
        val day = midnight(now, zone)
        return (0..6).map { i ->
            val start = day.timeInMillis; day.add(Calendar.DAY_OF_YEAR, 1); val end = day.timeInMillis
            Day(start, due.count { it < end && (i == 0 || it >= start) }, 0)
        }
    }
    /** Illustrative exponential decay. Half-life is the next scheduled interval, NOT a fitted memory measurement. */
    fun retention(days: Double, halfLifeDays: Double): Double =
        (100.0 * exp(-ln(2.0) * days.coerceAtLeast(0.0) / halfLifeDays.coerceIn(10.0 / 1440.0, 30.0))).coerceIn(0.0, 100.0)
    fun interval(streak: Int): Double = intArrayOf(1, 3, 7, 14, 30)[(streak - 1).coerceIn(0, 4)].toDouble()
}
