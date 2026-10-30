package com.vime.ime

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import com.vime.ime.data.*
import com.vime.ime.engine.*
import com.vime.ime.voice.VoiceDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Destructive personalization fixtures, dedicated emulator only. Restores the real learning snapshot. */
class InputDataInstrumentation : Instrumentation() {
    private val cases=JSONArray()
    private fun <T> main(action:()->T):T {
        val task=FutureTask(Callable(action)); Handler(Looper.getMainLooper()).post(task)
        return task.get(8,TimeUnit.SECONDS)
    }
    private fun test(name:String,action:()->Unit):Boolean {
        val result=runCatching(action)
        cases.put(JSONObject().put("name",name).put("pass",result.isSuccess)
            .put("error",result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0,Bundle().apply { putString("stream","${if(result.isSuccess) "PASS" else "FAIL"} $name\n") })
        return result.isSuccess
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var database:LearningDatabase?=null
        var original:LearningArchive.Snapshot?=null
        var phrases:List<PhraseRule>?=null
        var fixture:LearningArchive.Snapshot?=null
        var restored=false
        val expected=9
        try {
            val ready=test("dedicated-emulator-and-real-engine-ready") {
                check(Build.PRODUCT.contains("sdk")) { "Dedicated emulator required" }
                RimeEngine.initialize(targetContext); check(RimeEngine.state==RimeEngine.State.READY)
                PersonalLexicon.init(targetContext); main { MemoryEngine.init(targetContext) }
                phrases=PersonalLexicon.list()
                database=LearningDatabase(targetContext)
                original=database!!.snapshot()
                database!!.save(WordBook.Entry("隐私验收词","privacy fixture","CET4",1000))
                fixture=database!!.snapshot()
            }
            if(ready) {
                val candidate=RimeEngine.query("nihao").first { it.word=="你好" }
                test("reset-erases-native-and-manual-habits-but-preserves-book-and-phrases") {
                    check(InputEngine.resetPersonalization(targetContext))
                    fun nativeHasLearnedWord():Boolean = File(targetContext.filesDir,"rime/user").walkTopDown()
                        .filter { it.isFile && (it.extension=="log" || it.extension=="ldb") }
                        .any { String(it.readBytes(),Charsets.UTF_8).contains("你好") }
                    check(!nativeHasLearnedWord()) { "Fresh native user database unexpectedly contains the test word" }
                    PersonalLexicon.save(listOf(PhraseRule("fixturephrase","fixture phrase")))
                    InputEngine.selected("nihao",candidate,true)
                    // Switching schemas closes/reopens the shared native user dictionary,
                    // making the real learned record visible in its LevelDB files.
                    RimeEngine.query("64426")
                    check(nativeHasLearnedWord()) { "Native selection did not create the expected learned record" }
                    check(PersonalLexicon.count("nihao","你好")>0)
                    val marker=File(targetContext.filesDir,"rime/user/privacy-reset-sentinel.txt").apply { writeText("delete me") }
                    val table=File(targetContext.filesDir,"rime/shared/build/vime_pinyin.table.bin")
                    val before=hash(table)
                    check(InputEngine.resetPersonalization(targetContext))
                    check(!marker.exists() && !nativeHasLearnedWord() && PersonalLexicon.count("nihao","你好")==0)
                    check(PersonalLexicon.list()==listOf(PhraseRule("fixturephrase","fixture phrase")))
                    check(database!!.snapshot()==fixture && hash(table)==before)
                }
                test("reset-after-nine-key-can-reinitialize-twice") {
                    repeat(2) {
                        check(RimeEngine.query("64426",limit=256).any { it.word=="你好" })
                        check(InputEngine.resetPersonalization(targetContext))
                        check(RimeEngine.query("nihao").any { it.word=="你好" && it.consumed==5 })
                    }
                }
                test("concurrent-native-queries-and-reset-do-not-use-closed-database") {
                    val pool=Executors.newFixedThreadPool(2)
                    try {
                        val work=(0..1).map { pool.submit { repeat(40) { RimeEngine.query(if(it%2==0)"nihao" else "64426") } } }
                        check(InputEngine.resetPersonalization(targetContext))
                        work.forEach { it.get(15,TimeUnit.SECONDS) }
                        check(RimeEngine.query("nihao").any { it.word=="你好" })
                    } finally { pool.shutdownNow() }
                }
                test("annotation-reset-cancels-pending-and-queued-old-snapshots") {
                    main { MemoryEngine.master("privacy-sentinel","CET4"); MemoryEngine.flush(); MemoryEngine.onCommitted("late-sentinel","CET4") }
                    val done=CountDownLatch(1); var failure:Throwable?=null
                    MemoryEngine.resetAnnotations(targetContext) { failure=it.exceptionOrNull(); done.countDown() }
                    check(done.await(8,TimeUnit.SECONDS)); failure?.let { throw it }
                    SystemClock.sleep(600)
                    check(main { MemoryEngine.shouldAnnotate("privacy-sentinel","CET4") })
                    check(!targetContext.getSharedPreferences("vime_memory",0).contains("states"))
                    val disk=File(targetContext.applicationInfo.dataDir,"shared_prefs/vime_memory.xml").readText()
                    check("privacy-sentinel" !in disk && "late-sentinel" !in disk && "name=\"states\"" !in disk)
                    check(database!!.snapshot()==fixture)
                }
                test("phrase-deletion-is-durable-and-preserves-other-input-data") {
                    PersonalLexicon.record("nihao",candidate,true)
                    PersonalLexicon.clearPhrases()
                    check(PersonalLexicon.list().isEmpty() && !targetContext.getSharedPreferences("personal_lexicon",0).contains("phrases"))
                    check(PersonalLexicon.count("nihao","你好")>0 && database!!.snapshot()==fixture)
                }
                test("voice-status-deletion-preserves-service-settings") {
                    val config=targetContext.getSharedPreferences("voice_settings",0).all.toMap()
                    VoiceDiagnostics.record(targetContext,"test.component","Test service","zh-CN","fixture",5)
                    VoiceDiagnostics.clear(targetContext)
                    check(VoiceDiagnostics.summary(targetContext)==null)
                    check(targetContext.getSharedPreferences("voice_settings",0).all==config)
                }
                test("shared-clipboard-history-clears-without-a-disk-copy") {
                    check(ClipboardSession.shared.save("explicit test snippet",false,System.currentTimeMillis()))
                    ClipboardSession.shared.clear()
                    check(ClipboardSession.shared.list(System.currentTimeMillis()).isEmpty())
                }
                test("model-clears-symbol-recents-without-clearing-keyboard-settings") {
                    val settings=targetContext.getSharedPreferences("vime_ime",0).all.toMap()
                    targetContext.getSharedPreferences("vime_symbol_history",0).edit().putString("symbols","[\"。\"]").putString("emoji","[\"😀\"]").commit()
                    val store=ViewModelStore()
                    val model=main { InputDataModel(targetContext.applicationContext as Application).also { store.put("fixture",it); it.clearRecents() } }
                    val deadline=SystemClock.uptimeMillis()+8000
                    while(main { model.state.value!!.busy } && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(50)
                    check(main { model.state.value?.message=="最近使用的符号与表情已清除" })
                    check(targetContext.getSharedPreferences("vime_symbol_history",0).all.isEmpty())
                    check(targetContext.getSharedPreferences("vime_ime",0).all==settings && database!!.snapshot()==fixture)
                    main { store.clear() }
                }
            }
        } finally {
            runCatching {
                if(original!=null) { database!!.restore(original!!,LearningArchive.Restore.REPLACE); check(database!!.snapshot()==original) }
                phrases?.let(PersonalLexicon::save)
                restored=original!=null
            }.onFailure { error -> cases.put(JSONObject().put("name","fixture-restore").put("pass",false).put("error",error.toString())) }
            database?.close()
            val passed=(0 until cases.length()).count { cases.getJSONObject(it).getBoolean("pass") }
            val report=JSONObject().put("expected",expected).put("passed",passed).put("failed",cases.length()-passed)
                .put("not_run",(expected-cases.length()).coerceAtLeast(0)).put("learning_snapshot_restored",restored).put("cases",cases)
            finish(if(passed==expected && cases.length()==expected && restored)Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("summary",report.toString()) })
        }
    }
}
