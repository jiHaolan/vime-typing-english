package com.vime.ime

import android.app.Instrumentation
import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import com.vime.ime.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real packaged data / AtomicFile checks in an isolated emulator-only filesDir. */
class LexiconCoverageInstrumentation:Instrumentation() {
    private val cases=JSONArray();private val observations=JSONObject();private var phase="main"
    override fun onCreate(arguments:Bundle?) { phase=arguments?.getString("phase")?:"main";super.onCreate(arguments);start() }
    private fun test(name:String,action:()->Unit) {
        val result=runCatching(action);cases.put(JSONObject().put("name",name).put("pass",result.isSuccess).put("error",result.exceptionOrNull()?.toString()?:JSONObject.NULL))
        sendStatus(0,Bundle().apply { putString("stream","${if(result.isSuccess) "PASS" else "FAIL"} $name\n") })
    }
    private fun variant(original:LexiconPack,version:String):ByteArray {
        val row="ceshixingyun\t测试星云\tfixture nebula\tce4 shi4 xing1 yun2\n".toByteArray()
        val hash=LexiconPackCodec.sha256(row);val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for((name,data) in mapOf("manifest.properties" to "format=vimelex-1\nid=${original.id}\nname=Updated fixture\nversion=$version\nsource=https://example.invalid/fixture\nlicense=MIT\nentries=1\nsha256=$hash\n".toByteArray(),"entries.tsv" to row,"NOTICE.txt" to "Isolated emulator test fixture only".toByteArray(),"LICENSE.txt" to "MIT fixture, not a shipped dictionary".toByteArray())) {
                z.putNextEntry(ZipEntry(name));z.write(data);z.closeEntry()
            }
        }
        return out.toByteArray()
    }
    override fun onStart() {
        try {
            check(Build.PRODUCT.contains("sdk"));check(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)==16384L)
            val directory=File(targetContext.cacheDir,"lexicon-coverage-isolated").apply { check(isDirectory || mkdirs()) }
            val sandbox=object:ContextWrapper(targetContext) { override fun getFilesDir()=directory }
            val meta=JSONObject(context.assets.open("domain-packs.json").bufferedReader().use { it.readText() }).getJSONObject("domains")
            val packs=meta.keys().asSequence().sorted().map { key -> context.assets.open(meta.getJSONObject(key).getString("file")).use { it.readBytes() } }.toList()
            if(phase=="readback") {
                test("all-eight-packs-reload-after-real-process-restart") {
                    DictRepository.load(sandbox);check(DictRepository.installedPacks.size==8)
                    for(bytes in packs) { val pack=LexiconPackCodec.read(bytes.inputStream());check(DictRepository.installedPacks.any { it.id==pack.id && it.hash==pack.hash }) }
                    check(DictRepository.getEn("心肺复苏","xinfeifusu")=="cardiopulmonary resuscitation")
                }
                test("isolated-packs-can-all-be-removed-with-builtins-retained") {
                    DictRepository.uninstall(sandbox);check(LexiconStore.readAll(sandbox).isEmpty());check(DictRepository.getEn("很多").isNotBlank())
                }
            } else {
                test("packaged-loader-and-10000-common-words-offline") {
                    DictRepository.uninstall(sandbox);DictRepository.load(sandbox);check(DictRepository.ready)
                    val words=context.assets.open("common-baseline.tsv").bufferedReader().use { it.readLines() }.filter { it.isNotEmpty() }.map { it.substringBefore('\t') }
                    check(words.size==10000)
                    val missing=words.filter { DictRepository.getEn(it).none { c -> c in 'a'..'z' || c in 'A'..'Z' } }
                    observations.put("common_checked",words.size).put("missing_common",JSONArray(missing));check(missing.isEmpty())
                    check(DictRepository.getEn("未收录测试词甲乙丙").isEmpty())
                    val characters=context.assets.open("common-characters.tsv").bufferedReader().use { it.readLines() }.filter { it.isNotEmpty() }.map { it.substringBefore('\t') }
                    check(characters.size==500 && characters.all { word -> DictRepository.getEn(word).any { it in 'a'..'z' || it in 'A'..'Z' } })
                    observations.put("common_characters_checked",500)
                    check(DictRepository.getEn("重","chong")=="to repeat")
                    check(DictRepository.getCard("重")!!.d2.contains("to repeat"))
                }
                test("real-jni-and-merged-common-candidates-full-pinyin-and-nine-key") {
                    com.vime.ime.engine.RimeEngine.initialize(targetContext)
                    check(com.vime.ime.engine.RimeEngine.state==com.vime.ime.engine.RimeEngine.State.READY)
                    val user=File(directory,"jni-user").apply { check(isDirectory || mkdirs()) }
                    synchronized(com.vime.ime.engine.RimeEngine) {
                        com.vime.ime.engine.RimeNative.shutdown()
                        check(com.vime.ime.engine.RimeNative.initialize(com.vime.ime.engine.RimeEngine.sharedPath,user.absolutePath).isNotEmpty())
                    }
                    check(com.vime.ime.engine.RimeEngine.query("xuexi",limit=100).any { it.word=="学习" && it.en.isNotBlank() })
                    val examples=mapOf("henduo" to "很多","zheci" to "这次","fangzai" to "放在","zhendejiade" to "真的假的","xuexi" to "学习")
                    for((code,word) in examples)for(raw in listOf(code,code.map(CandidateIndex::digit).joinToString(""))) {
                        val candidates=com.vime.ime.engine.InputEngine.query(raw,raw.all { it in '2'..'9' },limit=100,personalized=false)
                        check(candidates.any { it.word==word && it.en.isNotBlank() }) { "$raw/$word" }
                    }
                    observations.put("native_common_queries",examples.size*2)
                    synchronized(com.vime.ime.engine.RimeEngine) { com.vime.ime.engine.RimeNative.shutdown() }
                }
                test("legacy-active-file-migrates-without-losing-old-pack") {
                    val legacy=File(directory,"dictionary/active.vimelex");val index=File(directory,"dictionary/installed.json")
                    android.util.AtomicFile(index).delete();legacy.parentFile!!.mkdirs();legacy.writeBytes(packs[0])
                    check(LexiconStore.readAll(sandbox).size==1)
                    DictRepository.install(sandbox,packs[1]);check(LexiconStore.readAll(sandbox).size==2);check(!legacy.exists())
                }
                test("eight-real-domain-packs-coexist-and-every-entry-has-english") {
                    for(bytes in packs)DictRepository.install(sandbox,bytes)
                    check(DictRepository.installedPacks.size==8)
                    var count=0
                    for(pack in DictRepository.installedPacks)for(entry in pack.entries) {
                        val label=DictRepository.candidate(entry.word,entry.pinyin,entry.pinyin.length).en
                        check(label.any { it in 'a'..'z' || it in 'A'..'Z' }) { entry.word };count++
                    }
                    observations.put("domain_entries_checked",count)
                    check(DictRepository.optionalCandidates("xinfeifusu",false,100).any { it.word=="心肺复苏" && it.en=="cardiopulmonary resuscitation" })
                    check(DictRepository.getCard("心肺复苏","xinfeifusu")!=null)
                }
                test("bad-checksum-and-rejected-snapshot-preserve-committed-catalog") {
                    val index=File(directory,"dictionary/installed.json");val before=index.readBytes();val previous=DictRepository.installedPacks.map { it.hash }
                    check(runCatching { DictRepository.install(sandbox,byteArrayOf(1,2,3)) }.isFailure)
                    val first=LexiconPackCodec.read(packs[0].inputStream())
                    check(runCatching { LexiconStore.install(sandbox,variant(first,"rollback")) { error("Reject snapshot before commit") } }.isFailure)
                    check(index.readBytes().contentEquals(before));check(DictRepository.installedPacks.map { it.hash }==previous)
                }
                test("same-id-update-and-single-removal-retain-other-domains") {
                    val first=LexiconPackCodec.read(packs[0].inputStream());val others=DictRepository.installedPacks.filter { it.id!=first.id }.map { it.hash }
                    DictRepository.install(sandbox,variant(first,"2"));check(DictRepository.installedPacks.size==8)
                    check(DictRepository.installedPacks.single { it.id==first.id }.version=="2")
                    DictRepository.uninstall(sandbox,first.id);check(DictRepository.installedPacks.map { it.hash }==others)
                    DictRepository.install(sandbox,packs[0]);check(LexiconStore.readAll(sandbox).size==8)
                }
            }
        } catch(error:Throwable) { cases.put(JSONObject().put("name","harness").put("pass",false).put("error",error.toString())) }
        val passed=(0 until cases.length()).all { cases.getJSONObject(it).getBoolean("pass") }
        val report=JSONObject().put("passed",passed).put("phase",phase).put("cases",cases).put("observations",observations)
        finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",report.toString()+"\n") })
    }
}
