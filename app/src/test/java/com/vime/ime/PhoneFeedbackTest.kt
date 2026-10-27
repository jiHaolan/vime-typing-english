package com.vime.ime

import com.vime.ime.engine.KeyboardTuning
import com.vime.ime.engine.LearningStats
import com.vime.ime.engine.VoiceSessionGate
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class PhoneFeedbackTest {
    @Test fun resizeNeverStoresUnrecoverableValues() {
        assertEquals(70, KeyboardTuning.height(-1)); assertEquals(150, KeyboardTuning.height(999))
        assertEquals(65, KeyboardTuning.width(0)); assertEquals(100, KeyboardTuning.width(130))
        assertEquals(0, KeyboardTuning.position(-1)); assertEquals(100, KeyboardTuning.position(101))
        assertEquals(0, KeyboardTuning.bottom(-1)); assertEquals(48, KeyboardTuning.bottom(100))
    }
    @Test fun hapticOffNeverVibratesAndLevelsIncrease() {
        assertEquals(0L, KeyboardTuning.duration(0, true))
        assertTrue(KeyboardTuning.duration(1) < KeyboardTuning.duration(2))
        assertTrue(KeyboardTuning.amplitude(2) < KeyboardTuning.amplitude(3))
        assertEquals(52L, KeyboardTuning.duration(3, true))
    }
    @Test fun canceledVoiceCannotCommitLateResult() {
        val gate = VoiceSessionGate(); val old = gate.start()
        assertTrue(gate.accepts(old)); gate.cancel(); assertFalse(gate.accepts(old))
    }
    @Test fun replacementVoiceRejectsPreviousSession() {
        val gate = VoiceSessionGate(); val first = gate.start(); val second = gate.start()
        assertFalse(gate.accepts(first)); assertTrue(gate.accepts(second))
        gate.cancel(); assertFalse(gate.accepts(second))
    }
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun time(day: Int, hour: Int = 0) = Calendar.getInstance(zone).apply {
        clear(); set(2026, Calendar.OCTOBER, day, hour, 0, 0)
    }.timeInMillis
    @Test fun actualHistoryUsesLocalDayAndExcludesFutureEvents() {
        val stats = LearningStats.recent(listOf(
            LearningStats.Event("a", time(22, 23), true), LearningStats.Event("b", time(23), false),
            LearningStats.Event("c", time(23, 20), true), LearningStats.Event("d", time(16), true)), time(23, 12), zone)
        assertEquals(7, stats.size); assertEquals(1, stats[5].count); assertEquals(1, stats[6].count)
        assertEquals(1, stats.sumOf { it.remembered }); assertEquals(2, stats.sumOf { it.count })
    }
    @Test fun emptyHistoryDoesNotFabricateOldDatesOrReviews() {
        assertTrue(LearningStats.recent(emptyList(), time(23), zone).all { it.count == 0 })
    }
    @Test fun forecastIncludesOverdueTodayButNotBeyondWindow() {
        val days = LearningStats.forecast(listOf(0, time(22), time(23, 20), time(24), time(29, 23), time(30)), time(23, 12), zone)
        assertEquals(3, days[0].count); assertEquals(1, days[1].count); assertEquals(1, days[6].count)
        assertEquals(5, days.sumOf { it.count })
    }
    @Test fun illustrativeCurveIsMonotonicBoundedAndHasDeclaredHalfLife() {
        assertEquals(100.0, LearningStats.retention(-3.0, 7.0), .001)
        assertEquals(50.0, LearningStats.retention(7.0, 7.0), .001)
        assertTrue(LearningStats.retention(14.0, 7.0) < LearningStats.retention(7.0, 7.0))
        assertEquals(30.0, LearningStats.interval(999), .001)
        assertTrue(LearningStats.retention(100000.0, .0) >= 0)
    }
    @Test fun daylightSavingDayDoesNotAssumeTwentyFourHours() {
        val z = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(z).apply { clear(); set(2026, Calendar.MARCH, 9, 12, 0) }.timeInMillis
        val result = LearningStats.recent(emptyList(), now, z)
        assertEquals(23L * 60 * 60 * 1000, result[6].at - result[5].at)
    }
}
