package com.vime.ime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.*
import java.util.concurrent.Executors

/** Explicit file import and lookup only. No background clipboard access or network requests. */
class DictionaryActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var query: EditText
    private val actions = ArrayList<Button>()
    private var busy = false
    private var staged: ByteArray? = null
    private val pronouncer by lazy { WordPronouncer(this) }
    private var pronunciationText = ""
    private lateinit var pronounceButton: Button
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background("正在校验词典包…") {
            val bytes = contentResolver.openInputStream(uri)?.let(LexiconStore::boundedBytes)
                ?: error("无法读取所选文件")
            val pack = LexiconPackCodec.read(bytes.inputStream())
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                staged = bytes
                AlertDialog.Builder(this).setTitle("安装离线词典？")
                    .setMessage("${pack.name}\n${pack.version}\n${pack.entries.size} 条拼音/释义记录\n授权：${pack.license}\n来源：${pack.source}\n\n将替换扩展包，内置词库保留。只安装可信来源的数据。")
                    .setNegativeButton("取消") { _, _ -> staged = null }
                    .setOnCancelListener { staged = null }
                    .setPositiveButton("安装") { _, _ ->
                        val data = staged ?: return@setPositiveButton
                        staged = null
                        background("正在安装并建立索引…") { DictRepository.install(this, data) }
                    }.show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this)
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gutter = resources.getDimensionPixelSize(R.dimen.page_gutter)
            setPadding(gutter, dp(16), gutter, dp(32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        Ui.window(this)
        body.addView(Ui.back(this, "‹ 返回") { finish() })
        Ui.space(body, 24)
        Ui.heading(this, body, "离线词典", "补充中文候选和英文释义，不用登录，也无需联网查询。")
        Ui.section(this, body, "我的词库")
        status = label("载入中…", 14f)
        status.setBackgroundResource(R.drawable.card_bg)
        status.setPadding(dp(20), dp(20), dp(20), dp(20))
        action("导入离线词包", true) { picker.launch(arrayOf("*/*")) }
        label("支持 .vimelex 格式 · 安装前会展示来源与授权", 12f)
        action("查看来源与授权") {
            val pack = DictRepository.installedPack
            AlertDialog.Builder(this).setTitle("词典来源与授权")
                .setMessage(pack?.let { "${it.notice}\n\n${it.licenseText}" } ?: "尚未安装扩展包。内置词库继续可用。")
                .setPositiveButton("关闭", null).show()
        }
        action("内置组件与词库来源") { startActivity(Intent(this,NoticesActivity::class.java)) }.id=R.id.dictionaryNotices
        action("移除扩展包 · 保留内置词库") {
            AlertDialog.Builder(this).setTitle("移除扩展词典？").setMessage("不会删除生词本或内置词库。可重新导入恢复。")
                .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                    background("正在恢复内置词库…") { DictRepository.uninstall(this) }
                }.show()
        }
        query = EditText(this).apply {
            hint = "输入中文词，或英文单词"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            setText(intent.getStringExtra("word").orEmpty().take(64))
            Ui.input(this)
            contentDescription = "输入中文词或英文单词"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { if (!busy) lookup(); true } else false
            }
        }
        Ui.section(this, body, "查一个词")
        body.addView(query)
        action("查找释义", true) { lookup() }
        result = label("输入一个词，释义会显示在这里。", 17f)
        result.setTextIsSelectable(true)
        result.setBackgroundResource(R.drawable.card_bg)
        result.setPadding(dp(20), dp(20), dp(20), dp(20))
        pronounceButton = action("播放英文发音") {
            pronouncer.speak(pronunciationText) { pronounceButton.text = it }
        }.apply { isEnabled = false }
        action("发音设置 · 手机文字转语音") { WordPronouncer.openSettings(this) }
        Ui.section(this, body, "更多查询")
        action("打开有道词典官网（外部浏览器）") {
            AlertDialog.Builder(this).setTitle("打开外部网站？")
                .setMessage("只打开有道词典主页，不传递当前输入框或聊天内容。外部网页需要联网，并遵循对方隐私政策。本应用尚未开通有道收费词典 API。")
                .setNegativeButton("取消", null).setPositiveButton("打开") { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dict.youdao.com/"))) }
                        .onFailure { status.text = "未找到可打开网页的浏览器" }
                }.show()
        }
        label("外部网站需要联网。应用仅打开主页，不传递当前查词内容。", 12f)
        background("正在载入本机词典…") { DictRepository.load(this) }
    }

    private fun lookup() {
        val word = query.text.toString().trim().take(64)
        if (word.isEmpty()) { result.text = "请先输入要查的词。"; return }
        val definition = OfflineDictionaryProvider.lookup(word)
        pronouncer.stop()
        pronunciationText = if (word.matches(Regex("[A-Za-z][A-Za-z'-]{0,63}"))) word
            else DictRepository.getEn(word).ifBlank { definition?.meaning.orEmpty() }
        pronounceButton.isEnabled = pronunciationText.any { it in 'a'..'z' || it in 'A'..'Z' }
        result.text = if (definition != null) "${definition.word}\n${definition.reading}\n${definition.meaning}\n\n${definition.source}"
        else {
            val reverse = DictRepository.enCandidates(word, 12).filter { it.word.equals(word, true) }
            if (reverse.isNotEmpty()) "${reverse.first().word}\n${reverse.first().en}\n\n英文反查为释义索引线索，不是完整权威英汉词典。"
            else "本机词典未收录「$word」。可导入扩展词典包；没有释义不等于无法输入中文。"
        }
    }
    private fun refresh() {
        val pack = DictRepository.installedPack
        status.text = "${if (DictRepository.ready) "词典就绪" else "词典未就绪"} · ${DictRepository.wordCount} 条拼音词条 · ${DictRepository.enCount} 条释义\n" +
            (pack?.let { "扩展包：${it.name} / ${it.version}\n${it.entries.size} 条记录 · ${it.license}" } ?: "未安装扩展包，正在使用内置词库") +
            (DictRepository.packError?.let { "\n$it" } ?: "") + (DictRepository.loadError?.let { "\n$it" } ?: "")
    }
    private fun background(message: String, work: () -> Unit) {
        if (busy) return
        busy = true; status.text = message; actions.forEach { it.isEnabled = false; it.alpha = 0.45f }
        worker.execute {
            val error = runCatching(work).exceptionOrNull()
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                busy = false; actions.forEach { it.isEnabled = true; it.alpha = 1f }; refresh()
                pronounceButton.isEnabled = pronunciationText.any { it in 'a'..'z' || it in 'A'..'Z' }
                if (error != null) status.text = "操作失败，原词库未替换：${error.message}\n${status.text}"
                if (error == null && query.text.isNotBlank()) lookup()
            }
        }
    }
    private fun alive() = !isFinishing && !isDestroyed
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float): TextView = Ui.label(this, value, size, secondary = size <= 14f).apply {
        setPadding(0, dp(8), 0, dp(12))
        body.addView(this)
    }
    private fun action(value: String, primary: Boolean = false, click: () -> Unit): Button {
        val button = Ui.button(this, value, primary) { if (!busy) click() }
        actions.add(button); body.addView(button)
        return button
    }
    override fun onStop() { pronouncer.stop(); super.onStop() }
    override fun onDestroy() { pronouncer.close(); staged = null; worker.shutdown(); super.onDestroy() }
}

