package com.vime.ime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.ThemePref
import com.vime.ime.voice.*

class VoiceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this);super.onCreate(savedInstanceState);Ui.window(this)
        val scroll=ScrollView(this).apply { setBackgroundColor(uiColor(R.color.panel_bg)) }
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(32)) }
        scroll.addView(column);setContentView(scroll)
        fun label(text: String,size: Float=15f)=Ui.label(this,text,size).also { column.addView(it);Ui.space(column,10) }
        fun button(text: String,action: ()->Unit)=Ui.button(this,text,action=action).also(column::addView)
        Ui.back(this,"返回") { finish() }.also(column::addView)
        label("语音输入",28f)
        label("长按空格2秒，看到「正在聆听」后说话，松手结束。滑出空格、点取消或切换输入框会放弃。手机语音服务可能自行提前结束收音。")
        fun choices(values:List<String>)=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,values) {
            private fun style(view:View)=view.apply { if(this is TextView){setTextColor(uiColor(R.color.ink));setBackgroundColor(uiColor(R.color.panel_bg));minHeight=dp(48)} }
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup)=style(super.getView(position,convertView,parent))
            override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup)=style(super.getDropDownView(position,convertView,parent))
        }
        val modeNames=listOf("手机语音 · 无需密钥","自定义联网服务 · 高级选项")
        val backend=Spinner(this).apply { id=R.id.voiceBackend;backgroundTintList=android.content.res.ColorStateList.valueOf(uiColor(R.color.ink));adapter=choices(modeNames);setSelection(if(VoiceSettings.mode(this@VoiceSettingsActivity)==VoiceSettings.CLOUD)1 else 0);column.addView(this) }
        val local=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;column.addView(this) }
        fun localLabel(text: String)=Ui.label(this,text,15f).also { local.addView(it);Ui.space(local,10) }
        localLabel("使用手机已安装的识别服务，无需注册或填写API。本应用不保存这一路径的录音；服务可能联网，数据处理和语言支持由所选服务提供。")
        val services=SystemVoiceServices.list(this)
        val saved=VoiceSettings.provider(this)
        val providers=mutableListOf("" to "跟随手机默认服务")
        if(saved.isNotEmpty() && services.none { it.component.flattenToString()==saved })providers.add(saved to "先前选择的服务已不可用")
        providers.addAll(services.map { it.component.flattenToString() to it.label })
        localLabel("识别服务")
        val provider=Spinner(this).apply { id=R.id.voiceProvider;backgroundTintList=android.content.res.ColorStateList.valueOf(uiColor(R.color.ink));adapter=choices(providers.map { it.second });setSelection(providers.indexOfFirst { it.first==saved }.coerceAtLeast(0));local.addView(this) }
        localLabel("识别语言")
        val languages=listOf("keyboard" to "跟随键盘中英切换","zh-CN" to "中文","en-US" to "English")
        val language=Spinner(this).apply { id=R.id.voiceLanguage;backgroundTintList=android.content.res.ColorStateList.valueOf(uiColor(R.color.ink));adapter=choices(languages.map { it.second });setSelection(languages.indexOfFirst { it.first==VoiceSettings.languageChoice(this@VoiceSettingsActivity) }.coerceAtLeast(0));local.addView(this) }
        val serviceStatus=localLabel("").apply { id=R.id.voiceServiceStatus }
        fun updateStatus() { serviceStatus.text=SystemVoiceServices.selected(this)?.let { "可用：${it.label}\n检测到 ${services.size} 个识别服务；返回输入框即可按住空格使用。" } ?: "没有可用的识别服务。请检查手机语音设置，或启用已安装的语音服务。仅有语音助手不一定能供其他应用调用。" }
        provider.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:AdapterView<*>?,v:View?,n:Int,id:Long){VoiceSettings.setProvider(this@VoiceSettingsActivity,providers[n].first);updateStatus()}
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
        language.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:AdapterView<*>?,v:View?,n:Int,id:Long){VoiceSettings.setLanguage(this@VoiceSettingsActivity,languages[n].first)}
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
        local.addView(Ui.button(this,"打开手机语音设置",action={openSettings(Settings.ACTION_VOICE_INPUT_SETTINGS)}))
        local.addView(Ui.button(this,"打开系统键盘设置",action={openSettings(Settings.ACTION_INPUT_METHOD_SETTINGS)}))
        localLabel("已启用的外部语音输入法可以从键盘的工具设置中切换；切换后使用对方的语音界面。")
        updateStatus()
        val advanced=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;column.addView(this) }
        fun advancedLabel(text:String)=Ui.label(this,text,15f).also(advanced::addView)
        advancedLabel("仅在主动选择此模式时上传录音至所填接口。需要自己的服务账号和密钥；费用与数据处理规则以服务方为准。")
        val config=VoiceSettings.read(this)
        val status=advancedLabel(if(config.key.isEmpty())"尚未配置联网服务" else "已保存联网服务密钥")
        advancedLabel("API密钥 · 本机加密保存")
        val key=EditText(this).apply { id=R.id.voiceApiKey;hint=if(config.key.isEmpty())"填写API Key" else "留空保留原密钥";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;setSingleLine();advanced.addView(this) }
        fun field(caption:String,value:String,id:Int):EditText { advancedLabel(caption);return EditText(this).apply { this.id=id;setText(value);inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine();advanced.addView(this) } }
        val endpoint=field("兼容转写接口 · HTTPS",config.endpoint,R.id.voiceEndpoint)
        val model=field("模型名称",config.model,R.id.voiceModel)
        advanced.addView(Ui.button(this,"保存联网服务",action={
            runCatching { VoiceSettings.save(this,VoiceConfig(endpoint.text.toString().trim(),model.text.toString().trim(),key.text.toString().trim().ifEmpty { VoiceSettings.read(this).key })) }
                .onSuccess { key.text.clear();status.text="设置已保存" }.onFailure { status.text=it.message ?: "保存失败" }
        }).apply { id=R.id.voiceSave })
        advanced.addView(Ui.button(this,"清除已保存的密钥",action={VoiceSettings.clear(this);key.text.clear();status.text="密钥已清除"}).apply { id=R.id.voiceClear })
        advanced.addView(Ui.button(this,"获取服务API密钥",action={runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://cloud.siliconflow.cn/account/ak"))) }}))
        fun showMode(index:Int){VoiceSettings.setMode(this,if(index==0)VoiceSettings.SYSTEM else VoiceSettings.CLOUD);local.visibility=if(index==0)View.VISIBLE else View.GONE;advanced.visibility=if(index==1)View.VISIBLE else View.GONE}
        backend.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p:AdapterView<*>?,v:View?,n:Int,id:Long){showMode(n)}
            override fun onNothingSelected(p:AdapterView<*>?){}
        }
        showMode(backend.selectedItemPosition)
    }
    private fun openSettings(action:String){runCatching { startActivity(Intent(action)) }.onFailure { Toast.makeText(this,"当前手机没有此设置入口，请从系统设置中打开",Toast.LENGTH_LONG).show() }}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
}
