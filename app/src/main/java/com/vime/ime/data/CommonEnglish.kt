package com.vime.ime.data

import java.security.MessageDigest

/** Small, separately attributed supplements. Unknown words never get invented labels. */
class CommonEnglish(bytes:ByteArray,expectedSha:String) {
    data class Entry(val code:String,val word:String,val gloss:String,val reading:String,val kind:String)
    val entries:List<Entry>
    private val words:Map<String,Entry>
    init {
        require(bytes.size in 1..2*1024*1024)
        require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }==expectedSha)
        entries=bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.map { line ->
            val parts=line.split('\t');require(parts.size==5)
            val (code,word,gloss,reading,kind)=parts
            require(code.matches(Regex("[a-z]{1,64}")) && word.length in 1..64)
            require(gloss.length in 1..160 && gloss.any { it in 'a'..'z' || it in 'A'..'Z' })
            require(reading.length in 1..160 && kind in listOf("authored","measure","proper-name"))
            require(parts.all { field -> field.none { it<' ' || it=='\u007f' } })
            Entry(code,word,gloss,reading,kind)
        }.toList()
        require(entries.size in 1..20000 && entries.map { it.word }.distinct().size==entries.size)
        words=entries.associateBy { it.word }
    }
    fun match(word:String,hint:String=""):Entry? {
        val entry=words[word]?:return null
        if(hint.isEmpty()) return entry
        val raw=hint.lowercase().replace("'","")
        if(raw.isEmpty() || raw.length>128) return null
        val nine=raw.all { it in '2'..'9' }
        if(nine) return entry.takeIf { it.code.map(CandidateIndex::digit).joinToString("").startsWith(raw) }
        if(raw.any { it !in 'a'..'z' }) return null
        if(entry.code.startsWith(raw)) return entry
        var positions=setOf(0)
        for(syllable in entry.reading.lowercase().split(' ')) {
            val options=if(syllable.startsWith("zh") || syllable.startsWith("ch") || syllable.startsWith("sh")) listOf(syllable.take(1),syllable.take(2)) else listOf(syllable.take(1))
            positions=positions.flatMap { at -> options.filter { raw.startsWith(it,at) }.map { at+it.length } }.toSet()
        }
        return entry.takeIf { raw.length in positions }
    }
}
