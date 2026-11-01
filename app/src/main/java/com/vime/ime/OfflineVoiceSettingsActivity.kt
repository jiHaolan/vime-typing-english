package com.vime.ime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.ThemePref
import com.vime.ime.voice.OfflineModelStore
import com.vime.ime.voice.VoiceSettings
import kotlinx.coroutines.*

class OfflineVoiceSettingsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var importButton: android.view.View
    private lateinit var useButton: android.view.View
    private lateinit var removeButton: android.view.View
    private var busy = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask("正在导入…") {
            val cancellation = currentCoroutineContext()
            var lastPercent = -1L
            val input = contentResolver.openInputStream(uri) ?: error("无法打开所选文件")
            input.use {
                OfflineModelStore.importModel(this@OfflineVoiceSettingsActivity, it, { cancellation.ensureActive() }) { count ->
                    val percent = count * 100 / OfflineModelStore.BYTES
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread { if (!isDestroyed && busy) status.text = "正在导入 $percent%" }
                    }
                }
            }
            "模型已导入，可以启用离线语音；下载的原文件可自行删除。"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this)
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gutter = resources.getDimensionPixelSize(R.dimen.page_gutter)
            setPadding(gutter, Ui.dp(this, 16), gutter, Ui.dp(this, 32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(column) })
        Ui.window(this)
        column.addView(Ui.back(this, "‹ 返回语音设置") { finish() })
        Ui.space(column, 20)
        Ui.heading(this, column, "离线语音", "中英文识别，在手机上完成。")
        val card = Ui.card(column)
        card.addView(Ui.label(this, "首次导入约93MB模型，之后断网可用。无需连接电脑或填写密钥。识别结果直接回到当前输入框。", 15f, secondary = true))
        Ui.space(card, 12)
        status = Ui.label(this, "", 15f, true).apply { id = R.id.offlineModelStatus; card.addView(this) }
        card.addView(Ui.button(this, "下载中英文模型") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OfflineModelStore.DOWNLOAD_URL))) }
                .onFailure { Toast.makeText(this, "请使用浏览器下载指定模型，再返回导入", Toast.LENGTH_LONG).show() }
        }.apply { id = R.id.offlineModelDownload })
        importButton = Ui.button(this, "导入已下载模型") { picker.launch(arrayOf("*/*")) }.apply { id = R.id.offlineModelImport; card.addView(this) }
        useButton = Ui.button(this, "在键盘中启用", true) {
            if (!OfflineModelStore.present(this)) return@button
            VoiceSettings.setMode(this, VoiceSettings.OFFLINE)
            Toast.makeText(this, "已启用离线语音，长按空格约半秒说话", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK); finish()
        }.apply { id = R.id.offlineModelUse; card.addView(this) }
        removeButton = Ui.button(this, "移除模型") {
            AlertDialog.Builder(this).setTitle("移除离线模型？")
                .setMessage("不影响打字和学习记录。移除后，需重新导入才能使用离线语音。")
                .setPositiveButton("移除") { _, _ -> runTask("正在移除…") {
                    val cancellation = currentCoroutineContext()
                    OfflineModelStore.remove(this@OfflineVoiceSettingsActivity) { cancellation.ensureActive() }
                    "离线模型已移除"
                } }.setNegativeButton("取消", null).show()
        }.apply { id = R.id.offlineModelRemove; card.addView(this) }
        Ui.space(card, 12)
        card.addView(Ui.label(this, "导入时检查文件完整性。离线模式不上传录音，临时录音会在识别结束或取消后删除。识别文字仍请确认，专有名词可能出错。", 13f, secondary = true))
        com.vime.ime.data.CustomTheme.apply(column)
        refresh()
    }
    private fun refresh(message: String? = null) {
        val supported = Process.is64Bit()
        val ready = OfflineModelStore.present(this)
        status.text = message ?: when {
            !supported -> "当前32位安装包暂未提供离线识别，可继续使用手机语音和正常打字。"
            ready -> "模型已导入 · 可离线识别中英文"
            else -> "尚未导入模型"
        }
        importButton.isEnabled = !busy && supported
        useButton.isEnabled = !busy && supported && ready
        removeButton.isEnabled = !busy && ready
        listOf(importButton,useButton,removeButton).forEach { it.alpha=if(it.isEnabled)1f else 0.42f }
        (useButton as TextView).text=if(ready) "在键盘中启用" else "请先导入模型"
    }
    private fun runTask(message: String, action: suspend () -> String) {
        if (busy) return
        busy = true; refresh(message)
        scope.launch {
            var outcome: String? = null
            try { outcome = withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { outcome = failure.message ?: "处理失败，请重试" }
            finally { busy = false; if (!isDestroyed) refresh(outcome) }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
