package com.vime.ime

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.vime.ime.data.Cand
import com.vime.ime.data.LevelPref
import com.vime.ime.databinding.CandidateItemBinding
import com.vime.ime.engine.MemoryEngine
import com.vime.ime.engine.EnglishGloss

/** Compact, horizontally scrollable candidates: small neutral gloss above the main word. */
object CandidateView {
    private class Pool {
        val bindings = ArrayList<CandidateItemBinding>()
    }

    fun render(
        container: LinearLayout,
        cands: List<Cand>,
        onCommit: (Cand) -> Unit,
        onLong: (Cand) -> Unit,
        level: String = LevelPref.level
    ) {
        val pool = (container.tag as? Pool) ?: Pool().also { container.tag = it }
        val inflater = LayoutInflater.from(container.context)
        // Some service states clear the row. Reattach cached views rather than reinflating them.
        while (pool.bindings.size < cands.size) pool.bindings.add(CandidateItemBinding.inflate(inflater, container, false))
        pool.bindings.forEach { b -> if (b.root.parent == null) container.addView(b.root) }
        pool.bindings.forEachIndexed { index, b -> b.root.visibility = if (index < cands.size) View.VISIBLE else View.GONE }
        cands.forEachIndexed { index, c ->
            val b = pool.bindings[index]
            applyLabel(b.enTag, c.en, c.lvl, level, c.word)
            b.zhWord.text = c.word
            b.zhWord.setTextColor(container.context.uiColor(R.color.ink))
            // 首项：底部 2dp 蓝线 + 汉字加粗（原型 .cand.on）
            b.root.setBackgroundResource(if (index == 0) R.drawable.cand_first_bg else R.drawable.candidate_bg)
            b.zhWord.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            b.root.isFocusable = true
            b.root.contentDescription = "${c.word}${if (b.enTag.visibility == View.VISIBLE) "，${c.en}" else ""}，点按输入，长按查看词卡"
            b.root.setOnClickListener { onCommit(c) }
            b.root.setOnLongClickListener { onLong(c); true }
            // These pooled views can be inflated after the root theme pass has already run.
            // Apply the current custom palette to their text and first-candidate surface as well.
            com.vime.ime.data.CustomTheme.apply(b.root)
        }
    }

    /** Keyboard passes ALL; only an explicit mastered/muted state suppresses a valid gloss. */
    fun applyLabel(tag: TextView, en: String, lvl: String, level: String, word: String) {
        val visible = en.isNotBlank() && LevelPref.allows(lvl, level) && MemoryEngine.shouldAnnotate(word, lvl)
        tag.text = if (visible) EnglishGloss.label(en) else ""
        tag.setTextColor(tag.context.uiColor(R.color.ink_2))
        tag.alpha = 1f
        tag.typeface = Typeface.DEFAULT
        tag.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}

