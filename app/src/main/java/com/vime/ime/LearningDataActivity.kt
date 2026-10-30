package com.vime.ime

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.vime.ime.data.LearningArchive
import com.vime.ime.data.ThemePref

/** Explicit SAF file actions. No storage permission, account, automatic upload or key export. */
class LearningDataActivity:AppCompatActivity() {
    private lateinit var model:LearningDataModel
    private lateinit var summary:TextView
    private lateinit var status:TextView
    private lateinit var preview:LinearLayout
    private val actions=ArrayList<Button>()
    private val create=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.exportTo(it) }
    private val open=registerForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFrom(it) }

    override fun onCreate(savedInstanceState:Bundle?) {
        ThemePref.apply(this); super.onCreate(savedInstanceState)
        model=ViewModelProvider(this)[LearningDataModel::class.java]
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            val gutter=resources.getDimensionPixelSize(R.dimen.page_gutter)
            setPadding(gutter,Ui.dp(this,16),gutter,Ui.dp(this,32)); setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport=true; addView(body) }); Ui.window(this)
        body.addView(Ui.back(this,"‹ 返回生词本") { finish() }); Ui.space(body,20)
        Ui.heading(this,body,"学习数据管理","把积累的生词和复习记录，带到下一台手机。")
        val overview=Ui.card(body)
        summary=Ui.label(this,"正在读取学习记录…",20f,true).also { it.id=R.id.learningDataSummary; overview.addView(it) }
        Ui.space(overview,8)
        overview.addView(Ui.label(this,"只包含主动收藏的词、复习进度和最近10000条复习记录。",13f,secondary=true))
        status=Ui.label(this,"",14f,secondary=true).also { it.id=R.id.learningDataStatus; it.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE; body.addView(it) }
        preview=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; body.addView(this) }
        fun action(parent:LinearLayout,label:String,id:Int,primary:Boolean=false,run:()->Unit)=Ui.button(this,label,primary,run).also { it.id=id; parent.addView(it); actions.add(it) }
        action(body,"备份学习数据",R.id.learningExport,true) { model.prepareExport() }
        action(body,"导入学习备份",R.id.learningImport) { open.launch(arrayOf("application/json","application/octet-stream","text/plain")) }
        Ui.space(body,16)
        body.addView(Ui.label(this,"备份不包含聊天内容、词频、剪贴板、录音、密钥或其他设置。文件可被打开查看，请选择自己保管的位置。",13f,secondary=true))
        Ui.space(body,28)
        Ui.section(this,body,"整理本机收藏")
        body.addView(Ui.label(this,"单个词可在生词本中移除。清空会一并移除全部收藏及对应复习记录，建议先备份。",14f,secondary=true))
        action(body,"清空收藏与复习记录",R.id.learningClear) {
            val data=model.state.value?.data ?: return@action
            AlertDialog.Builder(this).setTitle("清空本机学习记录？")
                .setMessage("将移除 ${data.entries.size} 个收藏和 ${data.history.size} 条复习记录。此操作不能撤销，可通过之前导出的备份恢复。")
                .setNegativeButton("保留",null).setPositiveButton("清空") { _,_ -> model.clear() }.show()
        }
        model.state.observe(this) { state ->
            summary.text=state.data?.let { "${it.entries.size} 个收藏 · ${it.history.size} 条复习记录" } ?: "正在读取学习记录…"
            status.text=state.message
            status.visibility=if(state.message.isBlank()) View.GONE else View.VISIBLE
            actions.forEach { it.isEnabled=!state.busy }
            findViewById<Button>(R.id.learningClear).isEnabled=!state.busy && state.data?.entries?.isNotEmpty()==true
            preview.removeAllViews()
            state.staged?.let { backup ->
                val card=Ui.card(preview)
                card.addView(Ui.label(this,"待导入的学习记录",20f,true))
                Ui.space(card,8)
                card.addView(Ui.label(this,"${backup.data.entries.size} 个词 · ${backup.data.history.size} 条复习记录",16f))
                card.addView(Ui.label(this,"备份时间：${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm",backup.createdAt)}",13f,secondary=true))
                backup.data.entries.take(3).forEach { card.addView(Ui.label(this,"${it.word} · ${it.english.take(60)}",14f)) }
                Ui.space(card,12)
                card.addView(Ui.label(this,"合并时保留本机同名词的释义和进度，只补入缺少的词及对应复习记录。",13f,secondary=true))
                card.addView(Ui.button(this,"合并导入",true) { model.restore(LearningArchive.Restore.MERGE) }.apply { id=R.id.learningMerge; isEnabled=!state.busy })
                card.addView(Ui.button(this,"替换本机学习记录") {
                    AlertDialog.Builder(this).setTitle("用备份替换本机学习记录？")
                        .setMessage("当前收藏和复习记录将整体替换为这份备份。请先保存当前备份，再继续替换。")
                        .setNegativeButton("保留本机",null).setPositiveButton("替换") { _,_ -> model.restore(LearningArchive.Restore.REPLACE) }.show()
                }.apply { id=R.id.learningReplace; isEnabled=!state.busy })
                card.addView(Ui.button(this,"放弃导入") { model.discard() }.apply { id=R.id.learningDiscard; isEnabled=!state.busy })
            }
            com.vime.ime.data.CustomTheme.apply(body)
            if(model.claimExportPicker()) create.launch("Vime-learning-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())}.json")
        }
    }
    override fun onResume() { super.onResume(); if(::model.isInitialized) model.refresh() }
}
