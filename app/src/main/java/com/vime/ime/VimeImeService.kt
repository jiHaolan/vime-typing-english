// Keyboard / KeyboardView 自 API 29 起标记 deprecated，但仍是官方唯一内置键盘容器；
// 阶段一有意复用（自绘键盘属后续项，见设计规格）。
@file:Suppress("DEPRECATION")

package com.vime.ime

import android.content.ClipboardManager
import android.graphics.Typeface
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.GridLayout
import android.widget.FrameLayout
import android.view.Gravity
import com.vime.ime.data.KeyboardExtras
import com.vime.ime.engine.LocalFormatting
import com.vime.ime.data.KeyboardPrefs
import com.vime.ime.engine.KeyHoldPolicy
import com.vime.ime.engine.KeyboardTuning
import android.widget.SeekBar
import android.view.MotionEvent
import org.json.JSONArray
import com.vime.ime.data.Cand
import com.vime.ime.data.DictRepository
import com.vime.ime.data.LevelPref
import com.vime.ime.data.ModePref
import com.vime.ime.databinding.ImeInputBinding
import com.vime.ime.databinding.PanelItemBinding
import com.vime.ime.engine.MemoryEngine
import com.vime.ime.engine.ClipboardSession
import com.vime.ime.engine.InputRules
import com.vime.ime.data.WordBook
import com.vime.ime.data.ThemePref
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.vime.ime.engine.QueryGate
import com.vime.ime.engine.RimeEngine
import com.vime.ime.engine.InputEngine
import com.vime.ime.engine.EnglishText

/**
 * 输入法服务主体（对应 Web 原型的 [IME] + [VIEW]）。
 *
 * v0.2/v0.3 修掉的三处致命问题（保留）：
 *  ① 候选栏从 candidatesView（独立窗口、不自动显示、位置漂移）搬进 inputView。
 *  ② 候选查询是四层兜底（见 DictRepository.candidates），不是只认完整拼音。
 *  ③ 词典加载期间有明确提示，失败原因写在候选栏。
 *
 * v0.4.0（T15）补齐的骨架：
 *  - **中英两态**：中文态走拼音候选，英文态字母直接上屏并给单词补全；
 *    标点键 44/46 按语言态输出 ，。 或 , . ；空格键中文上首选、英文上字母。
 *  - **⇧ 三态**：无 → 单字母大写 → 全大写锁定（拼音没有大小写，中文态按 ⇧ 直接切英文）。
 *  - **三块面板**：字母 / 符号 / 数字，全部等高 217dp，切换时窗口不跳。
 *  - **换行键按 imeOptions 取文案**：搜索 / 发送 / 前往 / 下一项 / 完成 / 换行。
 *  - **候选翻页**：每页 5 条，超过 5 条才出 ‹ ›；翻页只换候选，不改已输入的拼音。
 *  - **收起键盘**：候选栏右侧 ⌄，`requestHideSelf(0)`。
 *
 * T05 的词库分级（levelChip / applyLabel / 四层候选兜底）全部保留，未退化。
 */
class VimeImeService : InputMethodService(), KeyboardView.OnKeyboardActionListener {

    private enum class Panel { QWERTY, NINE, SYMBOLS, NUMBER, NUMPAD }

    /** 工具箱 / 键盘选择 面板，以及它们打开的二级面板 */
    private enum class Sheet { NONE, TOOLBOX, KEYBOARD, EDIT, PHRASES, TRANSLATE, SIZE, CLIPBOARD, THEME, EMOJI, SYMBOLS, FORMAT, HELP, CANDIDATES }
    private var expandedOffset = 0

    private data class SheetItem(
        val icon: String,
        val label: String,
        val enabled: Boolean,
        val onPick: (() -> Unit)? = null
    )

    private var sheet = Sheet.NONE
    private val clipboardSession = ClipboardSession()
    private var privateInput = false
    private var learningAllowed = true
    private var suppressSuggestions = false
    private var clipboardRevealed = false
    private var previousWord = ""
    private var keyboardContext: Context? = null
    private var backspaceConnection: InputConnection? = null
    private var emojiTab = "Emoji"
    private var symbolTab = "中文"
    private var symbolsLocked = true

    private var binding: ImeInputBinding? = null
    private var cardSaveSerial = 0L

    /** Chinese composition only. English letters are committed immediately. */
    private val buffer = StringBuilder()
    private var englishCandidatePrefix = ""
    private val pronouncer by lazy { WordPronouncer(this) }
    private val main = Handler(Looper.getMainLooper())
    private val candidateWorker = Executors.newSingleThreadExecutor()
    private var candidateJob: Future<*>? = null
    private val queryGate = QueryGate()

    private var panel = Panel.QWERTY

    /** 用户选的字母键盘形态（全键 / 九键），下次唤起键盘时恢复 */
    private var preferredPanel = Panel.QWERTY

    /** 键盘高度缩放（工具箱 → 键盘调节） */
    private var keyboardScale = 1f
    private var keyboardWidth = 100
    private var keyboardPosition = 50
    private var keyboardBottom = 0
    private var editorSerial = 0L
    private val voice by lazy { InlineVoiceController(this, { text, listening, rms ->
        binding?.let { b ->
            b.voiceStrip.visibility = if (text == null) View.GONE else View.VISIBLE
            b.candidateContent.visibility = if (text == null) View.VISIBLE else View.GONE
            b.candidateBar.visibility = View.VISIBLE
            b.voiceStatus.text = text
            b.voicePulse.listening = listening; b.voicePulse.rms = rms
        }
    }, { text -> showVoiceHint(text) }) }

    /** 常用语：点一下直接上屏 */
    private val PHRASES = listOf(
        "好的", "收到", "稍等", "谢谢", "马上", "没问题", "麻烦你了", "不好意思"
    )

    /** 快捷翻译的本地词表样本：点中文上英文（用的是词典里已有的义项，不联网） */
    private val TRANS_WORDS = listOf(
        "你好", "谢谢", "再见", "请", "对不起", "时间", "工作", "学习"
    )
    private var english = false

    /** 0 = 无，1 = 下一个字母大写，2 = 全大写 */
    private var shift = 0
    private var page = 0
    private var totalPages = 1

    /** 当前 buffer 对应的全部候选（翻页从这里切片，空格上屏取 first） */
    private var candsAll: List<Cand> = emptyList()

    /** 上一次写进输入框的 composition，用来避免无意义的 setComposingText 打断光标 */
    private var lastComposing: String? = null

    private var actionLabel = "换行"

    @Volatile
    private var loading = false

    /** 分级开关变更 → 只重画候选栏，不重建词典 */
    private val onLevelChanged: (String) -> Unit = {
        main.post { page = 0; renderCandidates() }
    }

    override fun onCreate() {
        super.onCreate()
        MemoryEngine.init(this)
        LevelPref.init(this)
        ModePref.init(this)
        english = ModePref.isEnglish
        preferredPanel = if (getSharedPreferences("vime_ime", MODE_PRIVATE).getString("letters", "QWERTY") == "NINE") Panel.NINE else Panel.QWERTY
        keyboardScale = KeyboardPrefs.height(this) / 100f
        keyboardWidth = KeyboardPrefs.width(this); keyboardPosition = KeyboardPrefs.position(this); keyboardBottom = KeyboardPrefs.bottom(this)
        symbolsLocked = getSharedPreferences("vime_ime", MODE_PRIVATE).getBoolean("symbols_locked", true)
        LevelPref.addListener(onLevelChanged)
        ensureDict()
    }

    override fun onCreateInputView(): View {
        return try {
            buildInputView()
        } catch (t: Throwable) {
            // 视图构建失败时，系统只会「不显示键盘」，用户看到的就是「点了没反应」。
            // 宁可把错误摊在键盘位置上，也不要静默失败。
            Log.e(TAG, "onCreateInputView failed", t)
            runCatching {
                File(filesDir, VimeApp.CRASH_FILE).writeText(
                    "onCreateInputView 构建失败\n" +
                        "${t.javaClass.name}: ${t.message}\n\n" +
                        Log.getStackTraceString(t)
                )
            }
            TextView(this).apply {
                text = "键盘加载失败\n${t.javaClass.simpleName}: ${t.message}"
                setTextColor(0xFFD1453B.toInt())
                textSize = 13f
                setPadding(28, 40, 28, 40)
                setBackgroundColor(0xFFFFF3F1.toInt())
            }
        }
    }

    private var renderedTheme: String? = null

