package com.vime.ime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.vime.ime.data.DictRepository
import com.vime.ime.data.NoticeCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Read-only checks of packaged documents and the real on-device dictionary loader. */
class NoticesInstrumentation:Instrumentation() {
    private val cases=JSONArray()
    private val observations=JSONObject()
    private lateinit var catalog:NoticeCatalog
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    private fun test(name:String,action:()->Unit) {
        val result=runCatching(action)
        cases.put(JSONObject().put("name",name).put("pass",result.isSuccess)
            .put("error",result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0,Bundle().apply { putString("stream","${if(result.isSuccess) "PASS" else "FAIL"} $name\n") })
    }
    override fun onStart() {
        try {
            check(Build.PRODUCT.contains("sdk")) { "Dedicated emulator required" }
            test("packaged-catalog-and-all-document-references-parse") {
                catalog=NoticeCatalog.parse(targetContext.assets.open("notices.json").bufferedReader().use { it.readText() })
                check(catalog.components.size==13 && catalog.files.size==22)
                observations.put("components",catalog.components.size).put("documents",catalog.files.size)
            }
            test("all-packaged-license-bytes-match-including-complete-gpl") {
                for((file,expected) in catalog.files) {
                    val bytes=targetContext.assets.open(file).use { it.readBytes() }
                    check(bytes.size in 100..256*1024)
                    check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }==expected)
                    if(file=="licenses/GNU-GPL-3.0.txt") check(bytes.size>30000 && bytes.toString(Charsets.UTF_8).contains("END OF TERMS AND CONDITIONS"))
                }
            }
            test("real-dictionary-loader-retains-chinese-and-english-data") {
                Runtime.getRuntime().gc();SystemClock.sleep(100)
                val before=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
                val started=SystemClock.elapsedRealtime();DictRepository.load(targetContext)
                observations.put("dictionary_load_ms",SystemClock.elapsedRealtime()-started)
                Runtime.getRuntime().gc();SystemClock.sleep(100)
                val heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()-before
                observations.put("dictionary_retained_java_heap_delta_bytes",heap)
                check(DictRepository.ready && DictRepository.state==DictRepository.State.READY)
                check(DictRepository.pinyinCount>=393950 && DictRepository.wordCount>=569466 && DictRepository.enCount>=14041)
                check(heap<32L*1024*1024) { "Public dictionary retained Java heap exceeds32MiB" }
                check(DictRepository.candidates("zhendejiade",100).any { it.word=="真的假的" })
                check(DictRepository.getCard("你好")!=null && DictRepository.getEn("学习").isNotBlank())
            }
            test("real-dictionary-applies-basics-cet6-and-toefl-tags") {
                for(word in listOf("hello","study","love")) check(DictRepository.getEnLevel(word)=="CET4")
                check(DictRepository.getEnLevel("contemplate")=="CET6")
                check(DictRepository.getEnLevel("abdicate")=="TOEFL")
                check(DictRepository.enCandidates("stud",100).any { it.word=="study" && it.lvl=="CET4" })
                check(DictRepository.getEnLevel("vime-unclassified-fixture").isEmpty())
            }
            test("built-in-level-metadata-has-explicit-policy-and-declared-counts") {
                val source=JSONObject(targetContext.assets.open("levels-source.json").bufferedReader().use { it.readText() })
                val counts=source.getJSONObject("counts")
                check(counts.getInt("CET4")==5351 && counts.getInt("CET6")==1755 && counts.getInt("TOEFL")==3408)
                check(source.getJSONObject("tag_policy").getJSONArray("CET4").toString()=="[\"zk\",\"gk\",\"cet4\"]")
                observations.put("level_counts",counts)
            }
            test("complete-mapped-fallback-full-nine-initials-and-reading-priority") {
                for((code,word) in mapOf("zhendejiade" to "真的假的","nihao" to "你好","jinbi" to "金币","mushu" to "木薯","jiantieban" to "剪贴板","shurufa" to "输入法")) {
                    check(DictRepository.candidates(code,100).any { it.word==word && it.consumed==code.length })
                    val nine=code.map(com.vime.ime.data.CandidateIndex::digit).joinToString("")
                    check(DictRepository.t9Candidates(nine,100).any { it.word==word && it.consumed==nine.length })
                }
                check(DictRepository.candidates("e").first().word=="额")
                check(DictRepository.candidates("nh",100).any { it.word=="你好" && it.consumed==2 })
                check(DictRepository.candidates("zhen'de'jia'de",100).any { it.word=="真的假的" && it.consumed==14 })
                check(DictRepository.candidates("zhendejiadex",100).any { it.word=="真的假的" && it.consumed==11 })
                // Exercise the actual fallback bridge while simulating an
                // unavailable native engine; restore its state before leaving.
                synchronized(com.vime.ime.engine.RimeEngine) {
                    val field=com.vime.ime.engine.RimeEngine.javaClass.getDeclaredField("state").apply { isAccessible=true }
                    val original=field.get(null)
                    try {
                        field.set(null,com.vime.ime.engine.RimeEngine.State.FAILED)
                        check(com.vime.ime.engine.InputEngine.query("zhendejiade",false,limit=100,personalized=false).any { it.word=="真的假的" })
                        check(com.vime.ime.engine.InputEngine.query("64426",true,limit=100,personalized=false).any { it.word=="你好" })
                    } finally { field.set(null,original) }
                }
                observations.put("native_unavailable_fallback_bridge","simulated state; restored after lookup")
            }
            test("mapped-vocabulary-query-latency-on-real-android-runtime") {
                val file=java.io.File(targetContext.filesDir,"lexicon-input/"+JSONObject(targetContext.assets.open("input/manifest.json").bufferedReader().use { it.readText() }).getString("dataset_id")+"/input.lex")
                val index=com.vime.ime.data.MappedInputIndex.open(file)
                val sample=java.io.RandomAccessFile(file,"r").use { source ->
                    val mapped=source.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,source.length()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    (0 until 400).map { item ->
                        val at=mapped.getInt(64+(item*1423%index.count)*4)
                        val length=mapped.get(at).toInt() and 255
                        String(ByteArray(length) { mapped.get(at+8+it) },Charsets.US_ASCII).replace(" ","")
                    }
                }
                val requests=sample.flatMap { code -> listOf(code to false,code.take(3) to false,
                    code.map(com.vime.ime.data.CandidateIndex::digit).joinToString("") to true) }
                val timings=requests.map { (code,nine) ->
                    val started=SystemClock.elapsedRealtimeNanos();check(index.query(code,nine,100).isNotEmpty());
                    SystemClock.elapsedRealtimeNanos()-started
                }.sorted()
                fun percentile(p:Double)=timings[((timings.size-1)*p).toInt()]/1e6
                val benchmark=JSONObject().put("requests",requests.size).put("limit",100).put("p50_ms",percentile(.5))
                    .put("p95_ms",percentile(.95)).put("p99_ms",percentile(.99)).put("max_ms",timings.last()/1e6)
                    .put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS.first()).put("physical_phone",false)
                observations.put("mapped_lookup",benchmark)
                check(percentile(.99)<50) { "Fallback query p99 exceeds50ms" }
                val edge=JSONArray()
                for(code in listOf("zhendejiadex","shishishishishishishishishi","z".repeat(48),"abcdefghijklmnopqrstuvwxyz","z".repeat(128))) {
                    val started=SystemClock.elapsedRealtimeNanos();index.query(code,false,100)
                    val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1e6
                    edge.put(JSONObject().put("characters",code.length).put("elapsed_ms",elapsed))
                    check(elapsed<100) { "Fallback incomplete/invalid query exceeds100ms" }
                }
                observations.put("incomplete_query_bounds",edge)
            }
        } catch(error:Throwable) {
            cases.put(JSONObject().put("name","runner-error").put("pass",false).put("error",error.toString()))
        } finally {
            val passed=(0 until cases.length()).count { cases.getJSONObject(it).getBoolean("pass") }
            val report=JSONObject().put("expected",7).put("passed",passed).put("failed",cases.length()-passed)
                .put("not_run",(7-cases.length()).coerceAtLeast(0)).put("cases",cases).put("observations",observations)
            finish(if(passed==7 && cases.length()==7) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("summary",report.toString()) })
        }
    }
}
