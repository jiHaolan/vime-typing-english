package com.vime.ime

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.*
import com.vime.ime.engine.RimeEngine
import java.util.concurrent.Executors

/** User-owned phrase and palette files use Android's document picker, no storage permission. */
class InputSettingsActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private val io = Executors.newSingleThreadExecutor()
    private var themeFields = emptyList<EditText>()
    override fun onCreate(state: Bundle?) {
        ThemePref.apply(this); super.onCreate(state)
        val scroll = ScrollView(this).apply { setBackgroundColor(uiColor(R.color.panel_bg)) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(20),dp(20),dp(32)) }
        scroll.addView(column); setContentView(scroll); Ui.window(this)
        render()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 15f): TextView = Ui.label(this,text,size).also { column.addView(it); Ui.space(column,8) }
    private fun button(text: String, action: () -> Unit): Button = Ui.button(this,text,action=action).also { column.addView(it) }
    private fun field(hint: String,value: String): EditText = EditText(this).apply {
        this.hint = hint; setText(value); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine(); Ui.input(this)
    }
    private fun render() {
        column.removeAllViews()
        Ui.back(this,"返回") { finish() }.also(column::addView)
        label("输入与个性化",24f)
        status = label(RimeEngine.label()).apply { id=R.id.inputEngineStatus }
        label("整句与九键由智能拼音处理；词典初次部署在后台完成。普通输入均离线。选择候选会学习用词习惯，密码和禁止学习的输入框不记录。")
        button("刷新引擎状态") { status.text=RimeEngine.label() }
        button("重新准备智能拼音") {
            status.text="正在准备智能拼音…"
            io.submit {
                RimeEngine.initialize(applicationContext)
                runOnUiThread { if(!isFinishing && !isDestroyed) status.text=RimeEngine.label() }
            }
        }
        button("管理本机输入数据") { startActivity(Intent(this,InputDataActivity::class.java)) }
        Ui.section(this,column,"自定义短语")
        label("触发码用英文字母，例如 hhhh；优先级越大越靠前。最多1000条，导入会按触发码和文字合并。")
        button("添加短语") { editPhrase(null) }.id=R.id.addPhrase
        button("导入短语文件") { document(false,1,"vime-phrases.json") }
        button("导出短语文件") { document(true,2,"vime-phrases.json") }
        val rules=PersonalLexicon.list()
        label("已保存 ${rules.size} 条短语")
        for ((index,rule) in rules.withIndex()) {
            button("${if (rule.enabled) "✓" else "停用"} ${rule.trigger} → ${rule.text.take(36)} · ${rule.priority}") {
                AlertDialog.Builder(this).setTitle(rule.trigger).setItems(arrayOf("编辑","${if (rule.enabled) "停用" else "启用"}","删除")) { _,which ->
                    when(which) {
                        0 -> editPhrase(index)
                        1 -> { PersonalLexicon.save(rules.mapIndexed { i,r -> if(i==index)r.copy(enabled=!r.enabled) else r });render() }
                        2 -> { PersonalLexicon.save(rules.filterIndexed { i,_ -> i!=index });render() }
                    }
                }.show()
            }
        }
        Ui.section(this,column,"高级触感")
        label("四档触感继续可用；下列设置可分别调整普通与长按的时长和力度。关闭系统触感或应用触感时均不振动。马达不支持力度控制时仅使用时长。")
        val enabled=Switch(this).apply { text="使用自定义触感";setTextColor(uiColor(R.color.ink));isChecked=KeyboardPrefs.advanced(this@InputSettingsActivity);column.addView(this) }
        fun slider(title: String,max: Int,value: Int): SeekBar {
            val caption=label("$title：$value")
            return SeekBar(this).apply { this.max=max-1;progress=value-1;column.addView(this)
                setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean){caption.text="$title：${v+1}"}
                    override fun onStartTrackingTouch(s:SeekBar?){}
                    override fun onStopTrackingTouch(s:SeekBar?){}
                })
            }
        }
        val ms=slider("普通按键时长（毫秒）",100,KeyboardPrefs.duration(this,false))
        val amp=slider("普通按键力度",255,KeyboardPrefs.amplitude(this,false))
        val longMs=slider("长按时长（毫秒）",100,KeyboardPrefs.duration(this,true))
        val longAmp=slider("长按力度",255,KeyboardPrefs.amplitude(this,true))
        button("保存并试一下触感") {
            KeyboardPrefs.advanced(this,enabled.isChecked,ms.progress+1,amp.progress+1,longMs.progress+1,longAmp.progress+1)
            HapticFeedback.play(this,KeyboardPrefs.haptics(this));status.text="触感已保存，仍遵循系统关闭设置"
        }
        Ui.section(this,column,"自定义配色")
        label("输入 #RRGGBB 色号。配色使用固定颜色；文字与背景需达到可读对比。可从文件导入或保存后导出。")
        val colors=CustomTheme.read(this)
        val values=listOf("名称" to colors.name,"键面" to colors.key,"键盘背景" to colors.keyboard,"文字" to colors.ink,"强调色" to colors.accent,"面板背景" to colors.panel)
        themeFields=values.mapIndexed { index, (hint,value) -> label(hint);field(hint,value).also { it.id=listOf(R.id.themeName,R.id.themeKey,R.id.themeKeyboard,R.id.themeInk,R.id.themeAccent,R.id.themePanel)[index];if(index>0)it.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;column.addView(it) } }
        button("应用并保存自定义配色") {
            runCatching { CustomTheme.save(this,editedColors()) }.onSuccess { recreate() }.onFailure { status.text=it.message;Toast.makeText(this,it.message,Toast.LENGTH_LONG).show() }
        }.apply { id=R.id.applyCustomTheme }
        button("导入配色文件") { document(false,3,"vime-theme.json") }
        button("导出已保存的配色") { document(true,4,"vime-theme.json") }
        button("使用默认皮肤") { ThemePref.setSkin(this,0);recreate() }
        Ui.section(this,column,"横屏与竖屏")
        label("键盘高度、宽度、左右位置和底部留白按横竖屏分别保存。在要调整的方向打开键盘→布局→键盘调节。首次使用横屏会沿用原有值。")
        Ui.section(this,column,"开源与词典")
        button("开源与数据来源") { startActivity(Intent(this,NoticesActivity::class.java)) }.id=R.id.inputNotices
    }
    private fun editedColors() = themeFields.map { it.text.toString().trim() }.let { CustomColors(it[0],it[1],it[2],it[3],it[4],it[5]) }.also(ThemeCodec::validate)
    private fun editPhrase(index: Int?) {
        val old=index?.let { PersonalLexicon.list()[it] } ?: PhraseRule("","",50)
        val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0) }
        val trigger=field("触发码（英文字母）",old.trigger).also { it.id=R.id.phraseTrigger;form.addView(it) }
        trigger.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val text=field("短语",old.text).also { it.id=R.id.phraseText;form.addView(it) }
        val order=field("优先级1—100",old.priority.toString()).also { it.inputType=InputType.TYPE_CLASS_NUMBER;form.addView(it) }
        val on=CheckBox(this).apply { this.text="启用";isChecked=old.enabled;form.addView(this) }
        val dialog=AlertDialog.Builder(this).setTitle(if(index==null)"添加短语" else "编辑短语").setView(form).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val item=PhraseRule(trigger.text.toString().trim().lowercase(),text.text.toString(),order.text.toString().toIntOrNull() ?: 0,on.isChecked)
                PhraseCodec.validate(item)
                val items=PersonalLexicon.list().toMutableList();if(index==null)items.add(item) else items[index]=item
                PersonalLexicon.save(items.distinctBy { it.trigger to it.text });dialog.dismiss();render()
            } catch(e:Exception) { trigger.error=e.message }
        } }
        dialog.show()
    }
    private fun document(export: Boolean,code: Int,name: String) {
        val intent=Intent(if(export)Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
        if(export)intent.putExtra(Intent.EXTRA_TITLE,name)
        runCatching { startActivityForResult(intent,code) }.onFailure { status.text="当前手机没有可用的文件选择器" }
    }
    @Deprecated("Android document picker callback")
    override fun onActivityResult(request: Int,result: Int,data: Intent?) {
        super.onActivityResult(request,result,data)
        val uri=data?.data ?: return;if(result!=RESULT_OK)return
        val phrases=PersonalLexicon.list();val colors=CustomTheme.read(this)
        io.submit {
            try {
                if(request==2||request==4) {
                    val bytes=(if(request==2)PhraseCodec.write(phrases) else ThemeCodec.write(colors)).toByteArray()
                    contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes) }
                } else {
                    val bytes=contentResolver.openInputStream(uri)!!.use { input ->
                        val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                        while(true) { val n=input.read(buf);if(n<0)break;require(out.size()+n<=1024*1024){"文件超过1MB"};out.write(buf,0,n) };out.toByteArray()
                    }
                    val value=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    if(request==1)PersonalLexicon.merge(PhraseCodec.read(value)) else CustomTheme.save(this,ThemeCodec.read(value))
                }
                runOnUiThread { if(!isFinishing && !isDestroyed) { if(request==3)recreate() else { render();status.text=if(request==2||request==4)"文件已导出" else "短语已合并导入" } } }
            } catch(e:Exception) { runOnUiThread { if(!isFinishing && !isDestroyed)status.text=e.message ?: "文件操作未完成" } }
        }
    }
    override fun onDestroy() { io.shutdown();super.onDestroy() }
}
