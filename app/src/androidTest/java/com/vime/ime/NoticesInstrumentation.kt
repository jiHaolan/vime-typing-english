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
        android.os.Trace.beginSection(name)
        val result=try { runCatching(action) } finally { android.os.Trace.endSection() }
        cases.put(JSONObject().put("name",name).put("pass",result.isSuccess)
            .put("error",result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0,Bundle().apply { putString("stream","${if(result.isSuccess) "PASS" else "FAIL"} $name\n") })
    }
    override fun onStart() {
        try {
            check(Build.PRODUCT.contains("sdk")) { "Dedicated emulator required" }
            test("packaged-catalog-and-all-document-references-parse") {
                catalog=NoticeCatalog.parse(targetContext.assets.open("notices.json").bufferedReader().use { it.readText() })
                check(catalog.components.size==21 && catalog.files.size==37)
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
                check(DictRepository.pinyinCount>=393950 && DictRepository.wordCount>=569466 && DictRepository.enCount==120194)
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
                    // Records are contiguous in both V1 and V2. Sampling the
                    // body avoids interpreting V2 delta blocks as V1 offsets.
                    val codes=ArrayList<String>(400)
                    var at=mapped.getInt(32)
                    repeat(index.count) { item ->
                        val length=mapped.get(at).toInt() and 255
                        val wordBytes=mapped.getShort(at+2).toInt() and 65535
                        if(item%1423==0 && codes.size<400) codes.add(String(ByteArray(length) { mapped.get(at+8+it) },Charsets.US_ASCII).replace(" ",""))
                        at+=8+length+wordBytes
                    }
                    check(at==mapped.limit() && codes.size==400)
                    codes
                }
                val requests=sample.flatMap { code -> listOf(code to false,code.take(3) to false,
                    code.map(com.vime.ime.data.CandidateIndex::digit).joinToString("") to true) }
                val cpuTimes=ArrayList<Long>(requests.size)
                val slow=JSONArray()
                val timings=requests.map { (code,nine) ->
                    val started=SystemClock.elapsedRealtimeNanos();val cpu=android.os.Debug.threadCpuTimeNanos()
                    android.os.Trace.beginSection("vime.mapped.query")
                    try { check(index.query(code,nine,100).isNotEmpty()) } finally { android.os.Trace.endSection() }
                    val elapsed=SystemClock.elapsedRealtimeNanos()-started
                    val usedCpu=android.os.Debug.threadCpuTimeNanos()-cpu;cpuTimes.add(usedCpu)
                    if(elapsed>20_000_000 && slow.length()<20) slow.put(JSONObject().put("public_code",code).put("nine",nine)
                        .put("wall_ms",elapsed/1e6).put("thread_cpu_ms",usedCpu/1e6))
                    elapsed
                }.sorted()
                fun percentile(p:Double)=timings[((timings.size-1)*p).toInt()]/1e6
                val benchmark=JSONObject().put("requests",requests.size).put("limit",100).put("p50_ms",percentile(.5))
                    .put("p95_ms",percentile(.95)).put("p99_ms",percentile(.99)).put("max_ms",timings.last()/1e6)
                    .put("sdk",Build.VERSION.SDK_INT).put("abi",Build.SUPPORTED_ABIS.first()).put("physical_phone",false)
                    .put("thread_cpu_p99_ms",cpuTimes.sorted()[((cpuTimes.size-1)*.99).toInt()]/1e6)
                    .put("thread_cpu_total_ms",cpuTimes.sum()/1e6).put("wall_total_ms",timings.sum()/1e6)
                    .put("slow_public_requests",slow)
                observations.put("mapped_lookup",benchmark)
                check(percentile(.99)<50) { "Fallback query p99 exceeds50ms" }
                val enriched=requests.map { (code,nine) ->
                    val started=SystemClock.elapsedRealtimeNanos()
                    val candidates=if(nine) DictRepository.t9Candidates(code,12) else DictRepository.candidates(code,12)
                    check(candidates.isNotEmpty())
                    SystemClock.elapsedRealtimeNanos()-started
                }.sorted()
                fun enrichedPercentile(p:Double)=enriched[((enriched.size-1)*p).toInt()]/1e6
                observations.put("repository_lookup_with_english",JSONObject().put("requests",requests.size).put("limit",12)
                    .put("p50_ms",enrichedPercentile(.5)).put("p95_ms",enrichedPercentile(.95))
                    .put("p99_ms",enrichedPercentile(.99)).put("max_ms",enriched.last()/1e6)
                    .put("physical_phone",false))
                check(enrichedPercentile(.99)<100) { "Enriched fallback query p99 exceeds100ms" }
                val edge=JSONArray()
                for(code in listOf("zhendejiadex","shishishishishishishishishi","z".repeat(48),"abcdefghijklmnopqrstuvwxyz","z".repeat(128))) {
                    val started=SystemClock.elapsedRealtimeNanos();index.query(code,false,100)
                    val elapsed=(SystemClock.elapsedRealtimeNanos()-started)/1e6
                    edge.put(JSONObject().put("characters",code.length).put("elapsed_ms",elapsed))
                    check(elapsed<100) { "Fallback incomplete/invalid query exceeds100ms" }
                }
                observations.put("incomplete_query_bounds",edge)
            }
            test("complete-english-reading-aware-tonal-cards-and-native-candidates") {
                check(DictRepository.getEn("重","zhong")=="heavy" && DictRepository.getEn("重","chong")=="to repeat")
                check(DictRepository.getEn("长","chang")=="long" && DictRepository.getEn("长","zhang")=="chief")
                check(DictRepository.getEn("重").isEmpty())
                val card=DictRepository.getCard("好","hao")?:error("Missing full tonal card")
                check(card.py.contains("hao3") && card.py.contains("hao4") && (card.d1+card.d2).contains("to be prone to"))
                val definition=DictRepository.definition("重")?:error("Missing definition")
                check(definition.meaning.contains("heavy") && definition.meaning.contains("to repeat") && definition.source.contains("CC BY-SA 4.0"))
                check(DictRepository.candidate("你好","nh",2).readingCode=="nihao")
                for((raw,meaning) in mapOf("zhong" to "heavy","chong" to "to repeat")) {
                    val native=com.vime.ime.engine.RimeEngine.query(raw,limit=100).first { it.word=="重" }
                    check(native.en==meaning && native.readingCode==raw)
                }
                observations.put("english_labelled_headwords",DictRepository.enCount)
            }
            test("optional-pack-import-native-front-rollback-and-removal") {
                val directory=java.io.File(targetContext.cacheDir,"notices-pack-isolated").apply { check(isDirectory || mkdirs()) }
                val sandbox=object:android.content.ContextWrapper(targetContext) { override fun getFilesDir()=directory }
                val row="ceshixingyun\t测试星云\tfixture nebula\tce4 shi4 xing1 yun2\n".toByteArray()
                fun pack(valid:Boolean):ByteArray {
                    val hash=if(valid) MessageDigest.getInstance("SHA-256").digest(row).joinToString("") { "%02x".format(it) } else "0".repeat(64)
                    val values=mapOf("manifest.properties" to ("format=vimelex-1\nname=Isolated Vime test\nversion=1\nsource=https://example.invalid/test\nlicense=MIT\nentries=1\nsha256=$hash\n").toByteArray(),
                        "entries.tsv" to row,"NOTICE.txt" to "Emulator-only fixture".toByteArray(),
                        "LICENSE.txt" to targetContext.assets.open("licenses/ecdict-MIT.txt").use { it.readBytes() })
                    val out=java.io.ByteArrayOutputStream()
                    java.util.zip.ZipOutputStream(out).use { zip -> values.forEach { (name,data) -> zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(data);zip.closeEntry() } }
                    return out.toByteArray()
                }
                try {
                    DictRepository.uninstall(sandbox)
                    DictRepository.install(sandbox,pack(true));val hash=DictRepository.installedPack!!.hash
                    check(com.vime.ime.engine.InputEngine.query("ceshixingyun",false,personalized=false).first().let { it.word=="测试星云" && it.en=="fixture nebula" })
                    check(DictRepository.definition("测试星云")!!.source.contains("Isolated Vime test"))
                    check(DictRepository.enCandidates("fixt",100).any { it.word=="fixture" })
                    check(runCatching { DictRepository.install(sandbox,pack(false)) }.isFailure)
                    check(DictRepository.installedPack!!.hash==hash)
                } finally { DictRepository.uninstall(sandbox);DictRepository.reload(targetContext) }
                check(DictRepository.getEn("学习").isNotBlank())
            }
        } catch(error:Throwable) {
            cases.put(JSONObject().put("name","runner-error").put("pass",false).put("error",error.toString()))
        } finally {
            val passed=(0 until cases.length()).count { cases.getJSONObject(it).getBoolean("pass") }
            val report=JSONObject().put("expected",9).put("passed",passed).put("failed",cases.length()-passed)
                .put("not_run",(9-cases.length()).coerceAtLeast(0)).put("cases",cases).put("observations",observations)
            finish(if(passed==9 && cases.length()==9) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("summary",report.toString()) })
        }
    }
}
