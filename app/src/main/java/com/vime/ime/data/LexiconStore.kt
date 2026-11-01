package com.vime.ime.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

object LexiconStore {
    const val MAX_PACKS=16
    private const val MAX_TOTAL_BYTES=32*1024*1024
    private const val MAX_TOTAL_ENTRIES=240000
    private fun target(context: Context) = File(context.filesDir, "dictionary/active.vimelex")
    private fun catalog(context:Context)=AtomicFile(File(context.filesDir,"dictionary/installed.json"))
    private fun blob(context:Context,hash:String):File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(context.filesDir,"dictionary/packs/$hash.vimelex")
    }
    private fun bytes(context:Context):List<ByteArray> {
        val index=catalog(context)
        if(index.baseFile.exists() || File(index.baseFile.path+".bak").exists()) {
            val raw=index.openRead().use { bounded(it,64*1024) }
            val json=JSONObject(raw.toString(Charsets.UTF_8));require(json.getInt("version")==1)
            val list=json.getJSONArray("files");require(list.length() in 0..MAX_PACKS)
            val hashes=List(list.length()) { list.getString(it) };require(hashes.distinct().size==hashes.size)
            var total=0
            return hashes.map { hash ->
                blob(context,hash).inputStream().use { boundedBytes(it) }.also {
                    total+=it.size;require(total<=MAX_TOTAL_BYTES) { "扩展词库总大小超出32MB" }
                    require(LexiconPackCodec.sha256(it)==hash) { "已安装词包损坏" }
                }
            }
        }
        val atomic = AtomicFile(target(context))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
        return listOf(atomic.openRead().use(::boundedBytes))
    }
    @Synchronized fun readAll(context:Context)=bytes(context).map { LexiconPackCodec.read(it.inputStream()) }.also(::validate)
    @Synchronized fun read(context:Context)=readAll(context).lastOrNull()
    private fun validate(packs:List<LexiconPack>) {
        require(packs.size<=MAX_PACKS) { "最多安装16个扩展词包" }
        require(packs.map { it.id }.distinct().size==packs.size) { "词包标识重复" }
        require(packs.sumOf { it.entries.size }<=MAX_TOTAL_ENTRIES) { "扩展词条总数超出24万" }
    }
    private fun bounded(input:InputStream,limit:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        input.use { while(true) { val n=it.read(buffer);if(n<0)break;require(out.size()+n<=limit);out.write(buffer,0,n) } }
        return out.toByteArray()
    }
    fun boundedBytes(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        input.use { while (true) {
            val n = it.read(buf); if (n < 0) break
            require(out.size() + n <= LexiconPackCodec.MAX_COMPRESSED) { "词典包超过16MB" }
            out.write(buf, 0, n)
        } }
        return out.toByteArray()
    }
    private fun save(context:Context,all:List<ByteArray>,publish:(List<LexiconPack>)->(()->Unit)) {
        require(all.sumOf { it.size.toLong() }<=MAX_TOTAL_BYTES)
        val packs=all.map { LexiconPackCodec.read(it.inputStream()) };validate(packs)
        val commitSnapshot=publish(packs)
        val hashes=all.map(LexiconPackCodec::sha256)
        for((i,hash) in hashes.withIndex()) {
            val file=blob(context,hash);check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            if(file.isFile && file.inputStream().use { LexiconPackCodec.sha256(boundedBytes(it)) }==hash)continue
            val atomic=AtomicFile(file);val stream=atomic.startWrite()
            try { stream.write(all[i]);atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream);throw e }
        }
        val index=catalog(context);check(index.baseFile.parentFile!!.isDirectory || index.baseFile.parentFile!!.mkdirs())
        val stream=index.startWrite()
        try { stream.write(JSONObject().put("version",1).put("files",JSONArray(hashes)).toString().toByteArray());index.finishWrite(stream) }
        catch(e:Exception) { index.failWrite(stream);throw e }
        commitSnapshot()
        // Only unreferenced, bounded-name blobs in this private directory are eligible.
        runCatching {
            AtomicFile(target(context)).delete()
            File(context.filesDir,"dictionary/packs").listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.vimelex")) && it.name.removeSuffix(".vimelex") !in hashes }?.forEach { AtomicFile(it).delete() }
        }
    }
    /** Prepare the complete next snapshot before atomically changing the active catalog. */
    @Synchronized fun install(context:Context,data:ByteArray,publish:(List<LexiconPack>)->(()->Unit)) {
        require(data.size<=LexiconPackCodec.MAX_COMPRESSED)
        val pack=LexiconPackCodec.read(data.inputStream())
        val previous=bytes(context).filter { val old=LexiconPackCodec.read(it.inputStream());old.id!=pack.id && old.hash!=pack.hash }
        save(context,previous+data,publish)
    }
    @Synchronized fun remove(context:Context,id:String?=null,publish:(List<LexiconPack>)->(()->Unit)) =
        save(context,if(id==null) emptyList() else bytes(context).filter { LexiconPackCodec.read(it.inputStream()).id!=id },publish)
}
