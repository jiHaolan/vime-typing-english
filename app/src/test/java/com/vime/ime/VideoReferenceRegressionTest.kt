package com.vime.ime

import com.vime.ime.data.KeyHints
import com.vime.ime.data.KeyboardExtras
import com.vime.ime.engine.KeyHoldPolicy
import com.vime.ime.engine.LocalFormatting
import com.vime.ime.engine.VoiceDraft
import org.junit.Assert.*
import org.junit.Test

class VideoReferenceRegressionTest {
    @Test fun topRowHintsMatchDigits() {
        assertEquals("1234567890", "qwertyuiop".map { KeyHints.secondary(it.code) }.joinToString(""))
    }
    @Test fun allLetterHintsHaveActualOutputs() {
        assertTrue(('a'..'z').all { !KeyHints.secondary(it.code).isNullOrBlank() })
        assertNull(KeyHints.secondary(-5)); assertNull(KeyHints.secondary(32))
    }
    @Test fun tapBackspaceDeletesOneNotClear() {
        assertEquals(KeyHoldPolicy.Output.Key(-5), KeyHoldPolicy.release(-5, false, true))
    }
    @Test fun armedBackspaceClearsOnlyOnSameKeyRelease() {
        assertEquals(KeyHoldPolicy.Output.Key(KeyHoldPolicy.CLEAR), KeyHoldPolicy.release(-5, true, true))
        assertNull(KeyHoldPolicy.release(-5, true, false))
    }
    @Test fun heldLetterEmitsItsHintNotOriginalLetter() {
        assertEquals(KeyHoldPolicy.Output.Text("1"), KeyHoldPolicy.release('q'.code, true, true))
        assertEquals(KeyHoldPolicy.Output.Key('q'.code), KeyHoldPolicy.release('q'.code, false, true))
        assertNull(KeyHoldPolicy.release('q'.code, true, false))
    }
    @Test fun longSpaceInvokesVoiceOnlyOnSameKeyRelease() {
        assertNull(KeyHoldPolicy.release(32, true, true))
        assertEquals(KeyHoldPolicy.Output.Key(32), KeyHoldPolicy.release(32, false, true))
        assertNull(KeyHoldPolicy.release(32, true, false))
    }
    @Test fun symbolCatalogHasAllFourOfflineCategories() {
        assertEquals(setOf("中文", "英文", "数学", "网络"), KeyboardExtras.symbols.keys)
        assertTrue(KeyboardExtras.symbols.values.all { it.size >= 20 })
    }
    @Test fun emojiIncludesCompleteSequencesNotSplitCodeUnits() {
        assertTrue("❤️" in KeyboardExtras.allEmoji)
        assertTrue("😂" in KeyboardExtras.allEmoji)
        assertTrue(KeyboardExtras.emoji["颜文字"]!!.any { it.length > 4 })
    }
    @Test fun recentPicksMoveToFrontAndDeduplicate() {
        assertEquals(listOf("！", "，"), KeyboardExtras.recent(listOf("，", "！", "，"), "！", KeyboardExtras.allSymbols))
    }
    @Test fun recentPicksRejectArbitraryChatAndLimitCapacity() {
        val result = KeyboardExtras.recent(listOf("用户聊天内容", "，", "。", "！"), "私密内容", KeyboardExtras.allSymbols, 2)
        assertEquals(listOf("，", "。"), result)
        assertTrue(KeyboardExtras.recent(result, "，", KeyboardExtras.allSymbols, 0).isEmpty())
    }
    @Test fun localSpacesPreserveLineBoundaries() {
        assertEquals("hello world\n你好", LocalFormatting.apply("  hello   world \n 你好 ", LocalFormatting.Action.SPACES))
    }
    @Test fun localParagraphsKeepOriginalSentences() {
        assertEquals("你好。\n\n再见！", LocalFormatting.apply("你好。再见！", LocalFormatting.Action.PARAGRAPHS))
    }
    @Test fun localBulletsAndUppercaseAreDeterministicNotAI() {
        assertEquals("• 第一行\n• 第二行", LocalFormatting.apply("第一行\n\n 第二行", LocalFormatting.Action.BULLETS))
        assertEquals("HELLO你好", LocalFormatting.apply("hello你好", LocalFormatting.Action.UPPERCASE))
    }
    @Test fun formattingRejectsBlankAndOversizedSelections() {
        assertNull(LocalFormatting.apply(" ", LocalFormatting.Action.SPACES))
        assertNull(LocalFormatting.apply("x".repeat(4001), LocalFormatting.Action.SPACES))
    }
    @Test fun voiceDraftExpiresInTwoMinutes() {
        val draft = VoiceDraft()
        assertTrue(draft.put("测试语音", 100))
        assertEquals("测试语音", draft.peek(120099))
        assertNull(draft.peek(120100))
    }
    @Test fun voiceDraftRejectsOversizedAndBlankResults() {
        val draft = VoiceDraft()
        assertFalse(draft.put(" ", 0)); assertFalse(draft.put("a".repeat(4001), 0))
        assertNull(draft.peek(0))
    }
    @Test fun voiceDraftClearsOnClockResetAndExplicitDiscard() {
        val draft = VoiceDraft()
        draft.put("test", 100); assertNull(draft.peek(99))
        draft.put("test", 100); draft.clear(); assertNull(draft.peek(101))
    }
}
