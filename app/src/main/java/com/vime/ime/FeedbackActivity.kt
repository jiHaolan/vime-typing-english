package com.vime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.ThemePref

/** Local help and explicitly initiated feedback. No report or input text is uploaded. */
class FeedbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePref.apply(this)
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gutter = resources.getDimensionPixelSize(R.dimen.page_gutter)
            setPadding(gutter, Ui.dp(this, 16), gutter, Ui.dp(this, 32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        Ui.window(this)
        body.addView(Ui.back(this, "‹ 返回首页") { finish() })
        Ui.space(body, 20)
        Ui.heading(this, body, "帮助与反馈", "把遇到的问题告诉我们。")

        val feedback = Ui.card(body)
        feedback.addView(Ui.label(this, "提交问题", 18f, true))
        Ui.space(feedback, 8)
        feedback.addView(Ui.label(this,
            "说明出现问题的位置、操作步骤、预期结果和实际表现。提交页面由浏览器打开，需要登录 GitHub。", 15f, secondary = true))
        feedback.addView(Ui.button(this, "前往 GitHub 提交问题", true) { openFeedback() }.apply { id = R.id.feedbackIssue })
        feedback.addView(Ui.button(this, "复制设备与版本信息") {
            copy("键忆问题信息", deviceInfo())
        }.apply { id = R.id.feedbackCopyDeviceInfo })
        feedback.addView(Ui.button(this, "复制反馈链接") {
            copy("键忆反馈链接", FEEDBACK_URL)
        }.apply { id = R.id.feedbackCopyLink })
        Ui.space(feedback, 8)
        feedback.addView(Ui.label(this,
            "复制信息仅含应用版本、手机型号和 Android 版本，不读取输入正文。截图前请遮住私人聊天和账号。", 13f, secondary = true))

        val usage = Ui.card(body)
        usage.addView(Ui.label(this, "使用说明", 18f, true))
        Ui.space(usage, 8)
        usage.addView(Ui.label(this,
            "中文：输入拼音后点选候选，长按候选查看英文词卡。\n英文：切换到英文后，字母直接上屏。\n语音：长按空格约半秒开始，松手结束。\n清空：按住删除键，再向上滑动。", 15f, secondary = true))
        usage.addView(Ui.button(this, "语音设置与故障信息") {
            startActivity(Intent(this, VoiceSettingsActivity::class.java))
        }.apply { id = R.id.feedbackVoiceHelp })
        usage.addView(Ui.button(this, "隐私与数据说明") {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }.apply { id = R.id.feedbackPrivacy })
        com.vime.ime.data.CustomTheme.apply(body)
    }

    private fun deviceInfo() = """
        键忆 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        手机：${Build.MANUFACTURER} ${Build.MODEL}
        Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}

        出现问题的位置：
        操作步骤：
        预期结果：
        实际表现：
    """.trimIndent()

    private fun copy(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "已复制，可粘贴到反馈中", Toast.LENGTH_SHORT).show()
    }

    private fun openFeedback() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FEEDBACK_URL))) }
            .onFailure { Toast.makeText(this, "当前手机没有可用浏览器，可先复制反馈链接", Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val FEEDBACK_URL = "https://github.com/jiHaolan/vime-typing-english/issues/new/choose"
    }
}
