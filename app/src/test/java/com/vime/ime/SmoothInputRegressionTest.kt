package com.vime.ime

import com.vime.ime.data.*
import com.vime.ime.engine.PointerKeys
import com.vime.ime.engine.QueryGate
import com.vime.ime.engine.OrderedReleases
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SmoothInputRegressionTest {
    @Test fun reusedPointerIdCannotOverwriteAlreadyReleasedPendingTap() {
        val order = OrderedReleases<String>(); order.down(1); order.down(2)
        assertTrue(order.release(2, "i").isEmpty())
        order.down(2)
        assertTrue(order.release(2, "h").isEmpty())
        assertEquals(listOf("n", "i", "h"), order.release(1, "n"))
    }
    @Test fun oppositeThumbReleaseOrderStillEmitsPressOrder() {
        val order = OrderedReleases<String>(); order.down(5); order.down(9)
        assertTrue(order.release(9, "i").isEmpty())
        assertEquals(listOf("n", "i"), order.release(5, "n"))
        assertTrue(order.release(9, "i").isEmpty())
    }
    @Test fun cancelledFirstPointerDoesNotBlockFollowingTap() {
        val order = OrderedReleases<String>(); order.down(1); order.down(2)
        assertTrue(order.release(2, "b").isEmpty()); assertEquals(listOf("b"), order.release(1, null))
    }
    @Test fun layoutCancellationDiscardsQueuedReleases() {
        val order = OrderedReleases<String>(); order.down(1); order.down(2)
        order.release(2, "old"); order.clear(); assertTrue(order.release(1, "old").isEmpty())
    }
    @Test fun twoFingerOverlapDoesNotLoseFirstKey() {
        val p = PointerKeys<String>(); p.down(7, "n"); p.down(11, "i")
        assertEquals("n", p.release(7)); assertEquals("i", p.release(11)); assertEquals(0, p.size)
    }
    @Test fun pointerIdsAreNotIndicesAndReleaseIsOnceOnly() {
        val p = PointerKeys<String>(); p.down(29, "a"); p.down(3, "b")
        assertEquals("b", p.release(3)); assertNull(p.release(3)); assertEquals("a", p.release(29))
    }
    @Test fun cancelledLayoutCannotCommitOldPointers() {
        val p = PointerKeys<String>(); p.down(2, "x"); p.clear(); assertNull(p.release(2))
    }
    @Test fun movedOffKeyCancelsOnlyThatPointer() {
        val p = PointerKeys<String>(); p.down(4, "a"); p.down(5, "b"); p.move(4, null)
        assertNull(p.release(4)); assertEquals("b", p.release(5))
    }
    @Test fun lateCandidatesCannotOverwriteNewInputOrEditor() {
        val gate = QueryGate(); val old = gate.next(); val new = gate.next()
        assertFalse(gate.accepts(old)); assertTrue(gate.accepts(new)); gate.next(); assertFalse(gate.accepts(new))
    }
    @Test fun unknownWholePhraseCanAssembleWithoutLosingLeftPrefix() {
        val index = CandidateIndex(mapOf("wo" to listOf("我"), "ai" to listOf("爱"), "ni" to listOf("你")),
            mapOf("wo" to 900, "ai" to 800, "ni" to 700), emptyMap(), emptyMap())
        val results = index.query("woaini")
        assertEquals("我爱你", results.first().word); assertEquals(6, results.first().consumed)
        assertEquals("", results.first().en); assertEquals(2, results.first { it.word == "我" }.consumed)
    }
    @Test fun shortPrefixesUseFrequencyBeyondFirst512AlphabeticalKeys() {
        val words = (0..700).associate { i -> "a" + ('a' + i / 26) + ('a' + i % 26) to listOf("词$i") }
        val valid = words.filterKeys { it.all { c -> c in 'a'..'z' } }
        val highest = valid.keys.last()
        val index = CandidateIndex(valid, mapOf(highest to 999999), emptyMap(), emptyMap())
        assertEquals(valid.getValue(highest).first(), index.query("a").first().word)
    }
    @Test fun queryCacheSeparatesModesAndLimits() {
        val index = CandidateIndex(mapOf("ni" to listOf("你", "泥")), emptyMap(), emptyMap(), emptyMap())
        assertEquals(1, index.query("ni", limit = 1).size); assertEquals(2, index.query("ni", limit = 2).size)
        assertTrue(index.query("ni", nine = true).isEmpty()); assertEquals("你", index.query("64", nine = true).first().word)
    }

    private fun pack(data: String = "mushu\t木薯\tcassava\tmu4 shu3\n", hash: String? = null,
        format: String = "vimelex-1", extra: String? = null, count: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val files = linkedMapOf("manifest.properties" to "format=$format\nname=Test\nversion=1\nsource=https://example.org/data\nlicense=CC-BY-SA-4.0\nentries=$count\nsha256=${hash ?: LexiconPackCodec.sha256(data.toByteArray())}\n",
                "entries.tsv" to data, "NOTICE.txt" to "Test fixture", "LICENSE.txt" to "Test license")
            if (extra != null) files[extra] = "unexpected"
            for ((name, value) in files) { zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() }
        }
        return out.toByteArray()
    }
    private fun reject(bytes: ByteArray) {
        try { LexiconPackCodec.read(bytes.inputStream()); fail("Invalid data was accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun validPackPreservesReadingGlossAndLicense() {
        val p = LexiconPackCodec.read(pack().inputStream())
        assertEquals("木薯", p.entries.single().word); assertEquals("cassava", p.entries.single().gloss)
        assertEquals("mu4 shu3", p.entries.single().reading); assertEquals("CC-BY-SA-4.0", p.license)
    }
    @Test fun tamperedHashRejected() = reject(pack(hash = "0".repeat(64)))
    @Test fun unsupportedFormatRejected() = reject(pack(format = "vimelex-99"))
    @Test fun pathTraversalRejectedWithoutExtracting() = reject(pack(extra = "../danger"))
    @Test fun unknownExecutableRejected() = reject(pack(extra = "code.dex"))
    @Test fun invalidPinyinRejected() = reject(pack(data = "mu4shu\t木薯\tcassava\tmu4 shu3\n"))
    @Test fun recordCountMismatchRejected() = reject(pack(count = 2))
    @Test fun oversizedFieldRejected() = reject(pack(data = "mushu\t木薯\t${"x".repeat(1001)}\tmu4 shu3\n"))
    @Test fun controlCharactersRejected() = reject(pack(data = "mushu\t木薯\tcass\u0000ava\tmu4 shu3\n"))
}
