package com.vime.ime.data

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Complete public senses stay mapped; only requested words and a small text LRU are decoded. */
class MappedEnglishIndex private constructor(private val data:ByteBuffer) {
    data class Form(val reading:String,val senses:List<String>,val sourceRows:List<Int>)
    data class Group(val code:String,val primary:String,val forms:List<Form>)
    private val groups:Int
    private val reverseStart:Int
    private val reverseData:Int
    private val records:Int
    private val poolIndex:Int
    private val poolData:Int
    private val reverseCount:Int
    private val stringCount:Int
    private val blocks:Int
    private val maxString:Int
    val headwords:Int
    val labelledHeadwords:Int
    val gradedHeadwords:Int
    private val strings=object:LinkedHashMap<Int,String>(512,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Int,String>?)=size>512
    }
    init {
        require(data.limit()>=96 && (0 until 8).map { data.get(it).toInt().toChar() }.joinToString("")=="VIMEENG2")
        require(data.getInt(8)==2);groups=data.getInt(12);headwords=data.getInt(16)
        require(groups in 1..2_000_000 && headwords in 1..groups && data.getInt(20)==96)
        reverseStart=data.getInt(24);reverseData=data.getInt(28);records=data.getInt(32)
        poolIndex=data.getInt(36);poolData=data.getInt(40);reverseCount=data.getInt(48)
        stringCount=data.getInt(52);blocks=data.getInt(56);maxString=data.getInt(88)
        labelledHeadwords=data.getInt(64);gradedHeadwords=data.getInt(68)
        require(reverseCount in 1..500_000 && stringCount in 1..2_000_000 && maxString in 1..65535)
        require(reverseStart==96+groups*4 && reverseData==reverseStart+reverseCount*4)
        require(reverseData<records && records<poolIndex && poolIndex.toLong()+blocks*4<poolData && poolData<data.limit())
        require(data.getInt(44)==data.limit() && blocks==(stringCount+63)/64)
        require(data.getInt(80)==0 && data.getInt(84)==64 && data.getInt(92)==0)
        require(labelledHeadwords in 0..headwords && gradedHeadwords in 0..headwords)
    }
    private fun byte(at:Int)=data.get(at).toInt() and 255
    private fun short(at:Int)=data.getShort(at).toInt() and 65535
    private fun utf8(start:Int,size:Int)=String(ByteArray(size) { data.get(start+it) },Charsets.UTF_8)
    private fun text(id:Int):String {
        require(id in 0 until stringCount)
        synchronized(strings) { strings[id]?.let { return it } }
        var cursor=data.getInt(poolIndex+(id/64)*4)
        require(cursor>=poolIndex+blocks*4 && cursor<=poolData-4)
        var relative=data.getInt(cursor);cursor+=4
        repeat(id%64) {
            var value=0;var shift=0
            while(true) {
                require(cursor<poolData && shift<=28)
                val part=byte(cursor++);value=value or ((part and 127) shl shift)
                if(part<128) break
                shift+=7
            }
            require(value>0 && relative.toLong()+value<Int.MAX_VALUE);relative+=value
        }
        require(relative>=0 && poolData.toLong()+relative<data.limit())
        val start=poolData+relative;var end=start
        while(end<data.limit() && byte(end)!=0 && end-start<=maxString) end++
        require(end<data.limit() && end-start<=maxString)
        val result=utf8(start,end-start)
        synchronized(strings) { strings[id]=result };return result
    }
    private fun record(index:Int):Int {
        require(index in 0 until groups)
        val at=data.getInt(96+index*4)
        require(at>=records && at<=poolIndex-14)
        require(short(at)>0 && short(at+2) in 1..128 && data.getInt(at+4) in 0 until stringCount && short(at+12)>0)
        val size=data.getInt(at+8)
        require(size in 1..1024*1024 && at.toLong()+14+short(at)+short(at+2)+size<=poolIndex)
        return at
    }
    private fun word(at:Int)=utf8(at+14,short(at))
    private fun code(at:Int)=utf8(at+14+short(at),short(at+2))
    private fun compareWord(at:Int,target:ByteArray):Int {
        val length=short(at)
        for(i in 0 until minOf(length,target.size)) {
            val difference=byte(at+14+i)-(target[i].toInt() and 255)
            if(difference!=0) return difference
        }
        return length-target.size
    }
    private fun wordRecords(word:String):List<Int> {
        if(word.isEmpty() || word.length>64) return emptyList()
        val raw=word.toByteArray(Charsets.UTF_8);var start=0;var end=groups
        while(start<end) { val mid=(start+end) ushr 1
            if(compareWord(record(mid),raw)<0) start=mid+1 else end=mid }
        val result=ArrayList<Int>()
        while(start<groups) { val at=record(start++);if(compareWord(at,raw)!=0) break;result.add(at) }
        return result
    }
    private fun forms(at:Int):List<Form> {
        var cursor=at+14+short(at)+short(at+2)
        val end=cursor+data.getInt(at+8)
        val result=ArrayList<Form>()
        repeat(short(at+12)) {
            require(cursor<=end-8)
            val reading=data.getInt(cursor);val senses=short(cursor+4);val sources=short(cursor+6);cursor+=8
            require(senses>0 && sources>0 && cursor.toLong()+(senses+sources)*4L<=end)
            val values=List(senses) { text(data.getInt(cursor).also { cursor+=4 }) }
            val ordinals=List(sources) { data.getInt(cursor).also { require(it>0);cursor+=4 } }
            result.add(Form(text(reading),values,ordinals))
        }
        require(cursor==end);return result
    }
    private fun initialsMatch(at:Int,hint:String):Boolean {
        val reading=forms(at).first().reading.lowercase()
        var positions=setOf(0)
        for(syllable in reading.split(' ').filter { it.isNotEmpty() }) {
            val options=if(syllable.startsWith("zh") || syllable.startsWith("ch") || syllable.startsWith("sh")) listOf(syllable.take(1),syllable.take(2)) else listOf(syllable.take(1))
            positions=positions.flatMap { pos -> options.filter { hint.startsWith(it,pos) }.map { pos+it.length } }.toSet()
            if(positions.isEmpty()) return false
        }
        return hint.length in positions
    }
    private fun select(word:String,hint:String):List<Int> {
        val all=wordRecords(word);if(hint.isEmpty()) return all
        val raw=hint.lowercase().replace("'","")
        if(raw.isEmpty() || raw.length>128) return emptyList()
        val nine=raw.all { it in '2'..'9' }
        if(!nine && raw.any { it !in 'a'..'z' }) return emptyList()
        fun typed(at:Int)=code(at).let { if(nine) it.map(CandidateIndex::digit).joinToString("") else it }
        val exact=all.filter { typed(it)==raw };if(exact.isNotEmpty()) return exact
        val prefix=all.filter { typed(it).startsWith(raw) };if(prefix.isNotEmpty()) return prefix
        return if(nine) emptyList() else all.filter { initialsMatch(it,raw) }
    }
    fun resolvedCode(word:String,hint:String)=select(word,hint).singleOrNull()?.let(::code).orEmpty()
    fun label(word:String,hint:String="")=select(word,hint).singleOrNull()?.let { text(data.getInt(it+4)) }.orEmpty()
    fun lookup(word:String,hint:String="")=select(word,hint).map { Group(code(it),text(data.getInt(it+4)),forms(it)) }
    private fun reverseAt(index:Int):Int {
        val at=data.getInt(reverseStart+index*4)
        require(at>=reverseData && at<=records-6 && short(at)>0 && at.toLong()+6+short(at)<=records)
        require(data.getInt(at+2) in 0 until groups);return at
    }
    private fun token(at:Int)=utf8(at+6,short(at))
    fun english(prefix:String,limit:Int=12):List<Pair<String,String>> {
        if(prefix.isEmpty() || prefix.length>64 || limit<=0) return emptyList()
        val raw=prefix.lowercase();if(raw.any { it !in 'a'..'z' && it!='-' }) return emptyList()
        var start=0;var end=reverseCount
        while(start<end) { val mid=(start+end) ushr 1
            if(token(reverseAt(mid))<raw) start=mid+1 else end=mid }
        val result=ArrayList<Pair<String,String>>()
        while(start<reverseCount && result.size<400) {
            val at=reverseAt(start++);val value=token(at);if(!value.startsWith(raw)) break
            result.add(value to word(record(data.getInt(at+2))))
        }
        return result.sortedWith(compareBy<Pair<String,String>> { it.first.length }.thenBy { it.first }).take(limit.coerceAtMost(100))
    }
    companion object {
        fun open(file:File):MappedEnglishIndex=FileInputStream(file).channel.use {
            require(it.size() in 96..Int.MAX_VALUE.toLong())
            MappedEnglishIndex(it.map(FileChannel.MapMode.READ_ONLY,0,it.size()).order(ByteOrder.LITTLE_ENDIAN))
        }
        internal fun fromBytes(bytes:ByteArray)=MappedEnglishIndex(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }
}
