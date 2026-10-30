package com.vime.ime.data

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import com.vime.ime.engine.LearningStats
import com.vime.ime.engine.ReviewSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Uses real on-device SQLite files. Test stores are isolated from the user's learning database. */
class LearningStorageInstrumentation:Instrumentation() {
    private val cases=JSONArray()
    private val helpers=ArrayList<LearningDatabase>()
    private val names=ArrayList<Pair<String,String>>()
    private var seed=false
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); seed=arguments?.getString("seedUi")=="true"; start() }
    private fun helper(tag:String):LearningDatabase {
        val name="test_learning_${tag}_${System.nanoTime()}"; val prefs="$name-legacy"
        names.add("$name.db" to prefs)
        return LearningDatabase(targetContext,"$name.db",prefs).also(helpers::add)
    }
    private fun test(name:String,work:()->Unit) {
        val result=runCatching(work)
        cases.put(JSONObject().put("name",name).put("pass",result.isSuccess).put("error",result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0,Bundle().apply { putString("stream","${if(result.isSuccess) "PASS" else "FAIL"} $name\n") })
    }
    override fun onStart() {
        val old="""[{"word":"学习","en":"learn","level":"CET4","saved":1000,"streak":2,"due":300000,"attempts":3,"correct":2,"lastReviewed":2000}]"""
        val oldHistory="""[{"word":"学习","at":2000,"remembered":true}]"""
        val original=LearningArchive.legacy(old,oldHistory)
        val db=helper("migration"); val originalName=names.last()
        val prefs=targetContext.getSharedPreferences(originalName.second,0)
        prefs.edit().putString("entries",old).putString("review_history",oldHistory).commit()
        try {
            test("legacy-migration-keeps-full-progress-and-history") {
                check(db.snapshot()==original)
                check(!prefs.contains("entries") && !prefs.contains("review_history"))
            }
            test("migrated-database-survives-reopen-without-reimport") {
                db.close(); check(db.snapshot()==original)
            }
            test("invalid-import-keeps-current-data") {
                val before=db.snapshot()
                check(runCatching { db.restore(before.copy(entries=listOf(before.entries.first().copy(english=""))),LearningArchive.Restore.REPLACE) }.isFailure)
                check(db.snapshot()==before)
            }
            test("sqlite-import-failure-rolls-back-deletion-and-inserts") {
                val before=db.snapshot()
                db.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON words WHEN NEW.word='回滚' BEGIN SELECT RAISE(ABORT,'fixture'); END")
                try {
                    check(runCatching { db.restore(LearningArchive.Snapshot(listOf(WordBook.Entry("回滚","rollback","",1000)),emptyList()),LearningArchive.Restore.REPLACE) }.isFailure)
                    check(db.snapshot()==before)
                } finally { db.writableDatabase.execSQL("DROP TRIGGER fail_import") }
            }
            val extra=WordBook.Entry("你好","hello","CET4",3000)
            val imported=LearningArchive.Snapshot(listOf(original.entries.first().copy(english="do not overwrite"),extra),listOf(LearningStats.Event("你好",4000,true)))
            test("merge-adds-missing-words-and-keeps-local-progress") {
                val result=db.restore(imported,LearningArchive.Restore.MERGE)
                check(result.added==1 && result.kept==1 && result.total==2)
                check(db.snapshot().entries.first { it.word=="学习" }==original.entries.first())
            }
            test("repeated-merge-is-idempotent-including-history") {
                val before=db.snapshot(); check(db.restore(imported,LearningArchive.Restore.MERGE).added==0); check(db.snapshot()==before)
            }
            test("duplicate-answer-commits-exactly-one-progress-and-event") {
                val expected=db.snapshot().entries.first { it.word=="你好" }; val n=db.snapshot().history.size
                check(db.answer(expected,true,5000)); check(!db.answer(expected,true,5001))
                val after=db.snapshot(); check(after.entries.first { it.word=="你好" }.progress.attempts==1); check(after.history.size==n+1)
            }
            test("removing-word-cascades-only-its-review-history") {
                check(db.remove("你好")); check(db.snapshot()==original)
            }
            test("clear-removes-all-words-events-and-obsolete-legacy-copy") {
                db.clear(); check(db.snapshot()==LearningArchive.Snapshot(emptyList(),emptyList()))
                check(!prefs.contains("entries") && !prefs.contains("review_history"))
            }
            test("replace-restores-exact-validated-snapshot") {
                db.restore(original,LearningArchive.Restore.REPLACE); check(db.snapshot()==original)
            }
            test("malformed-legacy-data-is-preserved-and-not-shown-as-empty") {
                val broken=helper("broken"); val brokenPrefs=targetContext.getSharedPreferences(names.last().second,0)
                brokenPrefs.edit().putString("entries","[{").commit()
                check(runCatching { broken.snapshot() }.isFailure); check(brokenPrefs.getString("entries",null)=="[{")
            }
            test("database-access-on-main-thread-is-rejected") {
                var rejected=false
                runOnMainSync { rejected=runCatching { db.snapshot() }.exceptionOrNull() is IllegalStateException }
                check(rejected)
            }
            test("corrupt-database-file-is-never-silently-deleted") {
                val broken=helper("corruption"); broken.snapshot(); broken.close()
                val file=targetContext.getDatabasePath(names.last().first)
                val damaged="controlled invalid SQLite file".toByteArray()
                file.writeBytes(damaged)
                check(runCatching { broken.snapshot() }.isFailure)
                check(file.exists() && file.readBytes().contentEquals(damaged))
            }
            test("concurrent-clients-do-not-overwrite-collections") {
                val concurrent=helper("concurrent"); concurrent.snapshot()
                val clientName=names.last()
                val pool=Executors.newFixedThreadPool(4)
                try {
                    val work=(0..3).map { n -> pool.submit {
                        LearningDatabase(targetContext,clientName.first,clientName.second).use { client ->
                            repeat(25) { i -> check(client.save(WordBook.Entry("词$n-$i","word","",1000))) }
                        }
                    } }
                    work.forEach { it.get(30,TimeUnit.SECONDS) }
                    check(concurrent.snapshot().entries.size==100)
                } finally { pool.shutdownNow() }
            }
            test("history-cap-keeps-latest-actual-events") {
                val entry=WordBook.Entry("学习","learn","CET4",0,ReviewSchedule.Progress(5,0,10000,10000),9999)
                db.restore(LearningArchive.Snapshot(listOf(entry),(0 until LearningArchive.MAX_EVENTS).map { LearningStats.Event("学习",it.toLong(),true) }),LearningArchive.Restore.REPLACE)
                check(db.answer(entry,false,20000)); val after=db.snapshot()
                check(after.history.size==10000 && after.history.last().at==20000L && after.history.none { it.at==0L })
            }
            if(seed) test("seed-ui-only-on-dedicated-emulator") {
                check(Build.PRODUCT.contains("sdk")) { "UI seed is emulator-only" }
                val now=System.currentTimeMillis()
                val data=LearningArchive.Snapshot(listOf(WordBook.Entry("学习","learn","CET4",now),WordBook.Entry("你好","hello","CET4",now-1000)),emptyList())
                LearningDatabase(targetContext).use { it.restore(data,LearningArchive.Restore.REPLACE) }
                File(targetContext.getExternalFilesDir(null),"learning-storage-fixture.json").writeBytes(LearningArchive.encode(original))
            }
        } finally {
            helpers.forEach { runCatching { it.close() } }
            names.forEach { targetContext.deleteDatabase(it.first); targetContext.deleteSharedPreferences(it.second) }
        }
        val failed=(0 until cases.length()).count { !cases.getJSONObject(it).getBoolean("pass") }
        val report=JSONObject().put("cases",cases).put("passed",cases.length()-failed).put("failed",failed)
        val dir=File(targetContext.getExternalFilesDir(null),"learning-storage").apply { mkdirs() }
        File(dir,"results.json").writeText(report.toString(2))
        finish(if(failed==0)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("summary",report.toString()) })
    }
}
