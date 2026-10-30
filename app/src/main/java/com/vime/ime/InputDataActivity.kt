package com.vime.ime

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.vime.ime.data.ThemePref

class InputDataActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this); super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[InputDataModel::class.java]
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) }); Ui.window(this)
        body.addView(Ui.back(this,"‹ 返回") { finish() }); Ui.space(body,20)
        Ui.heading(this,body,"输入数据","管理这台手机上的输入习惯、短语与临时记录。")
        val status = Ui.label(this,"",14f,secondary=true).also {
            it.id=R.id.inputDataStatus; it.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE; body.addView(it)
        }
        val actions = ArrayList<Button>()
        fun clear(title:String,detail:String,button:String,id:Int,confirmation:String,action:()->Unit):LinearLayout {
            val card = Ui.card(body)
            card.addView(Ui.label(this,title,18f,true)); Ui.space(card,8)
            card.addView(Ui.label(this,detail,14f,secondary=true))
            card.addView(Ui.button(this,button,action={
                if(model.state.value?.busy != true) AlertDialog.Builder(this).setTitle("$button？")
                    .setMessage(confirmation).setNegativeButton("保留",null)
                    .setPositiveButton(if(button.startsWith("重置")) "确认重置" else "确认清除") { _,_ -> action() }.show()
            }).also { it.id=id; actions.add(it) })
            return card
        }
        val learning=Ui.card(body)
        learning.addView(Ui.label(this,"收藏与复习",18f,true)); Ui.space(learning,8)
        learning.addView(Ui.label(this,"备份、导入或清空已经收藏的词和复习记录。",14f,secondary=true))
        learning.addView(Ui.button(this,"管理学习数据",action={startActivity(Intent(this,LearningDataActivity::class.java))}).also(actions::add))
        clear("选词习惯","选词后在本机学习用词习惯，影响候选顺序。","重置选词习惯",R.id.inputDataHabits,
            "将清除本机学到的用词及选词词频。收藏、复习记录、自定义短语和键盘设置会保留；之后选词会重新学习。", model::resetHabits)
        clear("英文提示状态","包括已掌握标记、本周隐藏和打字曝光积累。","重置英文提示状态",R.id.inputDataAnnotations,
            "英文标签将重新按初始状态显示。收藏与复习进度会保留。此操作不能撤销。", model::resetAnnotations)
        clear("自定义短语","删除前可先导出短语文件，之后可重新导入。","清空自定义短语",R.id.inputDataPhrases,
            "将删除全部自定义短语。此操作不能撤销，可用之前导出的短语文件恢复。", model::clearPhrases).also { card ->
            card.addView(Ui.button(this,"管理或导出自定义短语",action={startActivity(Intent(this,InputSettingsActivity::class.java))}).also(actions::add),card.childCount-1)
        }
        clear("临时剪贴板历史","仅保存你在键盘中主动留存的文字，最多10条，有效期10分钟。","清空临时剪贴板历史",R.id.inputDataClipboard,
            "将清空本应用留存的临时历史。系统剪贴板内容会保留。", model::clearClipboard)
        clear("符号与表情","保存最近选择过的预置符号和表情。","清除最近使用记录",R.id.inputDataRecents,
            "将清除最近使用的符号和表情，之后选择的条目会重新记录。", model::clearRecents)
        clear("语音故障记录","仅保存最近一次手机语音服务状态，不含录音或识别文字。","清除语音故障记录",R.id.inputDataVoice,
            "将清除最近一次语音服务状态。语音模式、服务地址与密钥会保留，新的尝试会生成新记录。", model::clearVoiceStatus)
        body.addView(Ui.button(this,"管理语音服务与密钥",action={startActivity(Intent(this,VoiceSettingsActivity::class.java))}).also(actions::add))
        var wasBusy=model.state.value?.busy==true
        model.state.observe(this) { state ->
            if(wasBusy && !state.busy && state.message.isNotBlank()) Toast.makeText(this,state.message,Toast.LENGTH_SHORT).show()
            wasBusy=state.busy
            status.text=state.message; status.visibility=if(state.message.isBlank()) View.GONE else View.VISIBLE
            actions.forEach { it.isEnabled = !state.busy }
        }
    }
}
