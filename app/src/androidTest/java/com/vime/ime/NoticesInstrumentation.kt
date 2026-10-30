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
                check(catalog.components.size==13 && catalog.files.size==21)
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
                val started=SystemClock.elapsedRealtime();DictRepository.load(targetContext)
                observations.put("dictionary_load_ms",SystemClock.elapsedRealtime()-started)
                check(DictRepository.ready && DictRepository.state==DictRepository.State.READY)
                check(DictRepository.pinyinCount>=30758 && DictRepository.enCount>=14041)
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
        } catch(error:Throwable) {
            cases.put(JSONObject().put("name","runner-error").put("pass",false).put("error",error.toString()))
        } finally {
            val passed=(0 until cases.length()).count { cases.getJSONObject(it).getBoolean("pass") }
            val report=JSONObject().put("expected",5).put("passed",passed).put("failed",cases.length()-passed)
                .put("not_run",(5-cases.length()).coerceAtLeast(0)).put("cases",cases).put("observations",observations)
            finish(if(passed==5 && cases.length()==5) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("summary",report.toString()) })
        }
    }
}
