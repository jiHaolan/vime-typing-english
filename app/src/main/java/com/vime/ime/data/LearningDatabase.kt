package com.vime.ime.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.Looper
import com.vime.ime.engine.LearningStats
import com.vime.ime.engine.ReviewSchedule

/** Owned by WordBook's worker; words and review history change in the same transaction. */
internal class LearningDatabase(context:Context,name:String="vime_learning.db",private val legacyPrefs:String="vime_wordbook") : SQLiteOpenHelper(context.applicationContext,name,null,1,DatabaseErrorHandler { damaged ->
    runCatching { damaged.close() }
    throw SQLiteDatabaseCorruptException("学习数据损坏，原文件保留")
}) {
    private val app=context.applicationContext
    override fun onConfigure(db:SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE words(word TEXT PRIMARY KEY NOT NULL, english TEXT NOT NULL, level TEXT NOT NULL, saved INTEGER NOT NULL, streak INTEGER NOT NULL CHECK(streak BETWEEN 0 AND 5), due INTEGER NOT NULL, attempts INTEGER NOT NULL CHECK(attempts>=0), correct INTEGER NOT NULL CHECK(correct BETWEEN 0 AND attempts), reviewed INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE review_events(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT NOT NULL REFERENCES words(word) ON DELETE CASCADE, at INTEGER NOT NULL, remembered INTEGER NOT NULL CHECK(remembered IN (0,1)))")
        db.execSQL("CREATE INDEX words_due ON words(due)"); db.execSQL("CREATE INDEX events_at ON review_events(at)")
        val prefs=app.getSharedPreferences(legacyPrefs,Context.MODE_PRIVATE)
        val old=LearningArchive.legacy(prefs.getString("entries","[]").orEmpty(),prefs.getString("review_history","[]").orEmpty())
        // SQLiteOpenHelper wraps schema creation and this migration in one transaction.
        old.entries.forEach { insert(db,it) }; old.history.forEach { event(db,it) }
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) { error("不支持的学习数据库升级") }
    override fun onOpen(db:SQLiteDatabase) {
        super.onOpen(db)
        val old=app.getSharedPreferences(legacyPrefs,Context.MODE_PRIVATE)
        if(old.contains("entries") || old.contains("review_history")) {
            // Remove obsolete private copies only after the migrated database opens and validates.
            readSnapshot(db)
            check(old.edit().remove("entries").remove("review_history").commit()) { "旧版学习数据清理未完成，请重试" }
        }
    }
    fun snapshot():LearningArchive.Snapshot {
        backgroundOnly()
        return readSnapshot(readableDatabase)
    }
    fun contains(word:String):Boolean { backgroundOnly(); return exists(readableDatabase,word) }
    private fun readSnapshot(db:SQLiteDatabase):LearningArchive.Snapshot {
        val entries=ArrayList<WordBook.Entry>(); val history=ArrayList<LearningStats.Event>()
        db.query("words",null,null,null,null,null,"saved DESC, word ASC").use { c -> while(c.moveToNext()) {
            fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
            fun n(k:String)=c.getLong(c.getColumnIndexOrThrow(k))
            entries.add(WordBook.Entry(s("word"),s("english"),s("level"),n("saved"),ReviewSchedule.Progress(n("streak").toInt(),n("due"),n("attempts").toInt(),n("correct").toInt()),n("reviewed")))
        } }
        db.query("review_events",arrayOf("word","at","remembered"),null,null,null,null,"id ASC").use { c -> while(c.moveToNext()) history.add(LearningStats.Event(c.getString(0),c.getLong(1),c.getInt(2)==1)) }
        return LearningArchive.Snapshot(entries,history).also(LearningArchive::validate)
    }
    fun save(entry:WordBook.Entry):Boolean=transaction { db ->
        LearningArchive.validate(LearningArchive.Snapshot(listOf(entry),emptyList()))
        if(exists(db,entry.word)) return@transaction false
        require(count(db)<LearningArchive.MAX_WORDS) { "生词本已满，请先备份或整理收藏" }; insert(db,entry); true
    }
    fun answer(expected:WordBook.Entry,remembered:Boolean,now:Long):Boolean=transaction { db ->
        val next=ReviewSchedule.answer(expected.progress,remembered,now)
        val changed=db.update("words",values(expected.copy(progress=next,lastReviewedAt=now)),"word=? AND attempts=? AND reviewed=? AND due=? AND streak=? AND correct=? AND saved=? AND english=? AND level=?",
            arrayOf(expected.word,expected.progress.attempts.toString(),expected.lastReviewedAt.toString(),expected.progress.dueAt.toString(),expected.progress.streak.toString(),expected.progress.correct.toString(),expected.savedAt.toString(),expected.english,expected.level))
        if(changed==0) return@transaction false
        event(db,LearningStats.Event(expected.word,now,remembered))
        db.execSQL("DELETE FROM review_events WHERE id NOT IN (SELECT id FROM review_events ORDER BY id DESC LIMIT ${LearningArchive.MAX_EVENTS})"); true
    }
    fun remove(word:String):Boolean=transaction { it.delete("words","word=?",arrayOf(word))>0 }
    fun clear()=transaction { it.delete("words",null,null); Unit }
    fun restore(data:LearningArchive.Snapshot,mode:LearningArchive.Restore):LearningArchive.ImportResult {
        LearningArchive.validate(data)
        return transaction { db ->
            if(mode==LearningArchive.Restore.REPLACE) db.delete("words",null,null)
            val existing=HashSet<String>()
            db.query("words",arrayOf("word"),null,null,null,null,null).use { c -> while(c.moveToNext()) existing.add(c.getString(0)) }
            val added=data.entries.filterNot { it.word in existing }
            require(existing.size+added.size<=LearningArchive.MAX_WORDS) { "合并后超过3000个词，请整理后重试" }
            added.forEach { insert(db,it) }; val addedWords=added.mapTo(HashSet()) { it.word }
            data.history.filter { it.word in addedWords }.forEach { event(db,it) }
            db.execSQL("DELETE FROM review_events WHERE id NOT IN (SELECT id FROM review_events ORDER BY at DESC, id DESC LIMIT ${LearningArchive.MAX_EVENTS})")
            LearningArchive.ImportResult(added.size,data.entries.size-added.size,existing.size+added.size)
        }
    }
    private fun exists(db:SQLiteDatabase,word:String)=db.rawQuery("SELECT 1 FROM words WHERE word=?",arrayOf(word)).use { it.moveToFirst() }
    private fun count(db:SQLiteDatabase)=db.rawQuery("SELECT COUNT(*) FROM words",null).use { it.moveToFirst(); it.getInt(0) }
    private fun values(e:WordBook.Entry)=ContentValues().apply {
        put("word",e.word);put("english",e.english);put("level",e.level);put("saved",e.savedAt);put("streak",e.progress.streak)
        put("due",e.progress.dueAt);put("attempts",e.progress.attempts);put("correct",e.progress.correct);put("reviewed",e.lastReviewedAt)
    }
    private fun insert(db:SQLiteDatabase,e:WordBook.Entry) { db.insertOrThrow("words",null,values(e)) }
    private fun event(db:SQLiteDatabase,e:LearningStats.Event) { db.insertOrThrow("review_events",null,ContentValues().apply { put("word",e.word);put("at",e.at);put("remembered",if(e.remembered)1 else 0) }) }
    private fun <T> transaction(action:(SQLiteDatabase)->T):T {
        backgroundOnly()
        val db=writableDatabase; db.beginTransaction()
        return try { action(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun backgroundOnly() { check(Looper.myLooper()!=Looper.getMainLooper()) { "学习数据库必须在后台线程访问" } }
}
