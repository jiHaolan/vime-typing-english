package com.vime.ime

import com.vime.ime.data.CandidateIndex
import com.vime.ime.engine.ClipboardSession
import com.vime.ime.engine.InputRules
import com.vime.ime.engine.ReviewSchedule
import org.junit.Assert.*
import org.junit.Test

class CoreRegressionTest {
    private fun dictionary() = CandidateIndex(
        mapOf("wo" to listOf("我"), "ai" to listOf("爱"), "ni" to listOf("你"), "nihao" to listOf("你好")),
        mapOf("wo" to 900, "nihao" to 800, "ni" to 700),
        mapOf("我" to "I", "你" to "you", "你好" to "hello"), emptyMap())

    @Test fun exactMatchRetainsConsumption() {
        val c = dictionary().query("nihao").first()
        assertEquals("你好", c.word); assertEquals(5, c.consumed)
    }
    @Test fun incompletePinyinHasCandidates() { assertTrue(dictionary().query("nih").any { it.word == "你好" }) }
    @Test fun unknownPhraseConsumesLeftNotTail() {
        val c = dictionary().query("woaini").first { it.word == "我" }
        assertEquals("我", c.word); assertEquals(2, c.consumed)
        assertEquals("aini", InputRules.remaining("woaini", c.consumed))
    }
    @Test fun unknownInputIsNotReplacedByUnrelatedTail() { assertTrue(dictionary().query("xxxni").isEmpty()) }
    @Test fun t9PrefixHasCandidates() { assertTrue(dictionary().query("64", true).any { it.word == "你好" }) }
    @Test fun t9ExactHasCorrectWord() { assertEquals("你好", dictionary().query("64426", true).first().word) }
    @Test fun t9InvalidDigitRejected() { assertTrue(dictionary().query("10", true).isEmpty()) }
    @Test fun queryLimitAndDedupe() { assertEquals(1, dictionary().query("n", limit = 1).size) }
    @Test fun emptyAndOversizedQuerySafe() {
        assertTrue(dictionary().query("").isEmpty()); assertTrue(dictionary().query("n".repeat(65)).isEmpty())
    }
    @Test fun passwordsNeverLearn() {
        for (type in listOf(0x81, 0x91, 0xe1, 0x12)) {
            assertTrue(InputRules.password(type)); assertFalse(InputRules.learningAllowed(type, 0))
        }
        assertFalse(InputRules.password(1)); assertFalse(InputRules.learningAllowed(1, 0x1000000))
    }
    @Test fun numericAndEmailPolicy() { assertTrue(InputRules.numeric(2)); assertTrue(InputRules.ascii(0x21)) }
    @Test fun noSuggestionsIsNotAnAsciiRequirement() { assertFalse(InputRules.ascii(1 or 0x80000)) }
    @Test fun explicitForceAsciiIsRespected() { assertTrue(InputRules.ascii(1, Int.MIN_VALUE)) }
    @Test fun forgottenReturnsInTenMinutes() {
        val result = ReviewSchedule.answer(ReviewSchedule.Progress(4, 0, 8, 6), false, 1000)
        assertEquals(0, result.streak); assertEquals(601000, result.dueAt); assertEquals(9, result.attempts)
    }
    @Test fun rememberedSpacingCapsAtThirtyDays() {
        var p = ReviewSchedule.Progress()
        for (day in listOf(1, 3, 7, 14, 30, 30)) {
            p = ReviewSchedule.answer(p, true, 0)
            assertEquals(day * 86400000L, p.dueAt)
        }
    }
    @Test fun clipboardRejectsSensitiveAndOverlong() {
        val c = ClipboardSession()
        assertFalse(c.save("secret", true, 0)); assertFalse(c.save("x".repeat(2001), false, 0))
        assertTrue(c.list(0).isEmpty())
    }
    @Test fun clipboardExpiresAndDeduplicates() {
        val c = ClipboardSession()
        c.save("hello", false, 0); c.save("hello", false, 5)
        assertEquals(1, c.list(5).size); assertTrue(c.list(600005).isEmpty())
    }
    @Test fun clipboardBoundedAndClearable() {
        val c = ClipboardSession()
        repeat(20) { c.save("text$it", false, it.toLong()) }
        assertEquals(10, c.list(20).size); c.clear(); assertTrue(c.list(20).isEmpty())
    }
}
