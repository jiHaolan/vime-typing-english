package com.vime.ime.data

import android.content.Context

/** Public corpora stay mapped. Optional user packs are published atomically. */
object DictRepository {
    private data class PackForm(val code:String,val reading:String,val gloss:String,val source:String)
    private data class Snapshot(val input:InputLexiconStore.BuiltIn,val english:EnglishLexiconStore.BuiltIn,val frequency:CommonFrequency,
        val common:CommonEnglish,val index:CandidateIndex,val packs:List<LexiconPack>,val forms:Map<String,List<PackForm>>,val packRows:Int,val packCodes:Int,
        val reverse:Map<String,String>,val englishKeys:List<String>)
    @Volatile private var snapshot:Snapshot?=null
    enum class State { IDLE,LOADING,READY,FAILED }
    @Volatile var state=State.IDLE;private set
    @Volatile var loadError:String?=null;private set
    @Volatile var packError:String?=null;private set
    val ready get()=snapshot!=null
    val installedPacks get()=snapshot?.packs.orEmpty()
    val installedPack get()=installedPacks.lastOrNull()
    private val tiers=listOf("","CET4","CET6","TOEFL")
    private val tokens=Regex("[A-Za-z][A-Za-z-]+")
    private val suffixes=listOf("ing","ed","es","s","ly","er","est")
    private val gradeCache=object:LinkedHashMap<String,String>(512,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>?)=size>512
    }
    @Synchronized fun load(context:Context) {
        if(ready) return
        state=State.LOADING
        try {
            snapshot=build(context,emptyList())
            val packs=try { LexiconStore.readAll(context) } catch(e:Exception) { packError="扩展词典未启用：${e.message}";emptyList() }
            if(packs.isNotEmpty()) try { snapshot=build(context,packs) } catch(_:Exception) { packError="扩展词典加载失败，继续使用内置词库" }
            state=State.READY;loadError=null
        } catch(e:Exception) { state=State.FAILED;loadError=e.message?:e.javaClass.simpleName }
    }
    @Synchronized internal fun reload(context:Context) { snapshot=null;packError=null;load(context) }
    @Synchronized fun install(context:Context,bytes:ByteArray) {
        LexiconStore.install(context,bytes) { packs ->
            val next=build(context,packs)
            val publish:()->Unit={ snapshot=next;state=State.READY;loadError=null;packError=null }
            publish
        }
    }
    @Synchronized fun uninstall(context:Context,id:String?=null) {
        LexiconStore.remove(context,id) { packs ->
            val next=build(context,packs)
            val publish:()->Unit={ snapshot=next;state=State.READY;loadError=null;packError=null }
            publish
        }
    }
    private fun build(context:Context,packs:List<LexiconPack>):Snapshot {
        val input=InputLexiconStore.load(context);val english=EnglishLexiconStore.load(context)
        val frequency=snapshot?.frequency ?: context.assets.open("common-frequency.bin").use { CommonFrequency(it.readBytes()) }
        val common=snapshot?.common ?: run {
            val meta=context.assets.open("common-english.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
            context.assets.open("common-english.tsv").use { CommonEnglish(it.readBytes(),meta.getString("sha256")) }
        }
        val words=HashMap<String,MutableList<String>>();val glosses=HashMap<String,String>()
        val forms=HashMap<String,MutableList<PackForm>>()
        for(pack in packs.asReversed()) for(entry in pack.entries) {
            val list=words.getOrPut(entry.pinyin) { ArrayList() };if(entry.word !in list) list.add(entry.word)
            glosses.putIfAbsent(entry.word,entry.gloss)
            val matching=forms.getOrPut(entry.word) { ArrayList() }
            if(matching.none { it.code==entry.pinyin }) matching.add(PackForm(entry.pinyin,entry.reading,entry.gloss,"${pack.name} · ${pack.license}"))
        }
        for(entry in common.entries) {
            val list=words.getOrPut(entry.code) { ArrayList() };if(entry.word !in list)list.add(entry.word)
            glosses.putIfAbsent(entry.word,entry.gloss)
        }
        val reverse=HashMap<String,String>()
        for((word,gloss) in glosses.toSortedMap()) for(token in tokens.findAll(gloss)) {
            val key=token.value.lowercase().trim('-');if(key.length>=2) reverse.putIfAbsent(key,word)
        }
        return Snapshot(input,english,frequency,common,CandidateIndex(words.mapValues { it.value.toList() },words.keys.associateWith { 1 },glosses,emptyMap()),
            packs,forms.mapValues { it.value.toList() },packs.sumOf { it.entries.size }+common.entries.size,words.size,reverse,reverse.keys.sorted())
    }
    private fun packForms(s:Snapshot,word:String,hint:String):List<PackForm> {
        val forms=s.forms[word].orEmpty();if(hint.isEmpty()) return forms
        val raw=hint.lowercase().replace("'","");val nine=raw.isNotEmpty() && raw.all { it in '2'..'9' }
        fun typed(form:PackForm)=if(nine) form.code.map(CandidateIndex::digit).joinToString("") else form.code
        val exact=forms.filter { typed(it)==raw };if(exact.isNotEmpty()) return exact
        return forms.filter { typed(it).startsWith(raw) }
    }
    private fun label(s:Snapshot,word:String,hint:String):String {
        val imported=packForms(s,word,hint)
        if(imported.map { it.code }.distinct().size==1) return imported.first().gloss
        val builtin=s.english.index.label(word,hint)
        return builtin.takeIf { value -> value.any { it in 'a'..'z' || it in 'A'..'Z' } } ?: s.common.match(word,hint)?.gloss.orEmpty()
    }
    private fun grade(s:Snapshot,value:String):String {
        synchronized(gradeCache) { gradeCache[value]?.let { return it } }
        var best=0
        for(match in tokens.findAll(value)) {
            val token=match.value.lowercase().trim('-');best=maxOf(best,tiers.indexOf(s.english.levels[token]))
            for(suffix in suffixes) if(token.length>suffix.length+2 && token.endsWith(suffix))
                best=maxOf(best,tiers.indexOf(s.english.levels[token.removeSuffix(suffix)]))
        }
        val result=tiers[best.coerceIn(0,3)];synchronized(gradeCache) { gradeCache[value]=result };return result
    }
    fun candidate(word:String,hint:String,consumed:Int,fromRime:Boolean=false):Cand {
        val s=snapshot?:return Cand(word,"","",consumed,fromRime)
        val imported=packForms(s,word,hint).map { it.code }.distinct()
        val code=if(imported.size==1) imported.first() else s.english.index.resolvedCode(word,hint).ifEmpty { s.common.match(word,hint)?.code.orEmpty() }
        val en=label(s,word,hint)
        return Cand(word,en,grade(s,en),consumed,fromRime,code)
    }
    private fun query(raw:String,nine:Boolean,limit:Int):List<Cand> {
        val s=snapshot?:return emptyList();if(limit<=0) return emptyList()
        val base=s.input.index.query(raw,nine,limit).map { candidate(it.word,it.code,it.consumed) }
        val extra=s.index.query(raw,nine,limit).map {
            candidate(it.word,it.readingCode.ifEmpty { raw },it.consumed).let { next -> if(next.en.isBlank()) next.copy(en=it.en,lvl=it.lvl) else next }
        }
        return (base+extra).distinctBy { it.word }.take(limit.coerceAtMost(100))
    }
    fun candidates(input:String,limit:Int=12)=query(input,false,limit)
    fun t9Candidates(input:String,limit:Int=12)=query(input,true,limit)
    fun optionalCandidates(raw:String,nine:Boolean,limit:Int=32):List<Cand> {
        val s=snapshot?:return emptyList();if(raw.isEmpty() || limit<=0) return emptyList()
        val normalized=raw.lowercase().replace("'","")
        return s.index.query(normalized,nine,limit).filter { c ->
            val code=if(nine) c.readingCode.map(CandidateIndex::digit).joinToString("") else c.readingCode
            code==normalized
        }.map { candidate(it.word,it.readingCode,raw.length) }
    }
    fun enCandidates(prefix:String,limit:Int=12):List<Cand> {
        val s=snapshot?:return emptyList();if(prefix.isEmpty() || limit<=0) return emptyList()
        val builtin=s.english.index.english(prefix,limit).map { (word,translation) -> Cand(word,translation,s.english.levels[word].orEmpty()) }
        val raw=prefix.lowercase();var position=s.englishKeys.binarySearch(raw).let { if(it<0) -it-1 else it }
        val extra=ArrayList<Cand>()
        while(position<s.englishKeys.size && extra.size<400 && s.englishKeys[position].startsWith(raw)) {
            val key=s.englishKeys[position++];extra.add(Cand(key,s.reverse[key].orEmpty(),s.english.levels[key].orEmpty()))
        }
        return (builtin+extra).distinctBy { it.word }.sortedWith(compareBy<Cand> { it.word.length }.thenBy { it.word }).take(limit.coerceAtMost(100))
    }
    fun getEn(word:String,readingCode:String="")=snapshot?.let { label(it,word,readingCode) }.orEmpty()
    fun getLvl(word:String,readingCode:String="")=snapshot?.let { grade(it,label(it,word,readingCode)) }.orEmpty()
    fun getEnLevel(word:String)=snapshot?.english?.levels?.get(word.lowercase()).orEmpty()
    private fun card(s:Snapshot,word:String,hint:String):Card? {
        val imported=packForms(s,word,hint)
        if(imported.isNotEmpty()) {
            val readings=imported.map { it.reading }.distinct()
            val values=imported.map { it.gloss }.distinct()
            return Card(readings.joinToString(" · "),values.first(),values.drop(1).joinToString("；"))
        }
        val forms=s.english.index.lookup(word,hint).flatMap { it.forms }
        val common=s.common.match(word,hint)
        val useCommon=common!=null && s.english.index.label(word,hint).none { it in 'a'..'z' || it in 'A'..'Z' }
        if(useCommon && forms.isEmpty()) {
            checkNotNull(common)
            val source=if(common.kind=="proper-name") "专名转写" else "常用简明释义"
            return Card(common.reading,common.gloss,source)
        }
        if(forms.isEmpty()) return null
        val readings=forms.map { it.reading }.distinct()
        val values=if(readings.size==1) forms.flatMap { it.senses }.distinct()
            else forms.map { it.reading+" · "+it.senses.joinToString("；") }
        if(useCommon) return Card(readings.joinToString(" · "),checkNotNull(common).gloss,values.joinToString("\n"))
        return Card(readings.joinToString(" · "),values.first(),values.drop(1).joinToString("\n"))
    }
    fun getCard(word:String,readingCode:String="")=snapshot?.let { card(it,word,readingCode) }
    fun definition(word:String):WordDefinition? {
        val s=snapshot?:return null;val value=card(s,word,"")?:return null
        val source=s.forms[word]?.map { it.source }?.distinct()?.joinToString("；")
            ?: if(s.english.index.label(word).none { it in 'a'..'z' || it in 'A'..'Z' } && s.common.match(word)!=null) "键忆常用补充 · CC BY-SA 4.0 · 本机离线"
            else "CC-CEDICT · CC BY-SA 4.0 · 本机离线"
        return WordDefinition(word,value.py,listOf(value.d1,value.d2).filter { it.isNotBlank() }.joinToString("\n"),source)
    }
    fun associations(previous:String,limit:Int=12):List<Cand> {
        val s=snapshot?:return emptyList()
        return s.input.associations.next(previous,limit).map { candidate(it,"",0) }
    }
    val pinyinCount get()=snapshot?.let { it.input.codes+it.packCodes }?:0
    fun wordFrequency(word:String)=snapshot?.frequency?.get(word)?:0
    val wordCount get()=snapshot?.let { it.input.entryCount+it.packRows }?:0
    val enCount get()=snapshot?.english?.index?.labelledHeadwords?:0
    val lvlCount get()=snapshot?.english?.index?.gradedHeadwords?:0
}
