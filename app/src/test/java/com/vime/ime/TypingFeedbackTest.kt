package com.vime.ime

import com.vime.ime.engine.EnglishGloss
import com.vime.ime.engine.EnglishText
import com.vime.ime.engine.TouchGeometry
import org.junit.Assert.*
import org.junit.Test

class TypingFeedbackTest {
    private val keys = listOf(TouchGeometry.Box(0f, 0f, 36f, 48f), TouchGeometry.Box(36f, 0f, 72f, 48f),
        TouchGeometry.Box(0f, 54f, 36f, 102f))
    @Test fun smallHorizontalReleaseJitterKeepsOriginal() {
        assertEquals(0, TouchGeometry.hit(keys, 40f, 24f, 0, 7f, 7f))
        assertEquals(1, TouchGeometry.hit(keys, 45f, 24f, 0, 7f, 7f))
    }
    @Test fun rowGapDoesNotLoseTap() {
        assertEquals(0, TouchGeometry.hit(keys, 18f, 50f, gap = 7f))
        assertEquals(0, TouchGeometry.hit(keys, 18f, 54f, 0, 7f, 7f))
        assertEquals(2, TouchGeometry.hit(keys, 18f, 60f, 0, 7f, 7f))
    }
    @Test fun slideOutsideCancels() { assertEquals(-1, TouchGeometry.hit(keys, 100f, 180f, 0, 7f, 7f)) }
    @Test fun glossOnlyUsesFirstSenseWithoutChangingSource() {
        val original = "hello / hi; greetings"
        assertEquals("hello", EnglishGloss.label(original))
        assertEquals("hello / hi; greetings", original)
        assertEquals("cassava", EnglishGloss.label("cassava, a tropical tuber plant"))
        assertEquals("carry out", EnglishGloss.label("v. carry out (a plan); execute"))
    }
    @Test fun longLabelsStayBoundedAndSpeechKeepsWholeSense() {
        val value = "a tropical tuber plant with large roots; cassava"
        assertTrue(EnglishGloss.label(value).length <= 23)
        assertEquals("a tropical tuber plant with large roots", EnglishGloss.primary(value))
    }
    @Test fun englishPrefixDoesNotCrossWordBoundary() {
        assertEquals("Hello", EnglishText.prefix("中文 Hello"))
        assertEquals("can't", EnglishText.prefix("I can't"))
        assertEquals("", EnglishText.prefix("hello "))
        assertEquals("", EnglishText.prefix("abc".repeat(30)))
        assertEquals("", EnglishText.prefix("-abc"))
    }
    @Test fun explicitlyChosenCompletionPreservesCase() {
        assertEquals("hello", EnglishText.matchingCase("hello", "he"))
        assertEquals("Hello", EnglishText.matchingCase("hello", "He"))
        assertEquals("HELLO", EnglishText.matchingCase("hello", "HE"))
    }
}
