package com.vime.ime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.ThemePref
import org.json.JSONObject

/** Local notice. Opening it makes no network requests and requests no permissions. */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this); super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport=true;addView(body) }); Ui.window(this)
        body.addView(Ui.back(this,"‹ 返回") { finish() }); Ui.space(body,20)
        val notice=JSONObject(assets.open("privacy-notice.json").bufferedReader().use { it.readText() })
        Ui.heading(this,body,"隐私与数据说明","预览版 ${BuildConfig.VERSION_NAME} · 更新于 ${notice.getString("updated")}")
        val sections=notice.getJSONArray("sections")
        repeat(sections.length()) { index ->
            val section=sections.getJSONObject(index); val card=Ui.card(body)
            card.addView(Ui.label(this,section.getString("title"),18f,true)); Ui.space(card,8)
            card.addView(Ui.label(this,section.getString("text"),15f,secondary=true))
        }
        body.addView(Ui.button(this,"开源与数据来源",action={startActivity(Intent(this,NoticesActivity::class.java))}).apply { id=R.id.privacyNotices })
        body.addView(Ui.button(this,"前往 GitHub 项目反馈",action={
            runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/jiHaolan/vime-typing-english"))) }
                .onFailure { Toast.makeText(this,"当前手机没有可用浏览器",Toast.LENGTH_SHORT).show() }
        }).apply { id=R.id.privacyFeedback })
    }
}
