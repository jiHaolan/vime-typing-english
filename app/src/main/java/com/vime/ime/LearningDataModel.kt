package com.vime.ime

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.vime.ime.data.LearningArchive
import com.vime.ime.data.WordBook
import java.util.concurrent.Executors

/** Retains validated imports/export payloads across rotation, never in the saved-state Bundle. */
class LearningDataModel(app:Application):AndroidViewModel(app) {
    data class State(val data:LearningArchive.Snapshot?=null,val busy:Boolean=false,val message:String="",
        val staged:LearningArchive.Backup?=null,val export:ByteArray?=null)
    val state=MutableLiveData(State())
    private val book=WordBook(app)
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    private var cleared=false
    var exportPickerOpen=false; private set
    private val current get()=state.value ?: State()
    init { refresh() }
    fun refresh() {
        if(current.busy) return
        book.load { result -> if(!cleared) state.value=result.fold(
            { current.copy(data=it) },{ current.copy(message="学习记录暂时无法读取，原有数据保留。") }) }
    }
    fun prepareExport() {
        if(current.busy || exportPickerOpen) return
        state.value=current.copy(busy=true,message="正在准备学习备份…")
        book.load { result ->
            if(cleared) return@load
            result.onSuccess { data -> io({ LearningArchive.encode(data) }) { bytes ->
                state.value=current.copy(data=data,busy=false,message="请选择备份保存位置",export=bytes)
            } }.onFailure { fail("无法读取学习记录，请重试") }
        }
    }
    fun claimExportPicker():Boolean {
        if(current.export==null || exportPickerOpen) return false
        exportPickerOpen=true; return true
    }
    fun exportTo(uri:Uri?) {
        exportPickerOpen=false
        val bytes=current.export
        state.value=current.copy(export=null)
        if(uri==null) { state.value=current.copy(message="已取消导出"); return }
        if(bytes==null) { fail("备份准备已失效，请重新导出"); return }
        state.value=current.copy(busy=true,message="正在保存备份…")
        io({
            getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use { it.write(bytes); it.flush() }
                ?: error("output unavailable")
        }) { state.value=current.copy(busy=false,message="学习备份已保存") }
    }
    fun importFrom(uri:Uri?) {
        if(uri==null) { state.value=current.copy(message="已取消导入"); return }
        if(current.busy) return
        state.value=current.copy(busy=true,message="正在检查学习备份…",staged=null)
        io({ getApplication<Application>().contentResolver.openInputStream(uri)?.use(LearningArchive::read) ?: error("input unavailable") }) {
            state.value=current.copy(busy=false,staged=it,message="备份已检查，请选择导入方式")
        }
    }
    fun discard() { state.value=current.copy(staged=null,message="已放弃导入，本机记录保留") }
    fun restore(mode:LearningArchive.Restore) {
        val data=current.staged?.data ?: return
        if(current.busy) return
        state.value=current.copy(busy=true,message="正在导入学习记录…")
        book.restore(data,mode) restoreDone@ { result ->
            if(cleared) return@restoreDone
            result.onSuccess {
                state.value=current.copy(busy=false,staged=null,message="已导入 ${it.added} 个词"+if(it.kept>0)"，保留本机 ${it.kept} 个同名词的进度" else "")
                refresh()
            }.onFailure { fail("导入未完成，本机记录未改动。请检查备份后重试。") }
        }
    }
    fun clear() {
        if(current.busy) return
        state.value=current.copy(busy=true,message="正在清空收藏与复习记录…")
        book.clear { result -> if(!cleared) result.onSuccess {
            state.value=current.copy(busy=false,message="收藏与复习记录已清空"); refresh()
        }.onFailure { fail("未能清空，请重试") } }
    }
    private fun fail(message:String) { if(!cleared) state.value=current.copy(busy=false,export=null,message=message) }
    private fun <T> io(work:()->T,done:(T)->Unit) {
        if(cleared) return
        worker.execute {
            val result=runCatching(work)
            main.post { if(!cleared) result.onSuccess(done).onFailure {
                fail("文件无法读取或保存，请重新选择有效的学习备份。本机学习记录未改动。")
            } }
        }
    }
    override fun onCleared() { cleared=true; worker.shutdown(); super.onCleared() }
}
