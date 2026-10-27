package com.vime.ime.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 词库分级开关（T05）。
 *
 * 四档：CET4 / CET6 / TOEFL / 全部。默认「全部」——升级不改变老用户看到的界面，
 * 用户自己切到 CET4 才进入「只背四级」模式。
 *
 * 为什么是单例 + 监听者而不是到处读 SharedPreferences：
 *  - 键盘（VimeImeService）与设置页（MainActivity）在同一个进程，但分属两个窗口。
 *    切换必须**实时**反映到已经显示着的候选栏上，靠「下次进键盘再读」是不合格的。
 *  - 监听者只做一件事：`renderCandidates()`，不做任何数据重建（重建由 DictRepository 负责）。
 *
 * 落盘用 MODE_PRIVATE 的 `vime_prefs`，与掌握度存档 `vime_memory` 分开，
 * 这样用户「清空学习记录」时不会顺手把分级偏好也清掉。
 */
object LevelPref {

    const val CET4 = "CET4"
    const val CET6 = "CET6"
    const val TOEFL = "TOEFL"
    const val ALL = "全部"

    /** 循环切换顺序：难度递增，最后是「全部」 */
    val LEVELS: List<String> = listOf(CET4, CET6, TOEFL, ALL)

    private const val PREFS = "vime_prefs"
    private const val KEY = "dict_level"
    private const val DEFAULT = ALL

    @Volatile
    var level: String = DEFAULT
        private set

    private var prefs: SharedPreferences? = null
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 存档里若是脏值（改过包、手改 xml），回退默认档而不是把界面搞成空的
        level = p.getString(KEY, DEFAULT)?.takeIf { it in LEVELS } ?: DEFAULT
        prefs = p
    }

    /** 设置档位；值非法时按「全部」处理。相同值直接返回，不触发重渲染。 */
    fun set(next: String) {
        val v = if (next in LEVELS) next else ALL
        if (v == level) return
        level = v
        prefs?.edit()?.putString(KEY, v)?.apply()
        for (l in listeners) runCatching { l(v) }
    }

    /** 下一个档位（键盘上的圆角小标签点击时用） */
    fun nextLevel(cur: String = level): String {
        val i = LEVELS.indexOf(cur)
        return LEVELS[if (i < 0) 0 else (i + 1) % LEVELS.size]
    }

    fun addListener(l: (String) -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** 候选栏上显示的短标签：CET4 / CET6 / TOEFL / 全部 */
    fun chipText(cur: String = level): String = if (cur == ALL) ALL else cur

    /**
     * 该词的英文是否属于当前档位。
     * 「全部」恒为 true；其余档位要求词的等级**精确等于**所选等级。
     * 未收录等级（"")一律视为不在档内 —— 只背四级时，没分级依据的词不该冒充四级词。
     */
    fun allows(wordLvl: String, cur: String = level): Boolean =
        cur == ALL || (wordLvl.isNotEmpty() && wordLvl == cur)
}
