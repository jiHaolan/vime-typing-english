package com.vime.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * 记忆引擎（端口自 Web 原型的 [MEMORY] 层）。
 *
 * 掌握度 0–100 = 先验基线 + 曝光增量 + 主动回忆增量：
 *  - 先验基线由词频等级给出（CET4 55 / CET6 35 / TOEFL 20 / 未收录 35）
 *  - 曝光（上屏）+0.7/次，累计最多 +10 点 —— 打字只能把你带到「眼熟」，别假装背会了
 *  - 主动回忆 记得 +6（累计封顶 +24），忘了 −13 并打回 Box1
 *
 * 相对原骨架修了两个静默 bug：
 *  ① `(fam + 0.7).toInt()` —— fam 是 Int，0.7 直接被截断，曝光永远是 +0，
 *     也就是说打一万次字标签也不会变淡，产品核心反馈完全失效。改为 Float 累加器。
 *  ② 每次上屏都把全部词状态序列化写 SharedPreferences，主线程直接卡顿。
 *     改为 350ms 合并 + 子线程快照落盘。
 */
object MemoryEngine {
    private val INIT_BASE = mapOf("CET4" to 55, "CET6" to 35, "TOEFL" to 20, "" to 35)
    private const val EXP_STEP = 0.7f
    private const val EXP_CAP = 10f
    private const val RECALL_GAIN = 6f
    private const val RECALL_CAP = 24f
    private const val RECALL_PENALTY = -13f

    /** 档位阈值，与原型 CONFIG.THRESH 一致 */
    private val THRESH = intArrayOf(20, 45, 70, 85)

    data class WordState(
        var box: Int = 3,
        var base: Int = 35,
        /** 曝光累计增量，封顶 EXP_CAP */
        var exp: Float = 0f,
        /** 主动回忆累计增量 */
        var recall: Float = 0f,
        var mutedWeek: String? = null,
        var mastered: Boolean = false
    )

