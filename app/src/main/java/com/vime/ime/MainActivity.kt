package com.vime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.DictRepository
import com.vime.ime.data.LevelPref
import com.vime.ime.data.ThemePref
import com.vime.ime.data.KeyboardPrefs
import com.vime.ime.data.WordBook
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.vime.ime.databinding.ActivityMainBinding
import com.vime.ime.engine.MemoryEngine
import java.io.File

/**
 * 启动入口。输入法本体 VimeImeService 没有界面，用户必须经这里两件事：
 *  ① 在系统设置里勾选本输入法
 *  ② 切换成当前输入法
 * 顺带后台预热词典（首次解析 1.8MB JSON 约 1–3 秒），避免第一次唤出键盘时白等。
 *
 * 「为什么装了没反应」八成是卡在第 ① 或第 ② 步，所以这里会实时检测状态并直接告诉用户。
 *
 * T05 新增：词库分级开关（CET4 / CET6 / TOEFL / 全部）。改这里和点键盘右侧的档位小标签
 * 是同一个偏好（`vime_prefs` 的 `dict_level`），改完键盘上的候选栏会**立刻**跟着变。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var appliedTheme = ""

    /** 演示区用的真实词条：中文 / 英文释义 / 等级，三级各一条，切档时肉眼可见差异 */
    private val demos = listOf(
        Triple("你好", "hello", LevelPref.CET4),
        Triple("学习", "learn", LevelPref.CET4),
        Triple("世界", "world", LevelPref.CET4)
    )

    private val onLevelChanged: (String) -> Unit = { runOnUiThread { refreshLevelUi() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this)
        appliedTheme = ThemePref.signature(this)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.window(this)

        LevelPref.init(this)
        MemoryEngine.init(this)

        bindLevelChips()
        refreshLevelUi()

        b.btnEnable.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        b.btnDictionary.setOnClickListener { startActivity(Intent(this, DictionaryActivity::class.java)) }
        b.btnVoiceConfig.setOnClickListener { startActivity(Intent(this, VoiceSettingsActivity::class.java)) }
        b.btnInputSettings.setOnClickListener { startActivity(Intent(this, InputSettingsActivity::class.java)) }
        b.btnStudy.setOnClickListener { startActivity(Intent(this, LearningActivity::class.java)) }
        b.btnTheme.setOnClickListener {
            AlertDialog.Builder(this).setTitle("皮肤配色")
                .setSingleChoiceItems(ThemePref.skinNames.toTypedArray(), ThemePref.skin(this)) { dialog, which ->
                    ThemePref.setSkin(this, which); dialog.dismiss(); recreate()
                }.setNeutralButton("深浅色模式") { _, _ ->
                    val modes = listOf(ThemePref.SYSTEM, ThemePref.LIGHT, ThemePref.DARK)
                    AlertDialog.Builder(this).setTitle("深浅色模式")
                        .setSingleChoiceItems(arrayOf("跟随系统", "浅色", "深色"), modes.indexOf(ThemePref.mode(this))) { dialog, which ->
                            ThemePref.set(this, modes[which]); dialog.dismiss(); recreate()
                        }.setNegativeButton("取消", null).show()
                }.setNegativeButton("取消", null).show()
        }
        b.btnHaptics.setOnClickListener {
            AlertDialog.Builder(this).setTitle("按键触感 · 遵循系统设置")
                .setSingleChoiceItems(KeyboardPrefs.hapticNames.toTypedArray(), KeyboardPrefs.haptics(this)) { dialog, which ->
                    KeyboardPrefs.setHaptics(this, which); HapticFeedback.play(this, which)
                    dialog.dismiss(); refreshAppearance()
                }.setNegativeButton("取消", null).show()
        }
        listOf(b.btnEnable, b.btnDictionary, b.btnStudy, b.btnTheme, b.btnHaptics, b.btnVoiceConfig,
            b.lvCet4, b.lvCet6, b.lvToefl, b.lvAll).forEach { view ->
            ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = "android.widget.Button"
                }
            })
        }

        warmUpDict()
        showLastCrash()
    }

    /**
     * 上次启动崩过就把堆栈弹出来。
     * 真机上「装上打不开」这类问题，不用连 adb / 抓 logcat 也能看到原因。
     */
    private fun showLastCrash() {
        val f = File(filesDir, VimeApp.CRASH_FILE)
        if (!f.exists()) return
        val txt = runCatching { f.readText() }.getOrNull().orEmpty()
        f.delete()
        if (txt.isBlank()) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("上次启动崩了")
                .setMessage(txt.take(2200))
                .setPositiveButton("知道了", null)
                .setNeutralButton("复制") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", txt))
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != ThemePref.signature(this)) { recreate(); return }
        refreshStepState()
        refreshLevelUi()
        refreshAppearance()
        val entries = WordBook(this).all()
        val due = entries.count { it.progress.dueAt <= System.currentTimeMillis() }
        b.tvLearningCount.text = when {
            entries.isEmpty() -> "从第一个生词开始"
            due == 0 -> "今天的复习，已完成"
            else -> "$due 个词，等你温习"
        }
        b.tvLearningHint.text = if (entries.isEmpty()) "在键盘长按候选词，收进你的生词本。" else
            "已收藏 ${entries.size} 个词 · 每次回忆，让印象更深一点。"
        b.btnStudy.text = if (due > 0) "去复习 · $due 个待温习" else "打开生词本"
    }

    override fun onDestroy() {
        LevelPref.removeListener(onLevelChanged)
        super.onDestroy()
    }

    private fun bindLevelChips() {
        LevelPref.addListener(onLevelChanged)
        b.lvCet4.setOnClickListener { LevelPref.set(LevelPref.CET4) }
        b.lvCet6.setOnClickListener { LevelPref.set(LevelPref.CET6) }
        b.lvToefl.setOnClickListener { LevelPref.set(LevelPref.TOEFL) }
        b.lvAll.setOnClickListener { LevelPref.set(LevelPref.ALL) }
    }

    /** 四个档位按钮的选中态 + 演示区，都只认 LevelPref 当前值 */
    private fun refreshLevelUi() {
        val cur = LevelPref.level
        val chips = listOf(
            LevelPref.CET4 to b.lvCet4,
            LevelPref.CET6 to b.lvCet6,
            LevelPref.TOEFL to b.lvToefl,
            LevelPref.ALL to b.lvAll
        )
        for ((lvl, v) in chips) {
            val sel = lvl == cur
            v.setBackgroundResource(if (sel) R.drawable.pill_primary_bg else R.drawable.pill_bg)
            v.setTextColor(uiColor(if (sel) R.color.on_acc else R.color.ink_2))
            v.isSelected = sel
        }
        renderDemo(cur)
    }

    private fun refreshAppearance() {
        val theme = when (ThemePref.mode(this)) { ThemePref.LIGHT -> "浅色"; ThemePref.DARK -> "深色"; else -> "跟随系统" }
        b.btnTheme.text = "皮肤配色    ${ThemePref.skinNames[ThemePref.skin(this)]} · $theme  ›"
        val strength = KeyboardPrefs.hapticNames[KeyboardPrefs.haptics(this)]
        b.btnHaptics.text = "按键触感    $strength  ›"
        b.btnHaptics.contentDescription = "按键触感，$strength，点击选择关闭、轻、中、强，遵循系统触感设置"
    }

    /** 演示区直接用真实的候选条目布局与真实的标签判定，和键盘上的一模一样 */
    private fun renderDemo(level: String) {
        val items = listOf(b.demo1, b.demo2, b.demo3)
        demos.forEachIndexed { i, (zh, en, lvl) ->
            val item = items[i]
            CandidateView.applyLabel(item.enTag, en, lvl, level, zh)
            item.zhWord.text = zh
            item.root.setBackgroundResource(if (i == 0) R.drawable.cand_first_bg else 0)
        }
    }

    private fun warmUpDict() {
        if (DictRepository.ready) {
            showStat()
            return
        }
        b.tvStat.text = "正在载入词典（首次约 1–3 秒）…"
        Thread {
            DictRepository.load(this)
            runOnUiThread { if (!isFinishing) showStat() }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun showStat() {
        b.tvStat.text = when {
            DictRepository.ready ->
                "词典已就绪 · ${DictRepository.pinyinCount} 个拼音音节 · " +
                    "${DictRepository.enCount} 条英文释义 · ${DictRepository.lvlCount} 条已分级"
            DictRepository.state == DictRepository.State.FAILED ->
                "词典载入失败：${DictRepository.loadError ?: "未知错误"}\n（可重启应用重试；若持续失败请反馈）"
            else -> "正在载入词典…"
        }
    }

    /** 实时告诉用户卡在哪一步，而不是让他在设置页里迷路 */
    private fun refreshStepState() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = runCatching {
            imm.enabledInputMethodList.any { it.packageName == packageName }
        }.getOrDefault(false)

        val current = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()?.startsWith(packageName) == true

        b.tvStep1.text = if (enabled) "已在系统中启用" else "尚未在系统中启用"
        b.tvStep1.setTextColor(uiColor(if (enabled) R.color.success else R.color.ink))
        b.btnEnable.text = if (enabled) "查看系统设置" else "前往系统设置"

        b.tvStep2.text = if (current) "当前正在使用本输入法" else "请用手机系统的键盘菜单选择本输入法"
        b.tvStep2.setTextColor(uiColor(if (current) R.color.success else R.color.ink_2))
    }
}

