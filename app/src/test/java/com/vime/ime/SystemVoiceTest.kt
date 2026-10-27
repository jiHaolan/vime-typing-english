package com.vime.ime

import com.vime.ime.engine.HeldSpeechResult
import com.vime.ime.voice.SystemVoiceServices
import org.junit.Assert.*
import org.junit.Test

class SystemVoiceTest {
    @Test fun earlyFinalResultWaitsForFingerUp() {
        val s=HeldSpeechResult()
        assertNull(s.result(listOf("  中文 English  ")))
        assertEquals("中文 English",s.release())
        assertNull(s.release());assertNull(s.result(listOf("重复")))
    }
    @Test fun delayedFinalResultIsTakenOnceAfterRelease() {
        val s=HeldSpeechResult();assertNull(s.release())
        assertEquals("Hello",s.result(listOf("", "Hello")))
        assertNull(s.result(listOf("again")))
    }
    @Test fun cancelDiscardsAnAlreadyAvailableResult() {
        val s=HeldSpeechResult();s.result(listOf("不应输入"));s.cancel()
        assertNull(s.release());assertNull(s.result(listOf("迟到结果")))
    }
    @Test fun cancelAfterReleaseDiscardsLateResults() {
        val s=HeldSpeechResult();s.release();s.cancel()
        assertNull(s.result(listOf("不应输入")))
    }
    @Test fun duplicateFinalBeforeReleaseKeepsFirstResult() {
        val s=HeldSpeechResult();s.result(listOf("first"));s.result(listOf("second"))
        assertEquals("first",s.release())
    }
    @Test fun invalidResultsNeverProduceText() {
        for(values in listOf(null,emptyList(),listOf(" "),listOf("x".repeat(4001)),listOf("a\u0000b"))) {
            val s=HeldSpeechResult();s.release()
            assertThrows(IllegalArgumentException::class.java) { s.result(values) }
        }
    }
    @Test fun missingLanguageAndPermissionHaveActionableErrors() {
        assertTrue(SystemVoiceServices.error(13).contains("语言包"))
        assertTrue(SystemVoiceServices.error(9).contains("权限"))
        assertTrue(SystemVoiceServices.error(8).contains("正忙"))
    }
}
