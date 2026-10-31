package com.vime.ime

import com.vime.ime.data.Cand
import com.vime.ime.data.CommonFrequency
import com.vime.ime.engine.BackspaceGesture
import com.vime.ime.engine.EverydayCandidateOrder
import com.vime.ime.engine.KeyHoldPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class KeyboardUsabilityTest {
    private fun gesture() = BackspaceGesture(36f,48f).apply { begin(1000,100f,100f) }
    @Test fun stationaryHoldDeletesOneEvenAfterFiveSeconds() = assertEquals(-5,gesture().release(6000,100f,100f,true))
    @Test fun holdThenUpClearsOnlyOnRelease() {
        val g=gesture();g.move(1400,100f,60f);assertTrue(g.armed)
        assertEquals(KeyHoldPolicy.CLEAR,g.release(1450,100f,60f,false));assertFalse(g.active)
    }
    @Test fun quickUpwardFlickCannotClearOrTypeAnotherKey() = assertNull(gesture().release(1100,100f,60f,false))
    @Test fun pauseAfterUpwardMoveArmsTheVisibleReleaseHint() {
        val g=gesture();g.move(1100,100f,60f);assertFalse(g.armed);g.refresh(1350);assertTrue(g.armed)
    }
    @Test fun sidewaysSlideCancelsDestructiveGesture() = assertNull(gesture().release(1500,170f,60f,false))
    @Test fun returningToDeleteDisarmsClear() {
        val g=gesture();g.move(1400,100f,60f);assertTrue(g.armed)
        g.move(1500,100f,100f);assertFalse(g.armed);assertEquals(-5,g.release(1550,100f,100f,true))
    }
    @Test fun cancelAndLayoutChangeCannotEmitPendingClear() {
        val g=gesture();g.move(1400,100f,60f);g.cancel();assertNull(g.release(1600,100f,60f,false))
    }
    @Test fun supplementalFrequencyPromotesFamiliarWordButKeepsAllCandidates() {
        val words=listOf("谥号","嗜好","十号").map { Cand(it,"","",6) }
        val ranked=EverydayCandidateOrder.rank(words,{ mapOf("嗜好" to 188,"谥号" to 78,"十号" to 9)[it]?:0 },{0})
        assertEquals(listOf("嗜好","谥号","十号"),ranked.map { it.word });assertEquals(words.toSet(),ranked.toSet())
    }
    @Test fun readingOrderAndConsumptionAreNotOverriddenByWordFrequency() {
        val words=listOf(Cand("额","","",1),Cand("我","","",1),Cand("你好","","",5),Cand("你","","",2))
        assertEquals(words,EverydayCandidateOrder.rank(words,{1_000_000},{0}))
    }
    @Test fun repeatedIntentionalChoiceCanPromoteUncommonWord() {
        val words=listOf(Cand("嗜好","","",6),Cand("谥号","","",6))
        assertEquals("谥号",EverydayCandidateOrder.rank(words,{100},{if(it=="谥号")2 else 0}).first().word)
    }
    @Test fun actualTableResolvesKnownAndMissingWords() {
        val table=CommonFrequency(File("src/main/assets/common-frequency.bin").readBytes())
        assertEquals(188,table.get("嗜好"));assertEquals(78,table.get("谥号"));assertEquals(34387,table.get("世界"))
        assertEquals(0,table.get("妳好"));assertEquals(0,table.get("word"))
    }
    @Test fun truncatedFrequencyTableIsRejected() {
        val raw=File("src/main/assets/common-frequency.bin").readBytes()
        try { CommonFrequency(raw.copyOf(raw.size-1));fail("Truncation accepted") } catch(_:IllegalArgumentException) {}
    }
}
