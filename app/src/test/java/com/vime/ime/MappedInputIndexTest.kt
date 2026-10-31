package com.vime.ime

import com.vime.ime.data.CandidateIndex
import com.vime.ime.data.MappedInputIndex
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class MappedInputIndexTest {
    private fun full() = MappedInputIndex.open(listOf(File("src/main/assets/input/input.lex"),
        File("app/src/main/assets/input/input.lex")).first { it.exists() })

    @Test fun completeShippedVocabularyHasFullAndNineKeyReadings() {
        val index = full()
        assertEquals(569466, index.count)
        val probes = mapOf("zhendejiade" to "真的假的", "nihao" to "你好", "jinbi" to "金币",
            "mushu" to "木薯", "jiantieban" to "剪贴板", "shurufa" to "输入法", "lixian" to "离线")
        for ((code, word) in probes) {
            assertTrue("full: $code", index.query(code, limit=100).any { it.word==word && it.consumed==code.length })
            val nine = code.map(CandidateIndex::digit).joinToString("")
            assertTrue("nine: $code", index.query(nine, true, 100).any { it.word==word && it.consumed==nine.length })
        }
    }
    @Test fun rareReadingsNeverReceiveInventedCommonWeights() {
        val index = full()
        assertEquals("我", index.query("wo").first().word)
        assertEquals("额", index.query("e").first().word)
        assertNotEquals("给我", index.query("jiwo").firstOrNull()?.word)
        assertNotEquals("你的", index.query("nidi").firstOrNull()?.word)
        assertNotEquals("长大", index.query("changdai").firstOrNull()?.word)
    }
    @Test fun initialsAndDelimitersConsumeOnlyTheInputTheyReplace() {
        val index = full()
        assertTrue(index.query("nh", limit=100).any { it.word=="你好" && it.consumed==2 })
        val delimited = "zhen'de'jia'de"
        assertTrue(index.query(delimited, limit=100).any { it.word=="真的假的" && it.consumed==delimited.length })
        assertTrue(index.query("zhendejiadex", limit=100).any { it.word=="真的假的" && it.consumed=="zhendejiade".length })
    }
    @Test fun completionsAndInvalidInputStayBounded() {
        val index=full()
        for (raw in listOf("a","zh","shi","zhon","zhongg","946","7444","74443")) {
            val rows=index.query(raw, raw.first().isDigit(),100)
            assertTrue(raw,rows.isNotEmpty());assertTrue(rows.size<=100)
            assertEquals(rows.size,rows.map { it.word }.distinct().size)
            assertTrue(rows.all { it.consumed in 1..raw.length })
        }
        for (invalid in listOf("", "'", "hello!", "你", "x".repeat(129))) assertTrue(index.query(invalid).isEmpty())
        assertTrue(index.query("102",true).isEmpty());assertTrue(index.query("ni",limit=0).isEmpty())
        // No exported full syllable starts with99; invalid phonetics need not
        // invent a completion, and are left for raw-text commitment by the IME.
        assertTrue(index.query("999",true).isEmpty())
    }
    @Test fun absoluteMappedReadsAreSafeAcrossCandidateAndCommitThreads() {
        val index=full();val pool=Executors.newFixedThreadPool(4)
        try {
            val jobs=(0 until 4).map { pool.submit<Boolean> {
                repeat(100) { assertEquals("我",index.query("wo").first().word)
                    assertEquals("额",index.query("e").first().word)
                    assertTrue(index.query("nh",limit=100).any { it.word=="你好" }) }
                true
            } }
            jobs.forEach { assertTrue(it.get()) }
        } finally { pool.shutdownNow() }
    }
    @Test fun v2BlockBoundariesAndEvictionMatchTheSerializedBody() {
        val file=listOf(File("src/main/assets/input/input.lex"),File("app/src/main/assets/input/input.lex")).first { it.exists() }
        val bytes=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val index=full();val selected=linkedMapOf<Int,Int>()
        val boundaries=setOf(0,1,62,63,64,65,126,127,128,index.count-1)
        var cursor=bytes.getInt(32)
        repeat(index.count) { position ->
            if(position in boundaries || position%4096==0) selected[position]=cursor
            cursor+=8+(bytes.get(cursor).toInt() and 255)+(bytes.getShort(cursor+2).toInt() and 65535)
        }
        assertEquals(bytes.limit(),cursor)
        val at=MappedInputIndex::class.java.getDeclaredMethod("at",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }
        // More than64 distinct blocks forces eviction. A second reverse pass
        // catches stale block reuse, including the final partially filled block.
        for(entries in listOf(selected.entries.toList(),selected.entries.reversed()))
            for((position,expected) in entries) assertEquals("offset $position",expected,at.invoke(index,0,position))
    }
    private fun fixture(): ByteArray {
        val bytes=ByteBuffer.allocate(102).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("VIMELEX1".toByteArray())
        for(value in intArrayOf(1,1,64,68,72,76,89,102,1,2,160,0,0,0)) bytes.putInt(value)
        repeat(3) { bytes.putInt(89) };bytes.putInt(80)
        bytes.put(0);bytes.put(1);bytes.putShort(1);bytes.put('w'.code.toByte());bytes.putInt(89)
        bytes.put(2);bytes.put(2);bytes.putShort(3);bytes.putInt(123);bytes.put("wo我".toByteArray())
        return bytes.array()
    }
    @Test fun malformedHeadersAndOffsetsAreRejected() {
        assertEquals("我",MappedInputIndex.fromBytes(fixture()).query("wo").first().word)
        for (field in listOf(8,12,16,20,24,28,32,36,40,44,48,52)) {
            val bytes=fixture();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(field,Int.MAX_VALUE)
            assertThrows("header $field",IllegalArgumentException::class.java) { MappedInputIndex.fromBytes(bytes) }
        }
        val indexBytes=fixture();ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(64,0)
        assertThrows(IllegalArgumentException::class.java) { MappedInputIndex.fromBytes(indexBytes).query("wo") }
        val rowBytes=fixture();rowBytes[89]=0
        assertThrows(IllegalArgumentException::class.java) { MappedInputIndex.fromBytes(rowBytes).query("wo") }
    }
}
