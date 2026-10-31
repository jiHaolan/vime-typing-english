// Keyboard / KeyboardView 自 API 29 起 deprecated。
@file:Suppress("DEPRECATION")

package com.vime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import com.vime.ime.data.KeyHints
import com.vime.ime.engine.KeyHoldPolicy
import com.vime.ime.engine.BackspaceGesture
import com.vime.ime.engine.PointerKeys
import com.vime.ime.engine.OrderedReleases
import com.vime.ime.engine.TouchGeometry
import com.vime.ime.voice.VoiceProtocol

/**
 * 自绘键盘视图。
 *
 * **关键决定：不继承 KeyboardView。**
 * 真机上 `KeyboardView` 在 inflate 阶段直接抛 InflateException（API 34 已 deprecated，
 * 部分 ROM 的构造实现不兼容）。它对本类唯一的用处只有两件事：
 *   ① 把 Keyboard 里的键位算出来（`keyboard.keys`，这部分与 KeyboardView 无关）
 *   ② 触摸命中 + 按下态
 * 后者自己写不到 60 行，换来的是彻底不受这个废弃类的历史包袱拖累。
 *
 * 几何与配色全部取自 HTML 原型（边打字边背单词-输入法原型.html）：
 *   .key{ background:key; border-radius:10px; font-size:20px; box-shadow:0 1px 0 rgba(0,0,0,.10) }
 *   .key.fn{ background:key-fn(#d3d6dc); font-size:13px }
 *   .key:active{ background:key-press(#c9cdd4); transform:scale(.96) }
 *   .kb-row{ gap:5px }  .kb{ gap:6px; padding:6px 3px 8px }
 */
class VimeKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var keyboard: Keyboard? = null
        set(value) {
            deletePointer = -1; backspaceGesture.cancel()
            cancelVoiceHold()
            cancelRepeat()
            pointers.values().forEach { it.pressed = false }
            pointers.clear()
            releases.clear()
            downKey?.pressed = false
            downKey = null
            heldText = null
            repeated = false
            field = value
            touchGeometrySize = ""
            contentHeight = (value?.keys?.maxOfOrNull { it.y + it.height } ?: 1).coerceAtLeast(1)
            requestLayout()
            invalidate()
        }

    /** 0 = 无，1 = 下一个字母大写，2 = 全大写锁定 */
    var shiftState: Int = 0
        set(v) {
            field = v
            invalidate()
        }

    /** true = 中文态。中英键要显示「目标态」：中文态显示「英」，英文态显示「中」 */
    var chineseMode: Boolean = true
        set(v) {
            field = v
            invalidate()
        }

    /** 换行键文案，由 service 按 imeOptions 算出后写进来 */
    var actionLabel: String = "换行"
        set(v) {
            field = v
            invalidate()
        }
    var keyHaptics: Boolean = true
    var hapticLevel: Int = 2

    // ── 与 KeyboardView 对齐的接口，让 Service 代码不用改 ──

    private var listener: KeyboardView.OnKeyboardActionListener? = null
    private var voiceStart: (() -> Unit)? = null
    private var voiceEnd: (() -> Unit)? = null
    private var voiceCancel: (() -> Unit)? = null
    private var voiceHolding = false
    fun setVoiceGestureListener(start: () -> Unit, end: () -> Unit, cancel: () -> Unit) {
        voiceStart = start; voiceEnd = end; voiceCancel = cancel
    }
    private fun cancelVoiceHold() { if (voiceHolding) { voiceHolding = false; voiceCancel?.invoke() } }

    fun setOnKeyboardActionListener(l: KeyboardView.OnKeyboardActionListener?) {
        listener = l
    }

    private var previewEnabled = true
    /** Draw within this View, never a PopupWindow/token-dependent preview. */
    fun setPreviewEnabled(enabled: Boolean) { previewEnabled = enabled }

    init {
        // View 默认不可点击时收不到完整的 DOWN→UP 序列，触摸事件会在 DOWN 后被父容器吞掉
        isClickable = true
    }

    // ── 取自 values/colors.xml、values/dimens.xml，和原型同一套令牌 ──

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun scaleXFor(kb: Keyboard): Float =
        (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat() / kb.minWidth.coerceAtLeast(1)
    private fun scaleYFor(): Float =
        (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat() /
            contentHeight
    private var contentHeight = 1
    private var touchGeometrySize = ""
    private var touchKeys: List<Keyboard.Key> = emptyList()
    private var touchBoxes: List<TouchGeometry.Box> = emptyList()

    private val cKey = context.uiColor(R.color.key)
    private val cKeyPress = context.uiColor(R.color.key_press)
    private val cFn = context.uiColor(R.color.key_fn)
    private val cEdge = context.uiColor(R.color.key_edge)
    private val cInk = context.uiColor(R.color.ink)
    private val cInk3 = context.uiColor(R.color.ink_3)
    private val cAccent = context.uiColor(R.color.acc)
    private val cOnAccent = context.uiColor(R.color.on_acc)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(.65f)
        color = if (androidx.core.graphics.ColorUtils.calculateLuminance(cKey) < .25) 0x34FFFFFF else 0xE6FFFFFF.toInt()
    }
    private val metrics = Paint.FontMetrics()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val rect = RectF()

    private val insetH = resources.getDimension(R.dimen.key_gap_h) / 2f
    private val radius = resources.getDimension(R.dimen.key_radius)
    private val edgeHeight = dp(1f)

    // ── 触摸状态 ──
    private var downKey: Keyboard.Key? = null
    private val pointers = PointerKeys<Keyboard.Key>()
    private val releases = OrderedReleases<() -> Unit>()
    private var holdPointer = -1
    private var repeated = false
    private var heldText: String? = null
    private val backspaceGesture = BackspaceGesture(dp(36f), dp(48f))
    private var deletePointer = -1
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> (keyboard?.height ?: 0) + paddingTop + paddingBottom
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val kb = keyboard ?: return
        val padLeft = paddingLeft.toFloat()
        val padTop = paddingTop.toFloat()
        val sx = scaleXFor(kb)
        val sy = scaleYFor()

        for (key in kb.keys) {
            // 无 codes 或 code==0 的空键是第 2 行的左右缩进占位，不参与绘制
            val codes = key.codes
            if (codes == null || codes.isEmpty()) continue
            val code = codes.firstOrNull() ?: 0
            if (code == 0) continue
            val fn = isFnKey(code)
            val pressed = key.pressed

            var l = key.x * sx + padLeft + insetH
            var t = key.y * sy + padTop
            var r = (key.x + key.width) * sx + padLeft - insetH
            var b = (key.y + key.height) * sy + padTop
            if (r <= l || b <= t) continue

            // Press feedback changes the surface, keeping visual and touch geometry stable.

            // ② 键面
            fill.color = when {
                pressed -> cKeyPress
                code == Keyboard.KEYCODE_DONE && strongAction() -> cAccent
                fn -> cFn
                else -> cKey
            }
            val faceBottom = b
            rect.set(l, t, r, faceBottom)
            canvas.drawRoundRect(rect, radius, radius, fill)
            if (!pressed) {
                rect.inset(dp(.4f), dp(.4f))
                canvas.drawRoundRect(rect, radius, radius, rim)
            }

            KeyHints.secondary(code)?.takeIf { !pressed }?.let { hint ->
                text.textSize = dp(11f); text.color = cInk3; text.textAlign = Paint.Align.RIGHT
                canvas.drawText(hint, r - dp(4f), t + dp(12f), text)
                text.textAlign = Paint.Align.CENTER
            }

            // ③ 键面文字
            val label = labelFor(key, code)
            if (label.isEmpty()) continue
            val glyph = isGlyphKey(code)
            text.textSize = when {
                // 原型 .key.fn 是 13px；⇧/⌫ 这类字形在 Android 系统字体下 13dp 偏小，略放大
                glyph -> dp(16f)
                fn -> dp(13f)
                code == 32 -> dp(13f)
                else -> dp(20f)
            }
            text.color = when (code) {
                Keyboard.KEYCODE_DONE -> if (!pressed && strongAction()) cOnAccent else cInk
                32 -> cInk
                else -> cInk
            }
            val fm = metrics.also { text.getFontMetrics(it) }
            val baseline = (t + faceBottom) / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, (l + r) / 2f, baseline, text)
        }
        val previewCode = downKey?.codes?.firstOrNull()
        val showClear = deletePointer >= 0 && backspaceGesture.armed
        val preview = when {
            showClear -> "松手清空"
            deletePointer >= 0 && backspaceGesture.held(android.os.SystemClock.uptimeMillis()) -> "上划清空"
            // The voice strip owns live status. A timer bubble would outlive a failed service.
            repeated && previewCode == 32 -> null
            !previewEnabled -> null
            heldText != null -> "松手输入 ${heldText}"
            previewCode != null && previewCode in 97..122 -> previewCode.toChar().uppercaseChar().toString()
            else -> null
        }
        if (preview != null && downKey != null) {
            val key = downKey!!
            val bubbleWidth = dp(if (deletePointer >= 0 || heldText != null || previewCode == 32) 96f else 48f)
            val left = ((key.x + key.width / 2f) * sx - bubbleWidth / 2).coerceIn(0f, (width - bubbleWidth).coerceAtLeast(0f))
            val top = (key.y * sy - dp(40f)).coerceAtLeast(dp(3f))
            fill.color = cAccent
            rect.set(left, top, left + bubbleWidth, top + dp(34f))
            canvas.drawRoundRect(rect, dp(9f), dp(9f), fill)
            text.color = cOnAccent; text.textSize = dp(14f)
            val fm = text.fontMetrics
            canvas.drawText(preview, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, text)
        }
    }

    // ── 触摸 ──

    /** 命中测试用原始键矩形（不缩 insetH），手指更好按 */
    private fun keyAt(x: Float, y: Float, current: Keyboard.Key? = null): Keyboard.Key? {
        val kb = keyboard ?: return null
        // Never extend a key into the toolbar, outer padding or navigation area.
        if (x < paddingLeft || x >= width - paddingRight || y < paddingTop || y >= height - paddingBottom) return null
        val size = "$width:$height:$paddingLeft:$paddingTop:$paddingRight:$paddingBottom"
        if (touchGeometrySize != size) {
            val sx = scaleXFor(kb); val sy = scaleYFor()
            touchKeys = kb.keys.filter { (it.codes?.firstOrNull() ?: 0) != 0 }
            touchBoxes = touchKeys.map { TouchGeometry.Box(it.x * sx + paddingLeft, it.y * sy + paddingTop,
                (it.x + it.width) * sx + paddingLeft, (it.y + it.height) * sy + paddingTop) }
            touchGeometrySize = size
        }
        // A small hysteresis zone keeps a resting thumb on its original key. Larger slides still retarget.
        val index = TouchGeometry.hit(touchBoxes, x, y, touchKeys.indexOf(current), dp(7f), dp(7f))
        return touchKeys.getOrNull(index)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (keyboard == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) resetPointers()
                // A second finger disarms destructive holds; normal taps remain independent.
                if (deletePointer >= 0) {
                    pointers.get(deletePointer)?.pressed = false
                    pointers.move(deletePointer, null)
                    deletePointer = -1; backspaceGesture.cancel()
                }
                if (voiceHolding) { downKey?.pressed = false; pointers.move(holdPointer, null) }
                cancelRepeat()
                cancelVoiceHold()
                repeated = false
                heldText = null
                val i = event.actionIndex
                val k = keyAt(event.getX(i), event.getY(i)) ?: return true
                val id = event.getPointerId(i)
                pointers.down(id, k)
                releases.down(id)
                downKey = k
                holdPointer = id
                repeated = false
                heldText = null
                k.pressed = true
                invalidate()
                val code = k.codes?.firstOrNull() ?: 0
                listener?.onPress(code)
                // ImeRootLayout owns touch-down feedback for the whole keyboard.
                if (code == Keyboard.KEYCODE_DELETE && event.pointerCount == 1) {
                    deletePointer = id
                    backspaceGesture.begin(event.eventTime, event.getX(i), event.getY(i))
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (event.pointerCount == 1 && (code == Keyboard.KEYCODE_DELETE || code == 32 || KeyHints.secondary(code) != null)) scheduleRepeat(k)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val k = pointers.get(id) ?: continue
                    if (id == deletePointer) {
                        val wasArmed = backspaceGesture.armed
                        backspaceGesture.move(event.eventTime, event.getX(i), event.getY(i))
                        if (event.getX(i) !in 0f..width.toFloat() || event.getY(i) !in 0f..height.toFloat()) backspaceGesture.cancel()
                        if (!wasArmed && backspaceGesture.armed && keyHaptics) HapticFeedback.play(context, hapticLevel, true)
                        k.pressed = backspaceGesture.active
                        downKey = k.takeIf { backspaceGesture.active }
                        continue
                    }
                    val hit = keyAt(event.getX(i), event.getY(i), k)
                    if (hit !== k) {
                        val armed = id == holdPointer && (repeated || heldText != null)
                        pointers.move(id, if (armed) null else hit)
                        k.pressed = k in pointers.values()
                        hit?.pressed = hit in pointers.values()
                        if (id == holdPointer) {
                            cancelVoiceHold()
                            downKey = if (armed) null else hit
                            heldText = null
                            repeated = false
                            cancelRepeat()
                        }
                    }
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                var k = pointers.release(id)
                val isDelete = id == deletePointer
                val armed = id == holdPointer && (repeated || heldText != null)
                val hit = keyAt(event.getX(i), event.getY(i), k)
                val releasedKey = k
                if (!armed && !isDelete && k != null) k = hit
                releasedKey?.pressed = releasedKey in pointers.values()
                invalidate()
                if (id == holdPointer && voiceHolding) {
                    voiceHolding = false
                    if (k != null && hit === k) voiceEnd?.invoke() else voiceCancel?.invoke()
                }
                var tap: (() -> Unit)? = null
                if (k != null) {
                    val releasedCodes = k.codes
                    val releasedCode = k.codes?.firstOrNull() ?: 0
                    k.pressed = k in pointers.values()
                    invalidate()
                    val output = if (isDelete) backspaceGesture.release(event.eventTime, event.getX(i), event.getY(i), hit === k)
                        ?.let { KeyHoldPolicy.Output.Key(it) }
                        else KeyHoldPolicy.release(k.codes?.firstOrNull() ?: 0, armed, hit === k)
                    tap = {
                        when (output) {
                            is KeyHoldPolicy.Output.Key -> listener?.onKey(output.code, releasedCodes)
                            is KeyHoldPolicy.Output.Text -> listener?.onText(output.value)
                            null -> Unit
                        }
                        listener?.onRelease(releasedCode)
                    }
                    performClick()
                }
                if (id == holdPointer) {
                    cancelRepeat()
                    downKey = null
                    heldText = null
                    repeated = false
                }
                if (isDelete) { deletePointer = -1; backspaceGesture.cancel() }
                val layout = keyboard
                for (emit in releases.release(id, tap)) {
                    // A prior functional key may change layouts; discard old-layout pending output.
                    if (keyboard === layout) emit()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                resetPointers()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Timer starts voice or previews the delete/secondary gesture; never clears by itself. */
    private fun scheduleRepeat(key: Keyboard.Key) {
        cancelRepeat()
        val code = key.codes?.firstOrNull() ?: return
        val r = object : Runnable {
            override fun run() {
                if (downKey !== key || !key.pressed || pointers.size != 1) return
                if (code == Keyboard.KEYCODE_DELETE) backspaceGesture.refresh(android.os.SystemClock.uptimeMillis())
                if (code == 32) repeated = true
                else heldText = KeyHints.secondary(code)
                if (code == 32) { voiceHolding = true; voiceStart?.invoke() }
                if (keyHaptics) HapticFeedback.play(context, hapticLevel, true)
                invalidate()
            }
        }
        repeatRunnable = r
        repeatHandler.postDelayed(r, when (code) { 32 -> VoiceProtocol.HOLD_MS; Keyboard.KEYCODE_DELETE -> BackspaceGesture.HOLD_MS; else -> 500L })
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        resetPointers()
        super.onDetachedFromWindow()
    }

    private fun resetPointers() {
        deletePointer = -1; backspaceGesture.cancel()
        cancelVoiceHold()
        cancelRepeat()
        pointers.values().forEach { it.pressed = false }
        pointers.clear()
        releases.clear()
        downKey = null
        holdPointer = -1
        repeated = false
        heldText = null
        invalidate()
    }

    private fun strongAction() = actionLabel == "发送" || actionLabel == "搜索" || actionLabel == "前往"

    /**
     * 键面文案的唯一判定入口。
     *  - -1       ⇧/⇪：跟随三态
     *  - -4       换行/搜索/发送/前往/下一项/完成
     *  - -20001   中英键：显示目标态
     *  - 字母     英文态（或中文态打开 ⇧）时跟随大写
     */
    private fun labelFor(key: Keyboard.Key, code: Int): String = when (code) {
        Keyboard.KEYCODE_SHIFT -> if (shiftState == 2) "⇪" else "⇧"
        Keyboard.KEYCODE_DONE -> actionLabel
        CODE_LANG -> if (chineseMode) "英" else "中"
        CODE_ABC -> if (chineseMode) "全键" else "ABC"
        CODE_SYMBOLS -> "符"
        CODE_RETURN -> "返回"
        CODE_NUMPAD -> "123"
        44 -> if (chineseMode) "，" else ","
        46 -> if (chineseMode) "。" else "."
        else -> {
            val raw = key.label?.toString().orEmpty()
            if (code in 97..122 && (chineseMode || shiftState > 0)) raw.uppercase() else raw
        }
    }

    private fun isGlyphKey(code: Int): Boolean =
        code == Keyboard.KEYCODE_SHIFT || code == Keyboard.KEYCODE_DELETE

    private fun isFnKey(code: Int): Boolean = code == Keyboard.KEYCODE_SHIFT ||
        code == Keyboard.KEYCODE_DELETE ||
        code == Keyboard.KEYCODE_DONE ||
        code == Keyboard.KEYCODE_MODE_CHANGE ||
        code == CODE_LANG || code == CODE_ABC || code == CODE_SYMBOLS || code == CODE_RETURN || code == CODE_NUMPAD

    companion object {
        /** 中英切换（自定义码，避开 -1…-5 的系统语义） */
        const val CODE_LANG = -20001

        /** 回字母键盘 */
        const val CODE_ABC = -20002

        /** 切符号面板（数字面板里用） */
        const val CODE_SYMBOLS = -20003
        const val CODE_CLEAR = KeyHoldPolicy.CLEAR
        const val CODE_NUMPAD = -20005
        const val CODE_RETURN = -20006

    }
}

