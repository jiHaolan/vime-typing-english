package com.vime.ime

import com.vime.ime.data.*
import com.vime.ime.engine.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearningArchiveTest {
    private val entry=WordBook.Entry("学习","learn","CET4",1000,ReviewSchedule.Progress(2,300000,3,2),2000)
    private val data=LearningArchive.Snapshot(listOf(entry),listOf(LearningStats.Event("学习",2000,true)))
    private fun root()=JSONObject(String(LearningArchive.encode(data,5000),Charsets.UTF_8))
    private fun read(o:JSONObject)=LearningArchive.read(o.toString().byteInputStream())
    @Test fun completeProgressAndHistoryRoundTripWithoutSettings() {
        val bytes=LearningArchive.encode(data,5000); val restored=LearningArchive.read(bytes.inputStream())
        assertEquals(5000,restored.createdAt); assertEquals(data,restored.data)
        assertEquals(setOf("format","version","createdAt","entries","history"),root().keys().asSequence().toSet())
    }
    @Test fun emptyBackupIsValidAndBomIsAccepted() {
        val empty=LearningArchive.Snapshot(emptyList(),emptyList())
        assertEquals(empty,LearningArchive.read(("\uFEFF"+String(LearningArchive.encode(empty,0))).byteInputStream()).data)
    }
    @Test fun rejectUnsupportedVersionWrongFormatAndMissingRequiredFields() {
        for(o in listOf(root().put("version",2),root().put("format","other"),root().apply { remove("history") }))
            assertThrows(Exception::class.java) { read(o) }
    }
    @Test fun rejectCoercedNumbersAndBooleans() {
        for(value in listOf("1",1.5,-1,Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) { read(root().put("version",value)) }
        assertThrows(IllegalArgumentException::class.java) { read(root().apply { getJSONArray("history").getJSONObject(0).put("remembered","true") }) }
        assertThrows(IllegalArgumentException::class.java) { read(root().apply { getJSONArray("entries").getJSONObject(0).getJSONObject("progress").put("attempts",2147483648L) }) }
    }
    @Test fun malformedUtf8AndOversizedFileAreRejected() {
        assertThrows(Exception::class.java) { LearningArchive.read(byteArrayOf(0xC3.toByte(),0x28).inputStream()) }
        val endless=object:java.io.InputStream() {
            var left=LearningArchive.MAX_BYTES+1
            override fun read()=if(left-- >0) 32 else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int { if(left<=0)return -1; val n=minOf(left,len); java.util.Arrays.fill(b,off,off+n,32.toByte()); left-=n; return n }
        }
        assertThrows(IllegalArgumentException::class.java) { LearningArchive.read(endless) }
    }
    @Test fun trailingJunkAndUnpairedSurrogateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { LearningArchive.read((root().toString()+" junk").byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) { LearningArchive.encode(data.copy(entries=listOf(entry.copy(word="\uD800")))) }
    }
    @Test fun duplicateWordsOrphanHistoryAndInvalidProgressAreRejected() {
        for(bad in listOf(data.copy(entries=listOf(entry,entry)),data.copy(history=listOf(LearningStats.Event("其他",2000,true))),
            data.copy(entries=listOf(entry.copy(progress=entry.progress.copy(correct=4)))),data.copy(entries=listOf(entry.copy(word="坏\n词")))))
            assertThrows(IllegalArgumentException::class.java) { LearningArchive.encode(bad) }
    }
    @Test fun oldProgressAndDatedHistoryMigrateExactlyAndInvalidJsonIsNotEmptyData() {
        val old="""[{"word":"学习","en":"learn","level":"CET4","saved":1000,"streak":2,"due":300000,"attempts":3,"correct":2,"lastReviewed":2000}]"""
        assertEquals(data,LearningArchive.legacy(old,"""[{"word":"学习","at":2000,"remembered":true}]"""))
        assertThrows(Exception::class.java) { LearningArchive.legacy("[{","[]") }
    }
    @Test fun reviewCountersDoNotWrapNegativeAtImportedIntegerBoundary() {
        val p=ReviewSchedule.answer(ReviewSchedule.Progress(5,0,Int.MAX_VALUE,Int.MAX_VALUE),true,1000)
        assertEquals(Int.MAX_VALUE,p.attempts); assertEquals(Int.MAX_VALUE,p.correct); assertEquals(5,p.streak)
    }
}
