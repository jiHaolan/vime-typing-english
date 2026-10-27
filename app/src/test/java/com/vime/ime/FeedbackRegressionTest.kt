package com.vime.ime

import com.vime.ime.data.AssociationIndex
import com.vime.ime.data.CandidateIndex
import com.vime.ime.data.LexiconSupplement
import com.vime.ime.engine.NavigationRules
import org.junit.Assert.*
import org.junit.Test

class FeedbackRegressionTest {
    private fun supplements(): CandidateIndex {
        val entries = LexiconSupplement.entries
        return CandidateIndex(entries.groupBy { it.pinyin }.mapValues { (_, rows) -> rows.map { it.word } },
            emptyMap(), entries.associate { it.word to it.english }, emptyMap())
    }
    @Test fun goldCoinHasEnglishAndConsumesFullPinyin() {
        val c = supplements().query("jinbi").single()
        assertEquals("金币", c.word); assertEquals("gold coin", c.en); assertEquals(5, c.consumed)
    }
    @Test fun cassavaIsAvailableInFullPinyin() {
        val c = supplements().query("mushu").single()
        assertEquals("木薯", c.word); assertEquals("cassava", c.en); assertEquals(5, c.consumed)
    }
    @Test fun supplementsAreAvailableInNineKey() {
        assertTrue(supplements().query("54624", nine = true).any { it.word == "金币" })
        assertTrue(supplements().query("68748", nine = true).any { it.word == "木薯" })
    }
    @Test fun incompleteCassavaPinyinStillSuggestsTheWord() {
        assertTrue(supplements().query("mush").any { it.word == "木薯" })
    }
    @Test fun correctionsHaveSourcesAndNoDuplicateEntries() {
        assertTrue(LexiconSupplement.entries.all { it.source.isNotBlank() && it.reading.isNotBlank() })
        assertEquals(LexiconSupplement.entries.size, LexiconSupplement.entries.map { it.word }.distinct().size)
    }
    @Test fun associationPreservesCuratedOrderAndDeduplicates() {
        assertEquals(listOf("天气", "晚上"), AssociationIndex(mapOf("今天" to listOf("天气", "天气", "", "今天", "晚上"))).next("今天"))
    }
    @Test fun associationUsesLongestKnownSuffix() {
        val index = AssociationIndex(mapOf("好" to listOf("好"), "你好" to listOf("世界")))
        assertEquals(listOf("世界"), index.next("大家你好"))
    }
    @Test fun associationDoesNotInventMatchesOrExceedLimit() {
        val index = AssociationIndex(mapOf("谢谢" to listOf("你", "大家", "支持")))
        assertTrue(index.next("木薯").isEmpty()); assertTrue(index.next("").isEmpty())
        assertTrue(index.next("谢谢", 0).isEmpty()); assertEquals(listOf("你"), index.next("谢谢", 1))
    }
    @Test fun backFromPracticeReturnsToWordBook() {
        assertEquals(NavigationRules.Destination.BOOK, NavigationRules.back(true))
    }
    @Test fun backFromWordBookFinishesRatherThanOpeningSettings() {
        assertEquals(NavigationRules.Destination.FINISH, NavigationRules.back(false))
    }
}
