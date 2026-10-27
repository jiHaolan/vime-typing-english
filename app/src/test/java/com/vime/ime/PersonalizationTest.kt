package com.vime.ime

import com.vime.ime.data.*
import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    @Test fun phrasesPreserveUnicodePriorityAndDisabledState() {
        val rules = listOf(PhraseRule("zzcs", "测试短语😀", 99), PhraseRule("addr", "第一行\n第二行", 1, false))
        assertEquals(rules, PhraseCodec.read(PhraseCodec.write(rules)))
    }
    @Test fun invalidImportRejectsWholeDocument() {
        val good = PhraseCodec.write(listOf(PhraseRule("abc", "有效")))
        assertThrows(IllegalArgumentException::class.java) { PhraseCodec.read(good.replace("abc", "../bad")) }
        assertThrows(IllegalArgumentException::class.java) { PhraseCodec.validate(PhraseRule("abc", "\u0000")) }
        assertThrows(IllegalArgumentException::class.java) { PhraseCodec.write(List(1001) { PhraseRule("a", "词") }) }
    }
    @Test fun customThemeRoundTripAndReadableContrast() {
        val colors = CustomColors(name = "夜间", key = "#18213D", keyboard = "#111827", ink = "#FFFFFF", panel = "#202A40")
        assertEquals(colors, ThemeCodec.read(ThemeCodec.write(colors)))
        assertEquals(21.0, ThemeCodec.contrast(ThemeCodec.color("#000000"), ThemeCodec.color("#FFFFFF")), .00001)
    }
    @Test fun unreadableAndMalformedThemesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.validate(CustomColors(ink = "#FFFFFF")) }
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.color("#12345") }
        assertThrows(IllegalArgumentException::class.java) { ThemeCodec.read("x".repeat(16385)) }
    }
}
