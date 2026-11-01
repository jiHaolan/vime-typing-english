package com.vime.ime

import com.vime.ime.data.CommonEnglish
import com.vime.ime.data.CandidateIndex
import com.vime.ime.data.MappedEnglishIndex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommonEnglishCoverageTest {
    private val root=listOf(File("."),File("..")).first { File(it,"data/common-baseline.tsv").isFile }
    private val assets=File(root,"app/src/main/assets")
    private fun supplement()=CommonEnglish(File(assets,"common-english.tsv").readBytes(),JSONObject(File(assets,"common-english.json").readText()).getString("sha256"))
    @Test fun everyFixedHighFrequencyWordHasAnEnglishLabel() {
        val english=MappedEnglishIndex.open(File(assets,"english/english.lex"));val common=supplement()
        val baseline=(File(root,"data/common-baseline.tsv").readLines()+File(root,"data/common-characters.tsv").readLines()).filter { it.isNotBlank() }.map { it.substringBefore('\t') }
        assertEquals(10500,baseline.size);assertEquals(10500,baseline.distinct().size)
        val missing=baseline.filter { word -> english.label(word).none { c -> c in 'A'..'Z' || c in 'a'..'z' } && common.match(word)?.gloss.orEmpty().none { c -> c in 'A'..'Z' || c in 'a'..'z' } }
        assertTrue("Missing actual English labels: $missing",missing.isEmpty())
    }
    @Test fun supplementalReadingsNineKeyInitialsAndUnknownsAreBounded() {
        val common=supplement();assertTrue(common.entries.size>1100)
        for(entry in common.entries) {
            assertEquals(entry,common.match(entry.word,entry.code))
            assertEquals(entry,common.match(entry.word,entry.code.uppercase()))
            assertEquals(entry,common.match(entry.word,entry.code.map(CandidateIndex::digit).joinToString("")))
            assertEquals(entry,common.match(entry.word,entry.reading.split(' ').joinToString("") { it.take(1) }))
            assertNull(common.match(entry.word,"invalidx"))
        }
        assertNull(common.match("未收录测试词甲乙丙"));assertNull(common.match("很多","!"))
        assertNull(common.match("大夫","dafu"));assertEquals("doctor",common.match("大夫","daifu")?.gloss)
        assertEquals("heavy",common.match("重","zhong")?.gloss);assertNull(common.match("重","chong"))
    }
    @Test fun shortDailyPhrasesArePresentAndBrokenAssetsAreRejected() {
        val common=supplement();val english=MappedEnglishIndex.open(File(assets,"english/english.lex"))
        for(word in listOf("真的假的","这次","很多","谢谢你","请稍等","没问题","哈哈哈哈哈"))
            assertTrue(word,english.label(word).ifBlank { common.match(word)?.gloss.orEmpty() }.isNotBlank())
        assertThrows(IllegalArgumentException::class.java) { CommonEnglish(File(assets,"common-english.tsv").readBytes(),"0".repeat(64)) }
    }
}
