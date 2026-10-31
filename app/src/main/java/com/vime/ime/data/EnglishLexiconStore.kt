package com.vime.ime.data

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Authenticated, generated public definitions; never stores user text or credentials. */
object EnglishLexiconStore {
    data class BuiltIn(val index:MappedEnglishIndex,val levels:Map<String,String>,val datasetId:String)
    @Volatile private var loaded:BuiltIn?=null
    private fun sha(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(16384)
            while(true) { val count=input.read(bytes);if(count<0) break;digest.update(bytes,0,count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun child(parent:File,name:String)=File(parent,name).also { check(it.canonicalPath==it.absolutePath) }
    @Synchronized fun load(context:Context):BuiltIn {
        loaded?.let { return it };check(Looper.myLooper()!=Looper.getMainLooper())
        val manifest=context.assets.open("english/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getString("format")=="vime-english-lexicon-1" && manifest.getInt("version")==2)
        require(!manifest.getBoolean("legacy_english_read") && !manifest.getBoolean("hand_overrides_read"))
        val files=manifest.getJSONObject("files");val names=files.keys().asSequence().toList().sorted()
        require(names==listOf("english.lex","levels.json"))
        names.forEach { require(files.getString(it).matches(Regex("[a-f0-9]{64}"))) }
        val canonical=names.joinToString(",","{","}") { "\"$it\":\"${files.getString(it)}\"" }
        val id=MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        require(id==manifest.getString("dataset_id"))
        val root=child(context.filesDir.canonicalFile,"lexicon-english").apply { check(isDirectory || mkdirs()) }
        val directory=child(root,id).apply { check(isDirectory || mkdirs()) }
        for(name in names) {
            val file=child(directory,name)
            if(!file.isFile || sha(file)!=files.getString(name)) {
                val temp=child(directory,"$name.tmp")
                FileOutputStream(temp).use { stream -> context.assets.open("english/$name").use { it.copyTo(stream) };stream.fd.sync() }
                check(sha(temp)==files.getString(name));check(temp.renameTo(file))
            }
        }
        val index=MappedEnglishIndex.open(child(directory,"english.lex"))
        val counts=manifest.getJSONObject("counts")
        require(index.headwords==counts.getInt("headwords") && index.labelledHeadwords==counts.getInt("labelled_headwords"))
        val source=child(directory,"levels.json").bufferedReader().use { JSONObject(it.readText()) }
        require(source.getString("format")=="vime-levels-1")
        val values=source.getJSONObject("entries");require(values.length()==counts.getInt("level_words"))
        val levels=values.keys().asSequence().associateWith { key ->
            require(key.matches(Regex("[a-z][a-z'-]*")))
            values.getString(key).also { require(it in listOf("CET4","CET6","TOEFL")) }
        }
        val result=BuiltIn(index,levels,id);loaded=result
        runCatching {
            fun remove(file:File) { check(file.canonicalPath==file.absolutePath)
                if(file.isDirectory) checkNotNull(file.listFiles()).forEach(::remove)
                check(!file.exists() || file.delete()) }
            root.listFiles()?.filter { it.name!=id && it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach(::remove)
        }
        return result
    }
}
