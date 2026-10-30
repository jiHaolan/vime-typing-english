package com.vime.ime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import android.os.Handler
import android.os.Looper
import com.vime.ime.data.PersonalLexicon
import com.vime.ime.engine.ClipboardSession
import com.vime.ime.engine.InputEngine
import com.vime.ime.engine.MemoryEngine
import com.vime.ime.voice.VoiceDiagnostics
import java.util.concurrent.Executors

/** App-owned data only. No system clipboard reads, broad file erasure or exported IPC. */
class InputDataModel(app: Application) : AndroidViewModel(app) {
    data class State(val busy: Boolean = false, val message: String = "")
    val state = MutableLiveData(State())
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var cleared = false
    private val appContext get() = getApplication<Application>()
    private fun complete(result: Result<String>) {
        if (!cleared) state.value = State(message = result.getOrElse { "清理未完成，请重试。已清除的内容不能撤销。" })
    }
    private fun run(message: String, work: () -> String) {
        if (state.value?.busy == true) return
        state.value = State(true, message)
        io.execute { val result = runCatching(work); main.post { complete(result) } }
    }
    fun resetHabits() = run("正在重置选词习惯…") {
        if (InputEngine.resetPersonalization(appContext)) "选词习惯已重置"
        else "选词习惯已清除，智能拼音暂未就绪，可在输入设置重试。"
    }
    fun clearPhrases() = run("正在清空自定义短语…") { PersonalLexicon.clearPhrases(); "自定义短语已清空" }
    fun clearRecents() = run("正在清除最近使用的符号与表情…") {
        check(appContext.getSharedPreferences("vime_symbol_history", 0).edit().clear().commit())
        "最近使用的符号与表情已清除"
    }
    fun clearVoiceStatus() = run("正在清除语音故障记录…") { VoiceDiagnostics.clear(appContext); "语音故障记录已清除" }
    fun clearClipboard() = run("正在清空临时历史…") { ClipboardSession.shared.clear(); "本应用的临时剪贴板历史已清空" }
    fun resetAnnotations() {
        if (state.value?.busy == true) return
        state.value = State(true, "正在重置英文提示状态…")
        MemoryEngine.resetAnnotations(appContext) { result -> complete(result.map { "英文提示状态已重置" }) }
    }
    override fun onCleared() { cleared = true; io.shutdown(); super.onCleared() }
}
