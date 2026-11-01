package com.vime.ime

import com.vime.ime.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DomainPacksTest {
    private val root=listOf(File("."),File("..")).first { File(it,"offline-packs/domain-packs.json").isFile }
    private val folder=File(root,"offline-packs")
    @Test fun everyDomainSeedHasAnImportableEnglishEntryAndAllRowsAreQueryable() {
        val spec=JSONObject(File(root,"data/domain-seeds.json").readText())
        val metadata=JSONObject(File(folder,"domain-packs.json").readText()).getJSONObject("domains")
        assertEquals(8,metadata.length())
        for(key in metadata.keys()) {
            val meta=metadata.getJSONObject(key);val pack=File(folder,meta.getString("file")).inputStream().use(LexiconPackCodec::read)
            assertEquals("jianyi-domain-$key",pack.id);assertEquals(meta.getInt("entries"),pack.entries.size)
            assertTrue(pack.notice.contains("CC-CEDICT"));assertTrue(pack.licenseText.contains("Attribution-ShareAlike"))
            val entries=pack.entries;val byCode=entries.groupBy { it.pinyin }.mapValues { it.value.map { row -> row.word }.distinct() }
            val index=CandidateIndex(byCode,byCode.keys.associateWith { 1 },entries.associate { it.word to it.gloss },emptyMap())
            val seeds=spec.getJSONObject(key).getString("words").split(' ')
            assertTrue("Missing seeds in $key",entries.map { it.word }.containsAll(seeds))
            for(entry in entries) {
                assertTrue("$key ${entry.word}",entry.gloss.any { it in 'a'..'z' || it in 'A'..'Z' })
                assertTrue("$key ${entry.word} ${entry.pinyin}",index.query(entry.pinyin,limit=100).any { it.word==entry.word && it.en.isNotBlank() })
            }
        }
    }
    private fun rewrite(pack:ByteArray,edit:(String,ByteArray)->ByteArray):ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { result -> ZipInputStream(pack.inputStream()).use { source ->
            while(true) { val entry=source.nextEntry?:break;result.putNextEntry(ZipEntry(entry.name));result.write(edit(entry.name,source.readBytes()));result.closeEntry() }
        } }
        return out.toByteArray()
    }
    @Test fun checksumAndUnsafeIdsFailWhileLegacyPacksStillDecode() {
        val bytes=File(folder,"Jianyi-it-2026.11.01.vimelex").readBytes()
        val corrupt=rewrite(bytes) { name,data -> if(name=="entries.tsv") data+"bad".toByteArray() else data }
        assertThrows(IllegalArgumentException::class.java) { LexiconPackCodec.read(corrupt.inputStream()) }
        val unsafe=rewrite(bytes) { name,data -> if(name=="manifest.properties") data.toString(Charsets.UTF_8).replace("id=jianyi-domain-it","id=../../bad").toByteArray() else data }
        assertThrows(IllegalArgumentException::class.java) { LexiconPackCodec.read(unsafe.inputStream()) }
        val legacy=rewrite(bytes) { name,data -> if(name=="manifest.properties") data.toString(Charsets.UTF_8).replace("id=jianyi-domain-it\n","").toByteArray() else data }
        assertTrue(LexiconPackCodec.read(legacy.inputStream()).id.startsWith("legacy-"))
    }
}
