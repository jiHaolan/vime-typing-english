package com.vime.ime

import com.vime.ime.data.MappedEnglishIndex
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MappedEnglishIndexTest {
    private fun asset()=listOf(File("src/main/assets/english/english.lex"),File("app/src/main/assets/english/english.lex")).first { it.exists() }
    private fun index()=MappedEnglishIndex.open(asset())
    @Test fun fullCorpusAndDifferentSpellingsRetainTheirOwnMeanings() {
        val english=index();assertEquals(121093,english.headwords);assertEquals(120194,english.labelledHeadwords)
        assertEquals("heavy",english.label("重","zhong"));assertEquals("to repeat",english.label("重","chong"))
        assertEquals("still",english.label("还","hai"));assertEquals("to pay back",english.label("还","huan"))
        assertEquals("long",english.label("长","chang"));assertEquals("chief",english.label("长","zhang"))
        assertEquals("",english.label("重"));assertEquals("",english.label("行","h"))
        assertEquals("",english.label("行","heng"))
        assertTrue(english.lookup("行","heng").flatMap { it.forms }.flatMap { it.senses }.any { it.startsWith("used in") })
    }
    @Test fun everyToneAndLaterSenseIsAvailableOnTheCard() {
        val forms=index().lookup("好","hao").flatMap { it.forms }
        assertTrue(forms.any { it.reading=="hao3" && "good" in it.senses })
        assertTrue(forms.any { it.reading=="hao4" && "to be fond of; to have a tendency to; to be prone to" in it.senses })
        assertTrue(forms.all { it.sourceRows.isNotEmpty() && it.sourceRows.all { id -> id>0 } })
    }
    @Test fun completionsNineKeyAndDigraphInitialsCanResolveUniqueReadings() {
        val english=index()
        for(raw in listOf("nihao","ni","nh","64426")) assertEquals("nihao",english.resolvedCode("你好",raw))
        assertEquals("zhonghua",english.resolvedCode("中华","zhh"))
        assertEquals("zhang",english.resolvedCode("长","94264"))
        assertEquals("chang",english.resolvedCode("长","24264"))
        assertEquals("",english.resolvedCode("长","x"))
    }
    @Test fun reverseEnglishIsBoundedAndKeepsUnknownsEmpty() {
        val english=index()
        assertTrue(english.english("stud",100).any { it.first=="study" && it.second.isNotBlank() })
        assertEquals(english.english("stud"),english.english("STUD"))
        assertTrue(english.english("").isEmpty());assertTrue(english.english("a!b").isEmpty())
        assertTrue(english.lookup("未收录测试词x").isEmpty());assertTrue(english.english("stud",0).isEmpty())
        assertTrue(english.english("a",1000).size<=100)
    }
    @Test fun mappedReadsAndTextCacheSupportConcurrentLookups() {
        val english=index();val pool=Executors.newFixedThreadPool(4)
        try { (0 until 4).map { pool.submit<Boolean> { repeat(100) {
            assertEquals("heavy",english.label("重","zhong"));assertEquals("long",english.label("长","chang"))
            assertTrue(english.lookup("好","hao").first().forms.size>=2)
        };true } }.forEach { assertTrue(it.get()) } } finally { pool.shutdownNow() }
    }
    @Test fun malformedHeadersAndMappedRecordReferencesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { MappedEnglishIndex.fromBytes(ByteArray(96)) }
        for(field in listOf(8,12,16,20,24,28,32,36,40,44,48,52,56,80,84,88,92)) {
            val bytes=asset().readBytes();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(field,Int.MAX_VALUE)
            assertThrows("header $field",IllegalArgumentException::class.java) { MappedEnglishIndex.fromBytes(bytes) }
        }
        val bytes=asset().readBytes();val view=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        view.putInt(96+(view.getInt(12)/2)*4,0)
        assertThrows(IllegalArgumentException::class.java) { MappedEnglishIndex.fromBytes(bytes).label("重","zhong") }
    }
}