    private lateinit var prefs: SharedPreferences
    private val states = HashMap<String, WordState>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vime-memory-io").apply { isDaemon = true }
    }

    private val saveTask = Runnable { flush() }
    private var dirty = false

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        prefs = context.getSharedPreferences("vime_memory", Context.MODE_PRIVATE)
        try {
            val root = JSONObject(prefs.getString("states", "{}") ?: "{}")
            val iter = root.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                val o = root.getJSONObject(k)
                states[k] = WordState(
                    box = o.optInt("box", 3),
                    base = o.optInt("b", o.optInt("fam", -1)),
                    exp = o.optDouble("exp", 0.0).toFloat(),
                    recall = o.optDouble("rec", 0.0).toFloat(),
                    mutedWeek = o.optString("mw").takeIf { it.isNotEmpty() },
                    mastered = o.optBoolean("m", false)
                )
            }
        } catch (_: Throwable) {
            // 存档损坏就当新装，不要因为记忆文件把输入法整体拖崩
            states.clear()
        }
        initialized = true
    }

    private fun weekKey(): String {
        val c = Calendar.getInstance()
        return "${c.get(Calendar.YEAR)}-W${c.get(Calendar.WEEK_OF_YEAR)}"
    }

    /** 只读探测：不创建状态，避免「只是看了一眼候选」就污染存档 */
    private fun peek(word: String, lvl: String): WordState =
        states[word] ?: WordState(base = INIT_BASE[lvl] ?: 35)

    private fun obtain(word: String, lvl: String): WordState =
        states.getOrPut(word) {
            WordState(base = INIT_BASE[lvl] ?: 35)
        }

    fun familiarity(word: String, lvl: String): Int {
        val s = peek(word, lvl)
        val base = if (s.base >= 0) s.base else INIT_BASE[lvl] ?: 35
        return (base + s.exp + s.recall).roundToInt().coerceIn(0, 100)
    }

    /** 已标记掌握、或本周被隐藏的词：不再显示英文标签 */
    @Suppress("UNUSED_PARAMETER")
    fun shouldAnnotate(word: String, lvl: String): Boolean {
        val s = states[word] ?: return true
        if (s.mastered) return false
        val muted = s.mutedWeek ?: return true
        return muted != weekKey()
    }

    /** 曝光（上屏）。累计最多 +10 点，且不再推进 —— 打字不产生「已掌握」 */
    fun onCommitted(word: String, lvl: String) {
        val s = obtain(word, lvl)
        if (s.mastered) return
        if (s.exp < EXP_CAP) {
            s.exp = (s.exp + EXP_STEP).coerceAtMost(EXP_CAP)
            scheduleSave()
        }
    }

    /** 主动回忆 */
    fun onRecall(word: String, lvl: String, correct: Boolean) {
        val s = obtain(word, lvl)
        if (correct) {
            s.recall = (s.recall + RECALL_GAIN).coerceAtMost(RECALL_CAP)
            s.box = (s.box + 1).coerceAtMost(5)
            if (familiarity(word, lvl) >= 92) s.mastered = true
        } else {
            s.recall = (s.recall + RECALL_PENALTY).coerceAtLeast(RECALL_PENALTY * 3)
            s.box = 1
            s.mastered = false
        }
        scheduleSave()
    }

    fun muteThisWeek(word: String, lvl: String) {
        obtain(word, lvl).mutedWeek = weekKey()
        scheduleSave()
    }

    fun unmute(word: String, lvl: String) {
        obtain(word, lvl).mutedWeek = null
        scheduleSave()
    }

    fun master(word: String, lvl: String) {
        val s = obtain(word, lvl)
        s.mastered = true
        s.box = 5
        scheduleSave()
    }

    // ────────────────────── 标签档位（对齐原型） ──────────────────────

    /**
     * 掌握度 → 档位。阈值与原型 CONFIG.THRESH 一致：
     *   0–20 陌生 / 20–45 模糊 / 45–70 熟悉 / 70–85 掌握 / 85+ 牢固
     *
     * 原型 CSS 只给 lv0 / lv1 / lv2 上色，lv3 起 `opacity:0`（透明占位）。
     * 这里刻意用四档离散而不是连续渐变 —— 原型的视觉语言是「三色三档 + 最后退场」。
     */
    fun levelOf(fam: Int): Int = when {
        fam < THRESH[0] -> 0
        fam < THRESH[1] -> 1
        fam < THRESH[2] -> 2
        fam < THRESH[3] -> 3
        else -> 4
    }

    /** 档位 → 标签颜色（原型 --lv0 青绿 / --lv1 琥珀 / --lv2 灰蓝） */
    fun labelColorOf(level: Int): Int = when (level) {
        0 -> 0xFF12A87C.toInt()
        1 -> 0xFFD98A26.toInt()
        else -> 0xFF7C8899.toInt()
    }

    // ────────────────────── 持久化 ──────────────────────

    private fun scheduleSave() {
        dirty = true
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, 350)
    }

    /** 立即落盘（Service.onDestroy 调用，避免最后 350ms 的操作丢失） */
    fun flush() {
        if (!dirty || !initialized) return
        dirty = false
        val snapshot = states.mapValues { (_, state) -> state.copy() }
        io.execute { write(snapshot) }
    }

    /** Clear on the main thread, then queue the durable deletion after older snapshots. */
    fun resetAnnotations(context: Context, done: (Result<Unit>) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val loaded = runCatching { init(app) }
            main.post {
                loaded.exceptionOrNull()?.let { done(Result.failure(it)); return@post }
                main.removeCallbacks(saveTask)
                dirty = false
                states.clear()
                io.execute {
                    val result = runCatching { check(prefs.edit().remove("states").commit()) { "提示状态清理未完成" } }
                    main.post { done(result) }
                }
            }
        }
    }

    private fun write(snapshot: Map<String, WordState>) {
        try {
            val root = JSONObject()
            snapshot.forEach { (k, s) ->
                root.put(k, JSONObject().apply {
                    put("box", s.box)
                    put("b", s.base)
                    put("exp", s.exp.toDouble())
                    put("rec", s.recall.toDouble())
                    put("mw", s.mutedWeek ?: "")
                    put("m", s.mastered)
                })
            }
            prefs.edit().putString("states", root.toString()).apply()
        } catch (_: Throwable) {
            // 写失败不影响输入
        }
    }
}
