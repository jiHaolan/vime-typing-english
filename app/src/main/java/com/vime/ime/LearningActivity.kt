package com.vime.ime

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.WordBook
import com.vime.ime.data.ThemePref
import com.vime.ime.engine.NavigationRules
import androidx.activity.OnBackPressedCallback
import com.vime.ime.engine.MemoryEngine
import com.vime.ime.engine.LearningStats
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView

/** Local word book and active recall. No account, network API or chat capture. */
class LearningActivity : AppCompatActivity() {
    private lateinit var book: WordBook
    private lateinit var body: LinearLayout
    private var current: String? = null
    private var revealed = false
    private var completed = 0
    private var practice = false
    private var draft = ""
    private var bookPage = 0
    private var spelling: EditText? = null
    private var analytics = false
    private var chartWord = 0
    private val pronouncer by lazy { WordPronouncer(this) }

    override fun onStop() { pronouncer.stop(); super.onStop() }
    override fun onDestroy() { pronouncer.close(); super.onDestroy() }

    private fun pronunciation(parent: LinearLayout, english: String) {
        lateinit var play: TextView
        play = button("播放英文发音") { pronouncer.speak(english) { play.text = it } }
        parent.addView(play)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this)
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { returnFromStudy() }
        })
        book = WordBook(this)
        MemoryEngine.init(this)
        current = savedInstanceState?.getString("current")
        revealed = savedInstanceState?.getBoolean("revealed") ?: false
        completed = savedInstanceState?.getInt("completed") ?: 0
        practice = savedInstanceState?.getBoolean("practice") ?: false
        draft = savedInstanceState?.getString("draft").orEmpty()
        bookPage = savedInstanceState?.getInt("bookPage") ?: 0
        analytics = savedInstanceState?.getBoolean("analytics") ?: false
        chartWord = savedInstanceState?.getInt("chartWord") ?: 0
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("current", current); outState.putBoolean("revealed", revealed)
        outState.putInt("completed", completed); outState.putBoolean("practice", practice)
        outState.putString("draft", spelling?.text?.toString() ?: draft)
        outState.putInt("bookPage", bookPage)
        outState.putBoolean("analytics", analytics); outState.putInt("chartWord", chartWord)
        super.onSaveInstanceState(outState)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(uiColor(R.color.ink))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(10), 0, dp(10))
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): TextView =
        Ui.button(this, label, primary, action)

    private fun render() {
        pronouncer.stop()
        spelling = null
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gutter = resources.getDimensionPixelSize(R.dimen.page_gutter)
            setPadding(gutter, dp(16), gutter, dp(32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        Ui.window(this)
        val backLabel = when {
            analytics -> "‹ 返回生词本"
            practice -> "‹ 返回生词本"
            intent.getBooleanExtra("from_keyboard", false) -> "‹ 返回打字"
            else -> "‹ 返回首页"
        }
        body.addView(Ui.back(this, backLabel) { returnFromStudy() })
        Ui.space(body, 24)
        Ui.heading(this, body, if (analytics) "看见每一次进步" else if (practice) "想一想，再看答案" else "留住那些新认识的词", if (analytics) "实际学习记录与复习安排，都在这里。" else if (practice) "先回忆中文对应的英文，再给自己一个诚实的反馈。" else "在打字时收藏，在这里慢慢记住。")
        val entries = book.all()
        val now = System.currentTimeMillis()
        val due = entries.filter { it.progress.dueAt <= now }.sortedBy { it.progress.dueAt }
        val summary = Ui.card(body)
        summary.addView(Ui.label(this, if (practice) "本次已完成 $completed 个" else "${entries.size} 个收藏    ${due.size} 个待复习", 20f, true))
        Ui.space(summary, 8)
        summary.addView(Ui.label(this, "只收录你主动收藏的词，学习记录保存在本机。", 13f, secondary = true))
        if (analytics) { renderAnalytics(entries, now); return }
        if (!practice) {
            body.addView(button("学习数据与遗忘曲线") { analytics = true; render() })
            body.addView(button("发音设置 · 手机文字转语音") { WordPronouncer.openSettings(this) })
            if (entries.isEmpty()) {
                val empty = Ui.card(body)
                empty.addView(Ui.label(this, "你的第一个生词，会是什么？", 22f, true))
                Ui.space(empty, 12)
                empty.addView(Ui.label(this, "1  在中文键盘中输入一个词\n2  长按候选，点“加入生词本”\n3  回到这里，遮住英文试着回忆", 15f, secondary = true))
                empty.addView(button("返回，继续打字", true) { returnFromStudy() })
            } else {
                if (due.isNotEmpty()) body.addView(button("开始今日复习（${due.size}）", true) {
                    practice = true; current = null; revealed = false; draft = ""; render()
                })
                val pageCount = (entries.size + 29) / 30
                bookPage = bookPage.coerceIn(0, pageCount - 1)
                Ui.space(body, 20)
                Ui.section(this, body, "我的收藏 · 第 ${bookPage + 1} / $pageCount 页")
                entries.sortedByDescending { it.savedAt }.drop(bookPage * 30).take(30).forEach { e ->
                    val word = Ui.card(body)
                    word.addView(Ui.label(this, e.word, 23f, true))
                    Ui.space(word, 6)
                    word.addView(Ui.label(this, e.english, 17f, secondary = true))
                    pronunciation(word, e.english)
                    Ui.space(word, 12)
                    word.addView(Ui.label(this, "${e.level.ifBlank { "未分级" }} · 已复习 ${e.progress.attempts} 次 · " +
                        if (e.progress.dueAt <= now) "待复习" else "下次 ${android.text.format.DateFormat.format("MM-dd HH:mm", e.progress.dueAt)}", 12f, secondary = true))
                }
                if (bookPage > 0) body.addView(button("上一页") { bookPage--; render() })
                if (bookPage + 1 < pageCount) body.addView(button("下一页") { bookPage++; render() })
            }
            return
        }
        val entry = due.find { it.word == current } ?: due.firstOrNull()
        if (entry == null) {
            current = null
            body.addView(text("这一轮完成了", 24f, true))
            body.addView(text("记得的词按1、3、7、14、30天逐步安排；忘记的词10分钟后再练。此为基础间隔方案，不承诺学习效果。"))
            body.addView(button("回到生词本", true) { practice = false; render() })
            return
        }
        current = entry.word
        val recall = Ui.card(body)
        recall.addView(Ui.label(this, "看中文，回忆英文", 13f, secondary = true))
        Ui.space(recall, 16)
        recall.addView(Ui.label(this, entry.word, 36f, true))
        Ui.space(recall, 16)
        spelling = EditText(this).apply {
            hint = "可选：输入英文拼写"; textSize = 19f; minHeight = dp(56)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_FORCE_ASCII or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setTextColor(uiColor(R.color.ink)); setHintTextColor(uiColor(R.color.ink_2))
            setText(draft); isEnabled = !revealed
            Ui.input(this)
            contentDescription = "英文拼写，可不填写"
        }
        recall.addView(spelling)
        if (!revealed) {
            body.addView(button("查看答案，再判断是否记得", true) {
                draft = spelling?.text?.toString().orEmpty(); revealed = true; render()
            })
        } else {
            body.addView(text(entry.english, 26f, true))
            pronunciation(body, entry.english)
            if (draft.isNotBlank()) body.addView(text(
                if (normalize(draft) == normalize(entry.english)) "拼写与本词条一致" else "与本词条释义不同，请自行核对；同义表达可能仍然正确。", 13f))
            fun answer(remembered: Boolean) {
                // Update once; rendering immediately removes this item from the due queue.
                book.answer(entry.word, remembered)
                MemoryEngine.onRecall(entry.word, entry.level, remembered)
                MemoryEngine.flush()
                completed++; current = null; revealed = false; draft = ""; render()
            }
            body.addView(button("忘了 · 10分钟后再练") { answer(false) })
            body.addView(button("记得 · 安排下次复习", true) { answer(true) })
        }
        body.addView(button("返回生词本（不计成绩）") {
            practice = false; current = null; revealed = false; draft = ""; render()
        })
    }
    private fun normalize(value: String) = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun renderAnalytics(entries: List<WordBook.Entry>, now: Long) {
        val attempts = entries.sumOf { it.progress.attempts.toLong() }
        val correct = entries.sumOf { it.progress.correct.toLong() }
        val totals = Ui.card(body)
        totals.addView(Ui.label(this, "累计复习 $attempts 次 · 记得 $correct 次", 19f, true))
        totals.addView(Ui.label(this, if (attempts == 0L) "完成一次复习后，这里会出现你的记录。" else "自评记得率 ${correct * 100 / attempts}% · 属于自评记录，非考试成绩", 13f, secondary = true))
        val recent = LearningStats.recent(book.history(), now)
        val activity = Ui.card(body)
        activity.addView(Ui.label(this, "近7天实际复习", 20f, true))
        activity.addView(Ui.label(this, "${recent.sumOf { it.count }} 次复习 · ${recent.sumOf { it.remembered }} 次记得", 14f, secondary = true))
        val exact = recent.joinToString("；") { "${android.text.format.DateFormat.format("M/d", it.at)}：${it.count}次" }
        activity.addView(LearningChartView(this, recent).apply { contentDescription = "近7天复习柱状图，$exact" }, LinearLayout.LayoutParams(-1, dp(210)))
        activity.addView(Ui.label(this, exact, 12f, secondary = true))
        activity.addView(Ui.label(this, "每日记录从0.12版开始，最多保留最近10000次；旧版本累计次数保留，不补造历史日期。", 12f, secondary = true))
        val forecast = LearningStats.forecast(entries.map { it.progress.dueAt }, now)
        val upcoming = Ui.card(body)
        upcoming.addView(Ui.label(this, "未来7天复习安排", 20f, true))
        val forecastText = forecast.joinToString("；") { "${android.text.format.DateFormat.format("M/d", it.at)}：${it.count}词" }
        upcoming.addView(LearningChartView(this, forecast).apply { contentDescription = "待复习词数柱状图，$forecastText" }, LinearLayout.LayoutParams(-1, dp(210)))
        upcoming.addView(Ui.label(this, forecastText + "。今日包含逾期未复习的词；完成后会重新安排。", 12f, secondary = true))
        val curve = Ui.card(body)
        curve.addView(Ui.label(this, "遗忘曲线 · 估算", 20f, true))
        curve.addView(Ui.label(this, "用于理解复习间隔，不是实测记忆能力。记得后按1、3、7、14、30天延长；忘记则10分钟后再练。", 13f, secondary = true))
        val chartHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updateCurve(index: Int) {
            chartHolder.removeAllViews()
            val entry = entries.getOrNull(index)
            val halfLife = if (entry != null && entry.progress.attempts > 0 && entry.progress.streak == 0) 10.0 / 1440 else LearningStats.interval(entry?.progress?.streak ?: 0)
            val description = if (entry == null) "示意曲线：尚无收藏，按1天间隔展示。" else "${entry.word} · 连续记得 ${entry.progress.streak} 次" +
                (if (entry.lastReviewedAt > 0) " · 上次 ${android.text.format.DateFormat.format("MM-dd HH:mm", entry.lastReviewedAt)}" else " · 尚无新版复习日期")
            chartHolder.addView(Ui.label(this, description, 13f, secondary = true))
            chartHolder.addView(LearningChartView(this, halfLife = halfLife).apply { contentDescription = "估算遗忘曲线，指数衰减示意，间隔${halfLife}天时降至50%，并非测量值。" }, LinearLayout.LayoutParams(-1, dp(210)))
            chartHolder.addView(Ui.label(this, "模型：从100%起按指数衰减，以当前复习间隔作为半衰期，仅作提醒示意。", 12f, secondary = true))
        }
        if (entries.isNotEmpty()) {
            chartWord = chartWord.coerceIn(0, entries.lastIndex)
            curve.addView(Spinner(this).apply {
                minimumHeight = dp(48); contentDescription = "选择查看遗忘曲线的生词"
                adapter = ArrayAdapter(this@LearningActivity, android.R.layout.simple_spinner_dropdown_item, entries.map { it.word })
                setSelection(chartWord)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) { chartWord = i; updateCurve(i) }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }
        curve.addView(chartHolder); updateCurve(chartWord)
        body.addView(button("返回生词本", true) { analytics = false; render() })
    }
    private fun returnFromStudy() {
        if (analytics) { analytics = false; render(); return }
        if (NavigationRules.back(practice) == NavigationRules.Destination.BOOK) {
            practice = false; current = null; revealed = false; draft = ""; render()
        } else {
            val legacyKeyboardTask = intent.getBooleanExtra("from_keyboard", false) && !isTaskRoot
            finish()
            if (legacyKeyboardTask) moveTaskToBack(true)
        }
    }
}