    private fun buildInputView(): View {
        val themed = ThemePref.keyboardContext(this)
        keyboardContext = themed
        val b = ImeInputBinding.inflate(LayoutInflater.from(themed))
        binding = b
        renderedTheme = ThemePref.signature(this)
        // Let enlarged candidate text fit without changing the keyboard touch geometry.
        val candidateTextHeight = ((themed.resources.getDimension(R.dimen.cand_tag_size) +
            themed.resources.getDimension(R.dimen.cand_zh_size)) * 1.3f +
            16 * themed.resources.displayMetrics.density).toInt()
        b.candidateBar.layoutParams.height = maxOf(themed.resources.getDimensionPixelSize(R.dimen.cand_height), candidateTextHeight)
        val toolbarLabel = b.tbSettings.getChildAt(1) as TextView
        val labelMetrics = toolbarLabel.paint.fontMetrics
        val toolbarContent = kotlin.math.ceil(labelMetrics.descent - labelMetrics.ascent +
            30 * themed.resources.displayMetrics.density).toInt()
        b.toolbar.layoutParams.height = maxOf((56 * themed.resources.displayMetrics.density).toInt(), toolbarContent)

        b.keyboard.apply {
            setOnKeyboardActionListener(this@VimeImeService)
            setVoiceGestureListener({ startInlineVoice() }, { voice.release() }, { voice.cancel(); renderCandidates() })
            // Preview is drawn inside the View, without a PopupWindow token.
            setPreviewEnabled(!privateInput)
            hapticLevel = KeyboardPrefs.haptics(this@VimeImeService)
            keyHaptics = hapticLevel > 0
        }
        applyPanel(if (english) Panel.QWERTY else preferredPanel)
        applyKeyboardScale(keyboardScale)

        b.cardOverlay.setOnClickListener { hideCard() }
        b.cardPanel.setOnClickListener { /* 吃掉点击，避免穿透到遮罩 */ }
        b.cardClose.setOnClickListener { hideCard() }

        // 词库分级：点一下切下一档（CET4 → CET6 → TOEFL → 全部 → CET4）
        b.levelChip.setOnClickListener {
            LevelPref.set(LevelPref.nextLevel())
            page = 0
            renderCandidates()
        }

        // 模式指示药丸 = 中英切换（与键盘上的中英键同一个动作）
        b.modePill.setOnClickListener { toggleLang() }
        // 候选翻页
        b.prevPage.setOnClickListener { turnPage(-1) }
        b.nextPage.setOnClickListener { turnPage(1) }
        // 收起键盘
        b.hideKb.setOnClickListener { voice.cancel(); requestHideSelf(0) }

        // 工具条（原型 buildToolbar：键盘 / 回顾 / 复习 / 音效 / 主题 / 帮助 / 设置）
        // 这里只挂真实有功能的 3 项，其余等对应功能实现再补，不放点了没反应的假按钮。
        // 工具条：⌨ 开「键盘选择」，⚙ 开「工具箱」，? 给用法提示
        b.tbIme.setOnClickListener { showSheet(Sheet.KEYBOARD) }
        b.tbHelp.setOnClickListener { showHelpHint() }
        b.tbClipboard.setOnClickListener { showSheet(Sheet.CLIPBOARD) }
        b.tbStudy.setOnClickListener { openStudy() }
        b.tbSettings.setOnClickListener { showSheet(Sheet.TOOLBOX) }
        b.tbEmoji.setOnClickListener { showSheet(Sheet.EMOJI) }
        b.voiceStop.setOnClickListener { voice.cancel(); renderCandidates() }
        // The bottom 8dp of toolbar tiles is inert, in addition to the 12dp separator.
        for (i in 0 until b.toolbar.childCount) {
            val tile = b.toolbar.getChildAt(i)
            var blocked = false
            tile.setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) blocked = e.y >= v.height - dp(8)
                blocked
            }
        }
        b.root.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (r - l != oldR - oldL) applyKeyboardScale(keyboardScale)
        }
        b.tbCollapse.setOnClickListener { voice.cancel(); requestHideSelf(0) }
        b.expandCandidates.setOnClickListener { expandedOffset = 0; showSheet(Sheet.CANDIDATES) }
        // 长按 ⚙ = 打开 App 设置页（面板占满了，给设置留个后门）
        b.tbSettings.setOnLongClickListener {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            true
        }

        // 面板：返回 / 收起 / 地球。二级面板的返回回到工具箱
        b.panelSheet.panelBack.setOnClickListener {
            if (sheet in listOf(Sheet.TOOLBOX, Sheet.KEYBOARD, Sheet.EMOJI, Sheet.SYMBOLS, Sheet.FORMAT, Sheet.HELP)) hideSheet()
            else showSheet(Sheet.TOOLBOX)
        }
        b.panelSheet.panelClose.setOnClickListener { hideSheet() }

        renderCandidates()
        com.vime.ime.data.CustomTheme.apply(b.root)
        return b.root
    }

    // 明确不使用 candidatesView：候选栏已经在 inputView 里
    override fun onCreateCandidatesView(): View? = null

    /**
     * 永不走全屏 IME 模式。
     *
     * 系统默认在「横屏 + 输入框较小」时让输入法占满整屏（extract mode），我们的
     * 候选栏 + 固定高度键盘是全屏模式下的异类布局，进去会被拉伸错位——这是真机
     * 横屏必现的问题，模拟器竖屏/桌面预览都看不到。直接拒绝，保持正常内嵌键盘。
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        voice.cancel(); editorSerial++
        super.onStartInput(info, restarting)
        queryGate.next()
        candidateJob?.cancel(false)
        backspaceConnection = null
        refreshActionLabel(info)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        voice.cancel(); editorSerial++
        super.onStartInputView(info, restarting)
        keyboardScale = KeyboardPrefs.height(this) / 100f
        keyboardWidth = KeyboardPrefs.width(this); keyboardPosition = KeyboardPrefs.position(this); keyboardBottom = KeyboardPrefs.bottom(this)
        // Home settings can change while the IME service stays alive.
        if (binding != null && renderedTheme != ThemePref.signature(this)) setInputView(onCreateInputView())
        binding?.keyboard?.let { it.hapticLevel = KeyboardPrefs.haptics(this); it.keyHaptics = it.hapticLevel > 0 }
        buffer.clear()
        previousWord = ""
        lastComposing = null
        page = 0
        shift = 0
        english = ModePref.isEnglish
        val inputType = info?.inputType ?: 0
        privateInput = InputRules.password(inputType)
        binding?.keyboard?.setPreviewEnabled(!privateInput)
        learningAllowed = InputRules.learningAllowed(inputType, info?.imeOptions ?: 0)
        suppressSuggestions = (inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        if (InputRules.ascii(inputType, info?.imeOptions ?: 0)) english = true
        refreshActionLabel(info)
        hideCard()
        hideSheet()
        ensureDict()
        applyPanel(if (InputRules.numeric(inputType)) Panel.NUMPAD else if (english) Panel.QWERTY else preferredPanel)
        renderCandidates()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        pronouncer.stop()
        voice.cancel(); editorSerial++
        super.onFinishInputView(finishingInput)
        queryGate.next()
        candidateJob?.cancel(false)
        backspaceConnection = null
        buffer.clear()
        previousWord = ""
        lastComposing = null
        hideCard()
    }

    override fun onDestroy() {
        pronouncer.close()
        queryGate.next()
        candidateWorker.shutdownNow()
        clipboardSession.clear()
        voice.close()
        LevelPref.removeListener(onLevelChanged)
        main.removeCallbacksAndMessages(null)
        // 把合并窗口里还没落盘的记忆状态写掉，避免最后几次上屏丢失
        MemoryEngine.flush()
        binding = null
        super.onDestroy()
    }

    // ────────────────────────── 词典预热 ──────────────────────────

    private fun ensureDict() {
        if (DictRepository.ready || loading) return
        loading = true
        Thread {
            DictRepository.load(this)
            main.post {
                loading = false
                renderCandidates()
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    // ────────────────────────── 面板与键盘同步 ──────────────────────────

    private fun applyPanel(p: Panel) {
        val b = binding ?: return
        if (p != panel && buffer.isNotEmpty()) finishPending()
        panel = p
        if (!english && (p == Panel.QWERTY || p == Panel.NINE)) {
            preferredPanel = p
            getSharedPreferences("vime_ime", MODE_PRIVATE).edit().putString("letters", p.name).apply()
        }
        val kb = Keyboard(
            this,
            when (p) {
                Panel.QWERTY -> R.xml.keyboard_qwerty
                Panel.NINE -> R.xml.keyboard_nine
                Panel.SYMBOLS -> R.xml.keyboard_symbols
                Panel.NUMBER -> R.xml.keyboard_number_row
                Panel.NUMPAD -> R.xml.keyboard_numpad
            }
        )
        b.keyboard.keyboard = kb

        // 诊断：Keyboard 内部解析异常会被它自己的 try-catch 吞掉，唯一表现是「键变少」。
        // 低于预期就把实际情况记下来，下次开 App 弹窗可见。
        val expected = when (p) {
            Panel.QWERTY -> 37
            Panel.NINE -> 16
            Panel.SYMBOLS -> 43
            Panel.NUMBER -> 43
            Panel.NUMPAD -> 20
        }
        if (kb.keys.size < expected) {
            Log.w(TAG, "keyboard parse abnormal: panel=$p keys=${kb.keys.size}/$expected")
            runCatching {
                File(filesDir, VimeApp.CRASH_FILE).writeText(
                    "键盘解析不全（会被系统吞掉，表现为少几行）\n" +
                        "panel=$p\nkeys=${kb.keys.size}（预期 $expected）\nheight=${kb.height}"
                )
            }
        }
        syncKeyboard()
    }

    /** 把三个动态量写进键盘视图：语言态、⇧ 状态、换行键文案 */
    private fun syncKeyboard() {
        val b = binding ?: return
        b.keyboard.chineseMode = !english
        b.keyboard.shiftState = shift
        b.keyboard.actionLabel = actionLabel
        b.tbStudy.isEnabled = learningAllowed
        b.tbStudy.alpha = if (learningAllowed) 1f else 0.35f
        b.tbEmoji.isEnabled = !privateInput
        b.tbEmoji.alpha = if (privateInput) 0.35f else 1f
        b.keyboard.invalidate()
    }

    // ────────────────────────── 按键 ──────────────────────────

    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        val ic = currentInputConnection ?: return
        if (voice.active) voice.cancel()
        when (primaryCode) {
            Keyboard.KEYCODE_SHIFT -> onShift()

            Keyboard.KEYCODE_DELETE -> onDelete(ic)
            VimeKeyboardView.CODE_CLEAR -> clearEditor(ic)

            // 123 键 → 直接切数字面板（原型只有 123；符号面板从数字面板里的「符」进入）
            Keyboard.KEYCODE_MODE_CHANGE -> applyPanel(Panel.NUMBER)

            VimeKeyboardView.CODE_LANG -> toggleLang()
            VimeKeyboardView.CODE_ABC -> applyPanel(Panel.QWERTY)
            VimeKeyboardView.CODE_SYMBOLS -> showSheet(Sheet.SYMBOLS)
            VimeKeyboardView.CODE_NUMPAD -> applyPanel(Panel.NUMPAD)
            VimeKeyboardView.CODE_RETURN -> applyPanel(if (english) Panel.QWERTY else preferredPanel)

            Keyboard.KEYCODE_DONE -> onEnter(ic)

            32 -> onSpace(ic)

            // 44/46：字母键盘上按语言态出中文或半角标点；符号/数字面板上恒为半角
            44 -> commitText(ic, if (english || panel in listOf(Panel.SYMBOLS, Panel.NUMBER, Panel.NUMPAD)) "," else "，")
            46 -> commitText(ic, if (english || panel in listOf(Panel.SYMBOLS, Panel.NUMBER, Panel.NUMPAD)) "." else "。")

            in 97..122 -> onLetter(primaryCode)

            // 数字键：九键态进 buffer 走 T9，其余面板直接上屏
            in 48..57 -> {
                if (panel == Panel.NINE && !english) onNineDigit(primaryCode)
                else if (primaryCode > 0) commitText(ic, primaryCode.toChar().toString())
            }

            else -> {
                // 符号面板：codes 就是字符本身，所见即所得，不做全半角转换
                if (primaryCode > 0) commitText(ic, String(Character.toChars(primaryCode)))
            }
        }
    }

    /** ⇧ 三态。拼音没有大小写 —— 中文态按 ⇧ 先切英文，再进入「单字母大写」 */
    private fun onShift() {
        if (!english) {
            if (buffer.isNotEmpty()) commitRaw(buffer.toString())
            english = true
            ModePref.setEnglish(true)
            buffer.clear()
            page = 0
            clearComposing()
            renderCandidates()
        }
        shift = (shift + 1) % 3
        syncKeyboard()
    }

    private fun onLetter(code: Int) {
        if (english || privateInput) {
            val c = if (shift > 0) code.toChar().uppercaseChar() else code.toChar()
            currentInputConnection?.commitText(c.toString(), 1)
            if (shift == 1) { shift = 0; syncKeyboard() }
            page = 0
            renderCandidates()
            return
        }
        // 上限保护：candidates() 的第四层兜底是「逐位回退 + 二分前缀」，长度线性增长时
        // 单次查询退化成 O(n²)。真机上连按十几下就会明显掉帧。24 个字母足够覆盖任何拼音/单词。
        if (buffer.length >= MAX_INPUT) return
        val upper = english && shift > 0
        buffer.append(if (upper) code.toChar().uppercaseChar() else code.toChar())
        if (english && shift == 1) {
            shift = 0
            syncKeyboard()
        }
        page = 0
        renderCandidates()
    }

    /** 九键：累积数字串，候选由 T9 反查拼音 */
    private fun onNineDigit(code: Int) {
        if (privateInput) { currentInputConnection?.commitText(code.toChar().toString(), 1); return }
        if (buffer.length >= MAX_INPUT) return
        buffer.append(code.toChar())
        page = 0
        renderCandidates()
    }

    private fun onDelete(ic: InputConnection) {
        if (buffer.isNotEmpty()) {
            buffer.deleteAt(buffer.length - 1)
            page = 0
            renderCandidates()
        } else {
            previousWord = ""
            candsAll = emptyList()
            // Delete selected text first; do not split a supplementary Unicode code point.
            if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
            else if (!ic.deleteSurroundingTextInCodePoints(1, 0)) sendKey(KeyEvent.KEYCODE_DEL)
            renderCandidates()
        }
    }

    /** Only called after a held backspace is released on that key. No clipboard or text snapshot. */
    private fun clearEditor(ic: InputConnection) {
        if (backspaceConnection !== ic) {
            showHint("输入框已切换，已取消清空")
            return
        }
        ic.beginBatchEdit()
        val cleared = try {
            ic.finishComposingText()
            buffer.clear()
            lastComposing = null
            previousWord = ""
            candsAll = emptyList()
            page = 0
            // Never delete just the current selection if the editor refuses select-all.
            ic.performContextMenuAction(android.R.id.selectAll) && ic.commitText("", 1)
        } finally {
            ic.endBatchEdit()
        }
        renderCandidates()
        showHint(if (cleared) "已清空当前输入框" else "当前输入框不支持一键清空，请使用全选删除")
        main.postDelayed({ if (binding != null) renderCandidates() }, 1800)
    }

    private fun onSpace(ic: InputConnection) {
        val text = buffer.toString()
        when {
            // 没有待上屏内容：插一个空格（中英一致）
            text.isEmpty() -> {
                ic.commitText(" ", 1)
                previousWord = ""
                renderCandidates()
            }
            // 英文态「空格上屏字母」：上屏的就是用户敲的那串，不被候选改写
            english -> { commitRaw(text); ic.commitText(" ", 1) }
            else -> commitFirst()
        }
    }

    private fun onEnter(ic: InputConnection) {
        if (buffer.isNotEmpty()) {
            if (english) commitRaw(buffer.toString()) else commitFirst()
            return // Confirm composition only; never unexpectedly send a chat message.
        }
        previousWord = ""
        renderCandidates()
        val action = currentAction()
        val perform = when {
            action == EditorInfo.IME_ACTION_UNSPECIFIED -> 0
            action == EditorInfo.IME_ACTION_NONE -> 0
            // 多行输入框里的「完成」其实想换行，别把光标锁在原地
            action == EditorInfo.IME_ACTION_DONE && isMultiline() -> 0
            else -> action
        }
        if (perform != 0) {
            ic.performEditorAction(perform)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun currentAction(): Int {
        val opts = currentInputEditorInfo?.imeOptions ?: return EditorInfo.IME_ACTION_UNSPECIFIED
        return opts and EditorInfo.IME_MASK_ACTION
    }

    private fun isMultiline(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return (info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    }

    /** 换行键文案：按 imeOptions 取，绝不写死「完成」 */
    private fun refreshActionLabel(info: EditorInfo?) {
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        actionLabel = when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> if (((info?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0) "换行" else "完成"
            else -> "换行"
        }
        syncKeyboard()
    }

    // ────────────────────────── 上屏 ──────────────────────────

    /** 无候选时原样上屏字母，绝不吞键 —— 用户至少看到自己敲的东西 */
    private fun commitRaw(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        ic.finishComposingText()
        lastComposing = null
        buffer.clear()
        previousWord = ""
        page = 0
        renderCandidates()
    }

    private fun commitWord(c: Cand) {
        if (english) { commitEnglish(c, englishCandidatePrefix); return }
        val ic = currentInputConnection ?: return
        val raw = buffer.toString()
        if (!english) InputEngine.selected(raw, c, learningAllowed && !privateInput)
        if (sheet == Sheet.CANDIDATES) hideSheet()
        val remainder = if (!english && c.consumed > 0) InputRules.remaining(buffer.toString(), c.consumed) else ""
        ic.commitText(c.word, 1)
        ic.finishComposingText()
        if (learningAllowed && c.en.isNotBlank()) MemoryEngine.onCommitted(c.word, c.lvl)
        lastComposing = null
        buffer.clear()
        buffer.append(remainder)
        previousWord = if (!english && learningAllowed) c.word else ""
        page = 0
        renderCandidates()
    }

    /** 标点/符号：直接写入，并清掉可能残留的拼音 */
    private fun commitText(ic: InputConnection, s: String) {
        if (buffer.isNotEmpty()) {
            // 有拼音时先上屏首选，再落标点 —— 用户的标点不吞已输入内容
            finishPending()
        }
        ic.commitText(s, 1)
        ic.finishComposingText()
        lastComposing = null
        buffer.clear()
        previousWord = ""
        page = 0
        renderCandidates()
    }

    private fun commitFirst() {
        val text = buffer.toString()
        if (text.isEmpty()) return
        // Space may arrive before the worker result. Query the current buffer, never stale candsAll.
        val first = queryCandidates(text, english, panel == Panel.NINE, privateInput || (english && suppressSuggestions)).firstOrNull()
        if (first != null) commitWord(first) else commitRaw(text)
    }

    // ────────────────────────── 中英切换 / 翻页 ──────────────────────────

    private fun toggleLang() {
        if (buffer.isNotEmpty()) finishPending()
        english = !english
        previousWord = ""
        ModePref.setEnglish(english)
        buffer.clear()
        shift = 0
        page = 0
        clearComposing()
        // 九键没有英文形态，切英文时自动回全键
        if (english && panel == Panel.NINE) applyPanel(Panel.QWERTY)
        else if (!english && panel == Panel.QWERTY) applyPanel(preferredPanel)
        syncKeyboard()
        renderCandidates()
    }

    private fun turnPage(delta: Int) {
        val next = page + delta
        if (next < 0 || next >= totalPages || next == page) return
        page = next
        // 只重画候选，buffer 一动不动 —— 「翻页不改变已输入的拼音」
        renderCandidates()
    }

    // ────────────────────────── 候选栏 ──────────────────────────

    private fun renderCandidates() {
        if (voice.active) return
        val b = binding ?: return
        val text = if (english) currentEnglishPrefix() else buffer.toString()
        val token = queryGate.next()
        candidateJob?.cancel(false)
        // 档位标签与模式指示跟着每次重画刷新：不管是在键盘上点的，还是在设置页改的
        b.levelChip.text = LevelPref.chipText()
        b.modePill.text = if (privateInput) "私密" else if (english) "EN" else "中"

        if (text.isEmpty()) {
            val suggestions = if (!english && learningAllowed && panel in listOf(Panel.QWERTY, Panel.NINE))
                DictRepository.associations(previousWord, MAX_CANDS) else emptyList()
            if (suggestions.isNotEmpty()) {
                b.candidateBar.visibility = View.VISIBLE
                candsAll = suggestions
                b.hint.visibility = View.GONE
                b.scroll.visibility = View.VISIBLE
                CandidateView.render(b.candidateRow, suggestions,
                    { if (queryGate.accepts(token)) commitWord(it) },
                    { if (queryGate.accepts(token)) showCard(it) }, LevelPref.ALL)
                b.scroll.post { b.scroll.scrollTo(0, 0) }
                updatePageButtons()
                return
            }
            candsAll = emptyList()
            totalPages = 1
            page = 0
            b.candidateRow.removeAllViews()
            updatePageButtons()
            clearComposing()
            showHint(
                when {
                    privateInput -> "密码输入 · 不显示候选、不记录学习"
                    english -> "英文直输 · 字母即点即输，点候选可补全"
                    panel == Panel.NINE -> "九键 · 按数字输拼音，空格上屏"
                    else -> idleHint()
                }
            )
            if (DictRepository.ready) b.candidateBar.visibility = View.VISIBLE
            return
        }

        b.candidateBar.visibility = View.VISIBLE
        if (!english) compose(text)

        val en = english
        val nine = panel == Panel.NINE
        val suppress = privateInput || (english && suppressSuggestions)
        // Clear stale click targets while keeping the row height stable.
        b.candidateRow.isEnabled = false
        candidateJob = candidateWorker.submit {
            val all = queryCandidates(text, en, nine, suppress)
            if (!Thread.currentThread().isInterrupted) main.post {
                if (queryGate.accepts(token) && binding === b &&
                    (if (english) currentEnglishPrefix() else buffer.toString()) == text) {
                    b.candidateRow.isEnabled = true
                    displayCandidates(all, text, token)
                }
            }
        }
    }

    private fun queryCandidates(text: String, en: Boolean, nine: Boolean, suppress: Boolean): List<Cand> = when {
        suppress -> emptyList()
        en -> DictRepository.enCandidates(text, MAX_CANDS)
        nine -> InputEngine.query(text, true, limit = MAX_CANDS, personalized = learningAllowed)
        else -> InputEngine.query(text, false, limit = MAX_CANDS, personalized = learningAllowed)
    }

    private fun displayCandidates(all: List<Cand>, text: String, token: Long) {
        val b = binding ?: return
        candsAll = all
        englishCandidatePrefix = if (english) text else ""

        if (all.isEmpty()) {
            b.candidateRow.removeAllViews()
            totalPages = 1
            page = 0
            updatePageButtons()
            showHint(
                if (!DictRepository.ready) loadingHint() else if (english) "英文直输 · 已输入 $text" else "「$text」无匹配 · 空格上屏原样"
            )
            return
        }

        totalPages = (all.size + PAGE_SIZE - 1) / PAGE_SIZE
        if (page >= totalPages) page = totalPages - 1
        if (page < 0) page = 0

        b.hint.visibility = View.GONE
        b.scroll.visibility = View.VISIBLE
        val slice = all.take(MAX_CANDS)
        CandidateView.render(
            container = b.candidateRow,
            cands = slice,
            onCommit = { c -> if (queryGate.accepts(token)) commitWord(c) },
            onLong = { c -> if (queryGate.accepts(token)) showCard(c) },
            level = LevelPref.ALL
        )
        updatePageButtons()
        b.scroll.post { b.scroll.scrollTo(0, 0) }
    }

    /** 候选超过 5 条才让 ‹ › 现身；不可翻的一侧变浅灰（INVISIBLE 保证布局不抖） */
    private fun updatePageButtons() {
        val b = binding ?: return
        b.expandCandidates.visibility = if (candsAll.isNotEmpty() && (buffer.isNotEmpty() || englishCandidatePrefix.isNotEmpty()) && !privateInput) View.VISIBLE else View.GONE
        b.prevPage.visibility = View.GONE
        b.nextPage.visibility = View.GONE
        b.prevPage.setTextColor(if (page > 0) 0xFF4A5260.toInt() else 0xFFC3C9D3.toInt())
        b.nextPage.setTextColor(if (page < totalPages - 1) 0xFF4A5260.toInt() else 0xFFC3C9D3.toInt())
    }

    /** 只在内容真变了的时候写 composition，避免翻页/重绘时打断光标 */
    private fun compose(text: String) {
        if (lastComposing == text) return
        currentInputConnection?.setComposingText(text, 1)
        lastComposing = text
    }

    private fun currentEnglishPrefix(): String {
        if (privateInput || suppressSuggestions) return ""
        val ic = currentInputConnection ?: return ""
        if (!ic.getSelectedText(0).isNullOrEmpty()) return ""
        val after = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        if (after != null && (after.isLetter() || after == '\'' || after == '-')) return ""
        return EnglishText.prefix(ic.getTextBeforeCursor(64, 0)?.toString().orEmpty())
    }

    private fun commitEnglish(c: Cand, expected: String) {
        val ic = currentInputConnection ?: return
        if (expected.isBlank() || currentEnglishPrefix() != expected) {
            renderCandidates(); return
        }
        ic.beginBatchEdit()
        try {
            if (ic.deleteSurroundingText(expected.length, 0))
                ic.commitText(EnglishText.matchingCase(c.word, expected), 1)
        } finally { ic.endBatchEdit() }
        englishCandidatePrefix = ""
        if (sheet == Sheet.CANDIDATES) hideSheet()
        renderCandidates()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (english && binding != null && (oldSelStart != newSelStart || oldSelEnd != newSelEnd)) renderCandidates()
    }

    private fun clearComposing() {
        if (lastComposing == null) return
        val ic = currentInputConnection ?: return
        ic.setComposingText("", 1)
        ic.finishComposingText()
        lastComposing = null
    }

    private fun showHint(msg: String) {
        val b = binding ?: return
        b.candidateBar.visibility = View.VISIBLE
        b.scroll.visibility = View.GONE
        b.hint.visibility = View.VISIBLE
        b.hint.text = msg
        b.hint.setOnClickListener(null)
        b.hint.isClickable = false
        b.hint.contentDescription = null
    }

    private fun showVoiceHint(msg: String) {
        showHint("点此查看语音设置 · $msg")
        binding?.hint?.apply {
            contentDescription="$msg，点按打开语音设置"
            setOnClickListener {
                voice.cancel()
                startActivity(Intent(this@VimeImeService,VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** 帮助：在候选栏提示位说明用法，2.8 秒后自动恢复候选 */
    private fun showHelpHint() {
        showHint("按住空格2秒说话 · 长按候选收藏 · 长按退格松手清空")
        main.postDelayed({ if (binding != null) renderCandidates() }, 2800)
    }

    // ────────────────────────── 工具箱 / 键盘选择 ──────────────────────────

    private fun showSheet(type: Sheet) {
        if (voice.active) voice.cancel()
        val b = binding ?: return
        if (type == Sheet.NONE) {
            hideSheet()
            return
        }
        if (type == Sheet.CLIPBOARD) clipboardRevealed = false
        sheet = type
        b.panelSheet.panelTitle.text = when (type) {
            Sheet.TOOLBOX -> "工具箱"
            Sheet.KEYBOARD -> "键盘选择"
            Sheet.EDIT -> "文本编辑"
            Sheet.PHRASES -> "常用语"
            Sheet.TRANSLATE -> "快捷翻译"
            Sheet.SIZE -> "键盘调节"
            Sheet.CLIPBOARD -> "剪贴板"
            Sheet.THEME -> "主题与触感"
            Sheet.EMOJI -> "表情"
            Sheet.SYMBOLS -> "符号"
            Sheet.FORMAT -> "本地排版 · 非AI生成"
            Sheet.HELP -> "使用说明"
            Sheet.CANDIDATES -> "展开候选"
            Sheet.NONE -> return
        }

        val items = when (type) {
            Sheet.TOOLBOX -> toolboxItems()
            Sheet.KEYBOARD -> keyboardItems()
            Sheet.EDIT -> editItems()
            Sheet.PHRASES -> phraseItems()
            Sheet.TRANSLATE -> translateItems()
            Sheet.SIZE -> emptyList()
            Sheet.CLIPBOARD -> emptyList()
            Sheet.THEME -> emptyList()
            Sheet.EMOJI, Sheet.SYMBOLS, Sheet.FORMAT, Sheet.HELP, Sheet.CANDIDATES -> emptyList()
            Sheet.NONE -> emptyList()
        }

        val grid = b.panelSheet.panelGrid
        val custom = b.panelSheet.panelContent
        custom.removeAllViews()
        val customPanel = type in listOf(Sheet.SIZE, Sheet.THEME, Sheet.CLIPBOARD, Sheet.EMOJI, Sheet.SYMBOLS, Sheet.FORMAT, Sheet.HELP, Sheet.CANDIDATES)
        custom.visibility = if (customPanel) View.VISIBLE else View.GONE
        grid.visibility = if (customPanel) View.GONE else View.VISIBLE
        if (type == Sheet.SIZE) renderSize()
        if (type == Sheet.THEME) renderAppearance()
        if (type == Sheet.CLIPBOARD) renderClipboard()
        if (type == Sheet.EMOJI || type == Sheet.SYMBOLS) renderPicker(type)
        if (type == Sheet.FORMAT) renderFormatting()
        if (type == Sheet.HELP) renderHelp()
        if (type == Sheet.CANDIDATES) renderExpandedCandidates()
        grid.removeAllViews()
        grid.rowCount = ((items.size + 3) / 4).coerceAtLeast(1)
        val inflater = LayoutInflater.from(keyboardContext ?: this)
        items.forEach { entry ->
            val item = PanelItemBinding.inflate(inflater, grid, false)
            item.itemIcon.text = entry.icon
            item.itemLabel.text = entry.label
            val glyph = when (entry.icon) {
                "贴" -> R.drawable.ic_clipboard
                "T", "文" -> R.drawable.ic_edit_text
                "语" -> R.drawable.ic_phrases
                "肤" -> R.drawable.ic_theme
                "调" -> R.drawable.ic_sliders
                "?" -> R.drawable.ic_help
                else -> null
            }
            if (glyph != null) {
                item.itemIcon.visibility = View.GONE
                item.itemGlyph.visibility = View.VISIBLE
                item.itemGlyph.setImageResource(glyph)
            }
            if (!entry.enabled) {
                // 未实现的功能明确置灰，而不是画一个点了没反应的按钮
                item.itemIcon.alpha = 0.28f
                item.itemLabel.alpha = 0.28f
            }
            item.root.setOnClickListener {
                if (entry.enabled) {
                    val before = sheet
                    entry.onPick?.invoke()
                    // onPick 如果打开了二级面板，就不要把它关掉
                    if (sheet == before) hideSheet()
                } else {
                    showHint("「${entry.label}」还没实现，这一版只接了可用项")
                    main.postDelayed({ if (binding != null) renderCandidates() }, 2200)
                }
            }
            grid.addView(item.root)
        }

        b.panelSheet.root.visibility = View.VISIBLE
    }

    private fun hideSheet() {
        val b = binding ?: return
        b.panelSheet.root.visibility = View.GONE
        sheet = Sheet.NONE
    }

    /**
     * 工具箱。参考百度输入法的同名面板，8 格。
     * 只有「剪贴板」接了真实实现；其余置灰，不做假按钮。
     */
    private fun toolboxItems(): List<SheetItem> = listOf(
        SheetItem("贴", "剪贴板", true) { showSheet(Sheet.CLIPBOARD) },
        SheetItem("T", "文本编辑", true) { showSheet(Sheet.EDIT) },
        SheetItem("语", "常用语", true) { showSheet(Sheet.PHRASES) },
        SheetItem("词", "离线词典", true) {
            finishPending()
            runCatching { startActivity(Intent(this, DictionaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        SheetItem("肤", "主题触感", true) { showSheet(Sheet.THEME) },
        SheetItem("调", "键盘调节", true) { showSheet(Sheet.SIZE) },
        SheetItem("文", "本地排版", true) { showSheet(Sheet.FORMAT) },
        SheetItem("?", "使用帮助", true) { showSheet(Sheet.HELP) }
    )

    /** 文本编辑：全部走 InputConnection，不依赖目标 App 提供 UI */
    private fun editItems(): List<SheetItem> = listOf(
        SheetItem("全", "全选", true) { contextAction(android.R.id.selectAll) },
        SheetItem("复", "复制", true) { contextAction(android.R.id.copy) },
        SheetItem("剪", "剪切", true) { contextAction(android.R.id.cut) },
        SheetItem("粘", "粘贴", true) { contextAction(android.R.id.paste) },
        SheetItem("←", "左移", true) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
        SheetItem("→", "右移", true) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
        SheetItem("删", "删除", true) { sendKey(KeyEvent.KEYCODE_DEL) },
        SheetItem("撤", "撤销", true) { sendCombo(KeyEvent.KEYCODE_Z) }
    )

    private fun phraseItems(): List<SheetItem> = PHRASES.map { p ->
        SheetItem(p.take(1), p, true) { commitPlain(p) }
    }

    /** 本地词表翻译：中文 → 词典里的英文义项，不联网 */
    private fun translateItems(): List<SheetItem> = TRANS_WORDS.map { w ->
        val en = DictRepository.getEn(w)
        if (en.isBlank()) SheetItem(w, "无译", false)
        else SheetItem(w, en, true) { commitPlain(en) }
    }

    private fun persistSize() = KeyboardPrefs.set(this, (keyboardScale * 100).toInt(), keyboardWidth, keyboardPosition, keyboardBottom)

    private fun applyKeyboardScale(scale: Float) {
        val b = binding ?: return
        val base = resources.getDimensionPixelSize(R.dimen.keyboard_height)
        val metrics = resources.displayMetrics
        val proportionalCap = (metrics.heightPixels * if (resources.configuration.orientation == 2) .48f else .43f).toInt()
        val header = b.candidateBar.layoutParams.height + b.toolbar.layoutParams.height + dp(12 + keyboardBottom)
        // screenHeightDp already excludes system bars. Reserve some editor space even with large text.
        val remaining = (dp(resources.configuration.screenHeightDp) - header - dp(32)).coerceAtLeast(dp(64))
        val maxHeight = minOf(proportionalCap, remaining)
        val availableWidth = b.root.width.takeIf { it > 0 } ?: metrics.widthPixels
        val width = availableWidth * keyboardWidth / 100
        b.keyboard.layoutParams = (b.keyboard.layoutParams as LinearLayout.LayoutParams).apply {
            height = (base * scale).toInt().coerceIn(minOf(dp(120), maxHeight), maxHeight)
            this.width = width
            marginStart = (availableWidth - width) * keyboardPosition / 100
        }
        b.bottomInset.layoutParams = b.bottomInset.layoutParams.also { it.height = dp(keyboardBottom) }
        b.keyboard.requestLayout()
    }

    private fun renderSize() {
        val column = customColumn(scroll = true)
        column.addView(panelText("连续调节，立即预览 · 设置自动保存；横屏高度受可用空间限制。"))
        val drag = panelText("↕ 按住这里上下拖动，调节高度", 15f).apply {
            gravity = Gravity.CENTER; minHeight = dp(48); setBackgroundResource(R.drawable.pill_bg)
        }
        var startY = 0f; var startHeight = 100
        drag.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startHeight = (keyboardScale * 100).toInt(); v.parent.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> { keyboardScale = KeyboardTuning.height(startHeight + ((startY - e.rawY) / resources.displayMetrics.density / 2).toInt()) / 100f; applyKeyboardScale(keyboardScale) }
                MotionEvent.ACTION_UP -> {
                    persistSize(); v.parent?.requestDisallowInterceptTouchEvent(false)
                    // Resizing can cancel a touch during measure. Never replace children in that callback.
                    main.post { if (sheet == Sheet.SIZE) renderSize() }
                }
                MotionEvent.ACTION_CANCEL -> { persistSize(); v.parent?.requestDisallowInterceptTouchEvent(false) }
            }; true
        }
        column.addView(drag)
        fun slider(name: String, low: Int, high: Int, value: Int, unit: String, update: (Int) -> Unit) {
            val label = panelText("$name  $value$unit")
            column.addView(label)
            column.addView(SeekBar(column.context).apply {
                contentDescription = name; minimumHeight = dp(48); max = high - low; progress = value - low
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) {
                        if (user) { update(p + low); label.text = "$name  ${p + low}$unit"; applyKeyboardScale(keyboardScale) }
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { persistSize() }
                })
            })
        }
        slider("高度", 70, 150, (keyboardScale * 100).toInt(), "%") { keyboardScale = it / 100f }
        slider("宽度", 65, 100, keyboardWidth, "%") { keyboardWidth = it }
        slider("左右位置（左 → 右）", 0, 100, keyboardPosition, "%") { keyboardPosition = it }
        slider("底部留白", 0, 48, keyboardBottom, "dp") { keyboardBottom = it }
        panelButton(column, "恢复默认尺寸") { keyboardScale = 1f; keyboardWidth = 100; keyboardPosition = 50; keyboardBottom = 0; persistSize(); applyKeyboardScale(1f); renderSize() }
    }

    private fun refreshSkin() {
        finishPending(); voice.cancel()
        val restorePanel = panel
        main.post { hideCard(); hideSheet(); setInputView(onCreateInputView()); applyPanel(restorePanel); renderCandidates() }
    }

    private fun renderExpandedCandidates() {
        val b = binding ?: return
        val text = if (english) currentEnglishPrefix() else buffer.toString()
        val serial = editorSerial; val start = expandedOffset
        val column = customColumn(scroll = true)
        column.addView(panelText("正在读取候选…"))
        val en = english; val nine = panel == Panel.NINE; val allowed = learningAllowed
        candidateWorker.submit {
            val items = if (en) DictRepository.enCandidates(text, start + 25).drop(start) else InputEngine.query(text, nine, start, 25, allowed)
            main.post {
                if (binding !== b || sheet != Sheet.CANDIDATES || serial != editorSerial ||
                    (if (english) currentEnglishPrefix() else buffer.toString()) != text || expandedOffset != start) return@post
                column.removeAllViews()
                column.addView(panelText("${start + 1} 起的候选 · ${if (en) "英文" else RimeEngine.label()}"))
                val nav = LinearLayout(column.context).apply { orientation = LinearLayout.HORIZONTAL }
                column.addView(nav)
                panelButton(nav, "上一页") { expandedOffset = (start - 24).coerceAtLeast(0); renderExpandedCandidates() }.apply { isEnabled = start > 0; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
                panelButton(nav, "返回键盘") { hideSheet() }.layoutParams = LinearLayout.LayoutParams(0,-2,1f)
                panelButton(nav, "下一页") { expandedOffset = start + 24; renderExpandedCandidates() }.apply { isEnabled = items.size > 24; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }
                val visible = items.take(24)
                var offset = 0
                while (offset < visible.size) {
                    val count = if (visible[offset].word.length > 6) 1 else minOf(3, visible.size - offset)
                    val row = LinearLayout(column.context).apply { orientation = LinearLayout.HORIZONTAL }
                    column.addView(row)
                    CandidateView.render(row, visible.subList(offset,offset+count),
                        { if (en) commitEnglish(it, text) else commitWord(it) }, { showCard(it) }, LevelPref.ALL)
                    for (i in 0 until row.childCount) {
                        row.getChildAt(i).layoutParams = LinearLayout.LayoutParams(0,-2,1f)
                        row.getChildAt(i).findViewById<TextView>(R.id.zhWord).apply { maxLines = 3; ellipsize = null }
                    }
                    offset += count
                }
                if (visible.isEmpty()) column.addView(panelText("没有更多候选"))
            }
        }
    }

    private fun renderAppearance() {
        val column = customColumn(scroll = true)
        panelButton(column, "输入与个性化 · 短语/配色/高级触感") {
            startActivity(Intent(this,InputSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        panelButton(column, "语音输入设置 · 默认免密钥") {
            voice.cancel()
            startActivity(Intent(this, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val inputManager=getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val externalVoice=inputManager.enabledInputMethodList.flatMap { info -> (0 until info.subtypeCount).map { info to info.getSubtypeAt(it) } }.filter { it.second.mode.equals("voice",true) }
        externalVoice.distinctBy { it.first.id }.forEach { (info,subtype) ->
            panelButton(column,"使用${info.loadLabel(packageManager)}语音面板") {
                voice.cancel()
                runCatching { if(android.os.Build.VERSION.SDK_INT>=28)switchInputMethod(info.id,subtype) else switchInputMethod(info.id) }.onFailure { showHint("无法切换，请检查系统键盘设置") }
            }
        }
        column.addView(panelText("皮肤配色 · 每种都提供浅色与深色"))
        ThemePref.skinNames.forEachIndexed { index, name ->
            panelButton(column, name + if (index == ThemePref.skin(this)) " ✓" else "") { ThemePref.setSkin(this, index); refreshSkin() }
        }
        column.addView(panelText("明暗模式"))
        listOf("跟随系统" to ThemePref.SYSTEM, "浅色" to ThemePref.LIGHT, "深色" to ThemePref.DARK).forEach { (label, mode) ->
            panelButton(column, label + if (mode == ThemePref.mode(this)) " ✓" else "") { ThemePref.set(this, mode); refreshSkin() }
        }
        column.addView(panelText("按键触感 · 遵循系统设置；强弱取决于手机振动马达"))
        KeyboardPrefs.hapticNames.forEachIndexed { index, name ->
            panelButton(column, "触感：$name" + if (index == KeyboardPrefs.haptics(this)) " ✓" else "") {
                KeyboardPrefs.setHaptics(this, index)
                binding?.keyboard?.let { it.hapticLevel = index; it.keyHaptics = index > 0 }
                HapticFeedback.play(this, index); renderAppearance()
            }
        }
    }

    private fun startInlineVoice() {
        if (privateInput || !learningAllowed) { showHint("当前私密输入框不启用语音输入"); return }
        finishPending(); hideSheet()
        val connection = currentInputConnection ?: return
        val serial = editorSerial
        voice.start(if (english) "en-US" else "zh-CN") { result ->
            if (serial == editorSerial && isInputViewShown && !privateInput && currentInputConnection === connection) {
                connection.commitText(result, 1); previousWord = ""; renderCandidates()
            }
        }
    }

    private fun sendKey(code: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Ctrl 组合键：Ctrl+A 全选 / Ctrl+Z 撤销 */
    private fun sendCombo(code: Int) {
        val ic = currentInputConnection ?: return
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun contextAction(id: Int) {
        val ok = currentInputConnection?.performContextMenuAction(id) == true
        if (!ok) {
            showHint("当前输入框不支持这个操作")
            main.postDelayed({ if (binding != null) renderCandidates() }, 1800)
        }
    }

    /** 原样上屏一段文本（常用语 / 翻译用） */
    private fun commitPlain(text: String) {
        val ic = currentInputConnection ?: return
        finishPending()
        ic.commitText(text, 1)
        ic.finishComposingText()
        lastComposing = null
        buffer.clear()
        previousWord = ""
        page = 0
        renderCandidates()
    }

    /**
     * 键盘选择。中文全拼 / 英文全键 是当前真实存在的两种形态，
     * 九键和全拼可用；手写、笔画、五笔、双拼暂未接入。
     */
    private fun keyboardItems(): List<SheetItem> = listOf(
        SheetItem("9拼", if (!english && panel == Panel.NINE) "拼音九键 ✓" else "拼音九键", true) {
            if (english) toggleLang()
            applyPanel(Panel.NINE)
        },
        SheetItem("26拼", if (!english && panel == Panel.QWERTY) "拼音全键 ✓" else "拼音全键", true) {
            if (english) toggleLang()
            applyPanel(Panel.QWERTY)
        },
        SheetItem("26英", if (english && panel == Panel.QWERTY) "英文全键 ✓" else "英文全键", true) {
            if (!english) toggleLang()
            applyPanel(Panel.QWERTY)
        },
        SheetItem("123", "数字符号", true) { applyPanel(Panel.NUMBER) },
        SheetItem("9数", "数字九宫格", true) { applyPanel(Panel.NUMPAD) }
    )

    /** 剪贴板里的文字直接上屏 */
    private fun commitClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        } else {
            ""
        }
        if (text.isBlank()) {
            showHint("剪贴板里没有可上屏的文字")
            main.postDelayed({ if (binding != null) renderCandidates() }, 2000)
            return
        }
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        ic.finishComposingText()
        lastComposing = null
        buffer.clear()
        page = 0
        renderCandidates()
    }

    private fun idleHint(): String = when {
        DictRepository.ready -> RimeEngine.label() + " · 上方显示英文释义"
        RimeEngine.state == RimeEngine.State.READY -> "智能拼音已就绪 · 英文词库载入中"
        DictRepository.state == DictRepository.State.FAILED -> "词典加载失败：${DictRepository.loadError ?: "未知错误"}"
        else -> "词典加载中…"
    }

    private fun loadingHint(): String = when {
        DictRepository.state == DictRepository.State.FAILED -> "词典加载失败：${DictRepository.loadError ?: "未知错误"}"
        else -> "词典加载中…"
    }

    // ────────────────────────── 单词卡 ──────────────────────────

    private fun showCard(c: Cand) {
        if (!learningAllowed) return
        val b = binding ?: return
        val card = DictRepository.getCard(c.word)
        val en = if (english) c.word else DictRepository.getEn(c.word).ifBlank { c.en }
        val def = if (english) c.en else listOfNotNull(card?.d1, card?.d2)
            .filter { it.isNotBlank() && it != "null" }
            .joinToString("；")

        b.cardWord.text = c.word
        b.cardPy.text = card?.py ?: ""
        b.cardPy.visibility = if (b.cardPy.text.isNullOrBlank()) View.GONE else View.VISIBLE
        b.cardEn.text = en
        b.cardEn.setTextColor((keyboardContext ?: this).uiColor(R.color.ink))
        b.cardEn.visibility = if (en.isBlank() || en == c.word) View.GONE else View.VISIBLE
        b.cardDef.text = def
        b.cardDef.visibility = if (def.isBlank() || def == en) View.GONE else View.VISIBLE
        b.cardDef.maxLines = Int.MAX_VALUE
        b.cardDef.ellipsize = null
        b.cardSpeak.visibility = if (en.any { it in 'a'..'z' || it in 'A'..'Z' }) View.VISIBLE else View.GONE
        b.cardSpeak.text = "播放英文发音"
        b.cardSpeak.setOnClickListener { pronouncer.speak(en) { b.cardSpeak.text = it } }
        b.cardSpeechSettings.setOnClickListener { pronouncer.stop(); WordPronouncer.openSettings(this) }
        val book = WordBook(this)
        val saveRequest = ++cardSaveSerial
        val gloss = en.ifBlank { c.en }
        val canSave = !english && gloss.isNotBlank()
        b.cardSave.text = "正在读取收藏…"
        b.cardSave.isEnabled = false
        fun updateSaveAppearance() {
            b.cardSave.setBackgroundResource(if (b.cardSave.isEnabled) R.drawable.pill_primary_bg else R.drawable.pill_bg)
            b.cardSave.setTextColor((keyboardContext ?: this).uiColor(if (b.cardSave.isEnabled) R.color.on_acc else R.color.ink_2))
        }
        updateSaveAppearance()
        book.contains(c.word) { result ->
            if(binding !== b || saveRequest != cardSaveSerial) return@contains
            val saved = result.getOrNull() == true
            b.cardSave.text = when { saved -> "已加入生词本"; result.isFailure -> "读取失败 · 点击重试收藏"; else -> "＋ 加入生词本" }
            b.cardSave.isEnabled = canSave && !saved
            updateSaveAppearance()
        }
        b.cardSave.setOnClickListener {
            b.cardSave.isEnabled = false; b.cardSave.text = "正在保存…"; updateSaveAppearance()
            book.save(c.word, gloss, c.lvl) { result ->
                if(binding !== b || saveRequest != cardSaveSerial) return@save
                b.cardSave.text = if(result.isSuccess) "已加入生词本" else "保存失败 · 点击重试"
                b.cardSave.isEnabled = result.isFailure && canSave; updateSaveAppearance()
            }
        }
        bindMeter(b, MemoryEngine.familiarity(c.word, c.lvl))

        // 主动回忆：这才是让掌握度真正往上走的东西（曝光最多 +10）
        b.cardForget.setOnClickListener {
            MemoryEngine.onRecall(c.word, c.lvl, false)
            hideCard()
            renderCandidates()
        }
        b.cardRemember.setOnClickListener {
            MemoryEngine.onRecall(c.word, c.lvl, true)
            hideCard()
            renderCandidates()
        }
        b.cardMute.setOnClickListener {
            MemoryEngine.muteThisWeek(c.word, c.lvl)
            hideCard()
            renderCandidates()
        }
        b.cardKnow.setOnClickListener {
            MemoryEngine.master(c.word, c.lvl)
            hideCard()
            renderCandidates()
        }

        if (b.cardOverlay.visibility == View.VISIBLE) return
        b.cardOverlay.alpha = 0f
        b.cardOverlay.visibility = View.VISIBLE
        b.cardOverlay.animate().alpha(1f).setDuration(110).start()
        b.cardPanel.scaleX = 0.94f
        b.cardPanel.scaleY = 0.94f
        b.cardPanel.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    /** 掌握度进度条：weightSum=100，用两段 View 的 weight 表示比例 */
    private fun bindMeter(b: ImeInputBinding, fam: Int) {
        val f = fam.coerceIn(0, 100)
        b.cardFam.text = f.toString()
        (b.cardMeterFill.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = f.coerceAtLeast(0).toFloat()
            b.cardMeterFill.layoutParams = it
        }
        (b.cardMeterRest.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = (100 - f).coerceAtLeast(0).toFloat()
            b.cardMeterRest.layoutParams = it
        }
    }

    private fun hideCard() {
        cardSaveSerial++
        pronouncer.stop()
        val b = binding ?: return
        if (b.cardOverlay.visibility != View.VISIBLE) return
        b.cardOverlay.visibility = View.GONE
    }

    private fun finishPending() {
        var previous = Int.MAX_VALUE
        while (buffer.isNotEmpty() && buffer.length < previous) {
            previous = buffer.length
            if (english) commitRaw(buffer.toString()) else commitFirst()
        }
        if (buffer.isNotEmpty()) commitRaw(buffer.toString())
    }

    private fun openStudy() {
        if (!learningAllowed) { showHint("当前输入框不启用学习功能"); return }
        finishPending()
        hideSheet()
        startActivity(Intent(this, LearningActivity::class.java)
            .putExtra("from_keyboard", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun customColumn(scroll: Boolean = false): LinearLayout {
        val container = binding!!.panelSheet.panelContent
        container.removeAllViews()
        val context = keyboardContext ?: this
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        if (scroll) {
            container.addView(ScrollView(context).apply { addView(column) }, FrameLayout.LayoutParams(-1, -1))
        } else container.addView(column, FrameLayout.LayoutParams(-1, -1))
        return column
    }

    private fun panelText(value: String, size: Float = 13f): TextView = TextView(keyboardContext ?: this).apply {
        text = value; textSize = size
        setTextColor(context.uiColor(R.color.ink_2))
        setPadding(dp(6), dp(8), dp(6), dp(8))
    }

    private fun panelButton(column: LinearLayout, label: String, action: () -> Unit): TextView {
        val button = panelText(label, 14f).apply {
            minHeight = dp(48); gravity = Gravity.CENTER
            setTextColor(context.uiColor(R.color.ink))
            setBackgroundResource(R.drawable.pill_bg)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
            setOnClickListener { action() }
        }
        column.addView(button)
        return button
    }

    private fun recentPicks(emoji: Boolean): List<String> {
        val raw = getSharedPreferences("vime_symbol_history", MODE_PRIVATE).getString(if (emoji) "emoji" else "symbols", "[]")
        val list = runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        return KeyboardExtras.recent(list, "", if (emoji) KeyboardExtras.allEmoji else KeyboardExtras.allSymbols)
    }

    private fun savePick(value: String, emoji: Boolean) {
        if (!learningAllowed) return
        val list = KeyboardExtras.recent(recentPicks(emoji), value, if (emoji) KeyboardExtras.allEmoji else KeyboardExtras.allSymbols)
        getSharedPreferences("vime_symbol_history", MODE_PRIVATE).edit()
            .putString(if (emoji) "emoji" else "symbols", JSONArray(list).toString()).apply()
    }

    private fun renderPicker(type: Sheet) {
        val emoji = type == Sheet.EMOJI
        val root = customColumn()
        if (emoji && privateInput) { root.addView(panelText("密码输入中不展示表情与最近使用。")); return }
        val catalog = if (emoji) KeyboardExtras.emoji else KeyboardExtras.symbols
        val selected = if (emoji) emojiTab else symbolTab
        val tabRow = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = HorizontalScrollView(root.context).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        root.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        (catalog.keys + "最近").forEach { tab ->
            tabRow.addView(panelText(tab).apply {
                gravity = Gravity.CENTER; minWidth = dp(62); minHeight = dp(48)
                setTextColor(context.uiColor(if (tab == selected) R.color.acc else R.color.ink_2))
                isFocusable = true
                setOnClickListener {
                    if (emoji) emojiTab = tab else symbolTab = tab
                    renderPicker(type)
                }
            })
        }
        if (!emoji) {
            root.addView(panelText(if (symbolsLocked) "连续输入：开  ·  点此关闭" else "连续输入：关  ·  输入后返回键盘").apply {
                minHeight = dp(40); isFocusable = true
                setOnClickListener {
                    symbolsLocked = !symbolsLocked
                    getSharedPreferences("vime_ime", MODE_PRIVATE).edit().putBoolean("symbols_locked", symbolsLocked).apply()
                    renderPicker(type)
                }
            })
        }
        val values = if (selected == "最近") {
            if (learningAllowed) recentPicks(emoji) else emptyList()
        } else catalog[selected].orEmpty()
        if (values.isEmpty()) {
            root.addView(panelText(if (!learningAllowed) "当前私密输入框不展示最近使用。" else "暂无最近使用。只保存本页预置符号，不读取聊天内容。"))
            return
        }
        val columns = if (emoji && selected == "颜文字") 3 else 6
        val grid = GridLayout(root.context).apply { columnCount = columns; rowCount = (values.size + columns - 1) / columns }
        values.forEachIndexed { index, value ->
            val cell = panelText(value, if (emoji && selected != "颜文字") 25f else 15f).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = value; isFocusable = true
                setTextColor(context.uiColor(R.color.ink))
                setBackgroundResource(R.drawable.pill_bg)
                layoutParams = GridLayout.LayoutParams(GridLayout.spec(index / columns), GridLayout.spec(index % columns, 1f)).apply {
                    width = 0; height = dp(48); setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                setOnClickListener {
                    commitPlain(value)
                    savePick(value, emoji)
                    if (!emoji && !symbolsLocked) hideSheet()
                    else if (selected == "最近") renderPicker(type)
                }
            }
            grid.addView(cell)
        }
        root.addView(ScrollView(root.context).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun renderFormatting() {
        val root = customColumn(scroll = true)
        root.addView(panelText("这是本地格式处理，不是AI总结、扩写或语义润色。请先在目标应用选中不超过4000字符的文字；没有选中内容时不会读取整个聊天框。"))
        listOf("整理空格" to LocalFormatting.Action.SPACES, "按句分段" to LocalFormatting.Action.PARAGRAPHS,
            "转换列表" to LocalFormatting.Action.BULLETS, "英文大写" to LocalFormatting.Action.UPPERCASE).forEach { (label, action) ->
            panelButton(root, label) {
                if (privateInput || buffer.isNotEmpty()) {
                    showHint("请先完成拼音，并在非密码框选择文字"); return@panelButton
                }
                val ic = currentInputConnection ?: return@panelButton
                val original = ic.getSelectedText(0)?.toString().orEmpty()
                val output = LocalFormatting.apply(original, action)
                if (output == null) { showHint("请先选中1—4000字符的文字"); return@panelButton }
                val preview = customColumn(scroll = true)
                preview.addView(panelText("本地排版预览（可上下滚动查看全文）\n$output", 15f))
                panelButton(preview, "替换选中文字") {
                    if (currentInputConnection === ic && !privateInput && ic.getSelectedText(0)?.toString() == original) {
                        ic.commitText(output, 1); previousWord = ""; hideSheet(); renderCandidates()
                    } else {
                        showHint("选区或输入框已变化，未替换"); renderFormatting()
                    }
                }
                panelButton(preview, "取消 · 返回选项") { renderFormatting() }
            }
        }
    }

    private fun renderHelp() {
        val root = customColumn(scroll = true)
        root.addView(panelText("打字与记词\n中文候选上方是小号英文，左右滑动看更多候选；长按候选查看词卡和主动收藏。生词本按钮进入复习。"))
        root.addView(panelText("键盘操作\n候选右侧可展开更多词并翻页，点击输入、长按查看词卡。字母角标长按约半秒松手输入；退格长按约0.9秒后松手清空、移开取消。横竖屏分别保存尺寸。自定义短语、配色和高级触感可在应用首页输入与个性化中管理。"))
        root.addView(panelText("布局与符号\n工具条键盘图标选择全键/拼音九键/数字布局；底行符号打开分类页，123打开数字符号，再点123可进数字九宫格。返回键回原字母布局。"))
        root.addView(panelText("真实能力边界\n按住空格满2秒开始录音，松手结束并识别；手机服务可能自行提前结束收音。默认调用手机语音，无需密钥，识别语言跟随键盘中英切换。候选栏显示动效，点取消、滑出空格或切换输入框即取消。服务是否联网由所选语音服务决定；可在首页语音设置中查看服务或选择高级联网接口。本地排版只处理选中文字，不是AI生成。图片表情包、OCR、手写/五笔/双拼及云端AI尚未接通，不显示伪可用入口。"))
        root.addView(panelText("隐私\n仅主动按住空格录音时申请麦克风权限；松手结束，隐藏键盘即取消。普通拼音离线。手机语音由选定服务处理，本应用不保存此路径的录音；高级联网模式向所填接口上传录音，并清理本机临时文件。不自动采集聊天内容。剪贴板仅主动打开面板后读取，临时历史需手动保存。表情/符号最近列表只保存预置条目，可在私密框隐藏。"))
    }

    /** Read only on explicit panel opening, never via a background clipboard listener. */
    private fun renderClipboard() {
        val container = binding?.panelSheet?.panelContent ?: return
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        val themed = keyboardContext ?: this
        val list = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 16, 12) }
        val scroll = ScrollView(themed).apply { addView(list) }
        container.addView(scroll)
        fun row(label: String, action: (() -> Unit)? = null) {
            list.addView(TextView(themed).apply {
                text = label; textSize = 14f; setTextColor(themed.uiColor(R.color.ink)); minHeight = (48 * dp).toInt()
                setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
                if (action != null) {
                    setBackgroundResource(R.drawable.pill_bg)
                    setOnClickListener { action() }
                }
            })
        }
        if (privateInput) { row("密码输入中不展示剪贴板。请使用系统的粘贴功能。"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = runCatching { cm?.primaryClip }.getOrNull()
        // Plain text only: do not dereference arbitrary clipboard content URIs.
        val current = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        val sensitive = clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true ||
            current?.trim()?.matches(Regex("[0-9]{4,8}")) == true
        row("只读取当前内容；本次历史需手动保存，10分钟后失效，不上传。")
        if (current.isNullOrEmpty()) row("当前剪贴板没有文字。请先在其他应用复制，再返回这里。")
        else {
            row(when {
                sensitive -> "敏感内容已隐藏 · 点此直接粘贴"
                !clipboardRevealed -> "当前文字已遮住（${current.length}字符）· 点此直接粘贴"
                else -> "当前 · ${current.take(180)}\n点击粘贴"
            }) {
                commitPlain(current); hideSheet()
            }
            if (!sensitive) row(if (clipboardRevealed) "隐藏当前预览" else "显示当前预览") {
                clipboardRevealed = !clipboardRevealed; renderClipboard()
            }
            if (!sensitive && current.length <= 2000) row("＋ 保存到本次临时历史") {
                clipboardSession.save(current, false, System.currentTimeMillis()); renderClipboard()
            }
        }
        clipboardSession.list(System.currentTimeMillis()).forEach { entry ->
            row(if (clipboardRevealed) "历史 · ${entry.text.take(180)}\n点击粘贴" else "历史文字（${entry.text.length}字符）· 点击粘贴") {
                if (clipboardSession.list(System.currentTimeMillis()).contains(entry)) {
                    commitPlain(entry.text); hideSheet()
                } else renderClipboard()
            }
        }
        if (clipboardSession.list(System.currentTimeMillis()).isNotEmpty()) row("清空本次临时历史") {
            clipboardSession.clear(); renderClipboard()
        }
    }

    // KeyboardView.OnKeyboardActionListener 其余空实现
    override fun onPress(primaryCode: Int) {
        backspaceConnection = if (primaryCode == Keyboard.KEYCODE_DELETE) currentInputConnection else null
    }
    override fun onRelease(primaryCode: Int) { backspaceConnection = null }
    override fun onText(text: CharSequence?) { if (!text.isNullOrEmpty()) commitPlain(text.toString()) }
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}

    private companion object {
        /** 每页候选数：超过这个数才出翻页箭头 */
        const val PAGE_SIZE = 5

        /** 一次最多取这么多候选（够翻 10 页），再多也没人翻 */
        const val MAX_CANDS = 12

        /** 已输入串长度上限，防长串把候选查询拖成 O(n²) */
        const val MAX_INPUT = 128

        const val TAG = "VimeIme"
    }
}

