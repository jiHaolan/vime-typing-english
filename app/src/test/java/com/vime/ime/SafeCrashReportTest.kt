package com.vime.ime

import com.vime.ime.engine.SafeCrashReport
import org.junit.Assert.*
import org.junit.Test

class SafeCrashReportTest {
    @Test fun messagesCausesSuppressedAndFileNamesNeverEnterReport() {
        val error=IllegalArgumentException("聊天正文 api_key=secret-value https://host/private/path")
        error.initCause(IllegalStateException("Bearer private-token"))
        error.addSuppressed(RuntimeException("录音文件 /private/audio.wav"))
        error.stackTrace=arrayOf(StackTraceElement("com.vime.ime.InputEngine","selected","private-input-text.kt",42))
        val report=SafeCrashReport.failure(SafeCrashReport.Stage.APP,error,true)
        for(secret in listOf("聊天正文","secret-value","private-token","private/audio.wav","private-input-text.kt","https://host"))
            assertFalse(secret,report.contains(secret))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("com.vime.ime.InputEngine.selected:42"))
    }
    @Test fun reportIsBoundedEvenWithManyLongFrames() {
        val error=RuntimeException("x".repeat(100000))
        error.stackTrace=Array(1000) { StackTraceElement("a".repeat(190),"b".repeat(190),"sensitive-file",it) }
        val text=SafeCrashReport.failure(SafeCrashReport.Stage.KEYBOARD_VIEW,error,false)
        assertTrue(text.startsWith(SafeCrashReport.MAGIC))
        assertTrue(text.length<=SafeCrashReport.MAX_CHARS && text.toByteArray().size<=16384)
        assertFalse(text.contains("sensitive-file"))
    }
    @Test fun cyclicCausesTerminateWithoutDuplicatingPayload() {
        val a=RuntimeException("secret-a");val b=RuntimeException("secret-b");a.initCause(b);b.initCause(a)
        val result=SafeCrashReport.failure(SafeCrashReport.Stage.APP,a,false)
        assertEquals(2,Regex("异常类型：").findAll(result).count())
        assertFalse(result.contains("secret-"))
    }
    @Test fun keyboardMetadataAcceptsOnlyKnownLayoutLabels() {
        val report=SafeCrashReport.layout("private input",-100,Int.MAX_VALUE,Int.MAX_VALUE)
        assertTrue(report.contains("面板：unknown"));assertFalse(report.contains("private input"))
        assertTrue(report.contains("按键：0/1000"));assertTrue(report.contains("高度：10000"))
    }
}
