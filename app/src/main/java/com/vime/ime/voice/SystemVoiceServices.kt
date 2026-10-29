package com.vime.ime.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.RecognitionService

data class VoiceService(val component: ComponentName, val label: String, val system: Boolean)
object SystemVoiceServices {
    fun list(c: Context): List<VoiceService> = runCatching {
        c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE),0).mapNotNull { r ->
            val s=r.serviceInfo ?: return@mapNotNull null
            if(!s.enabled || !s.exported) return@mapNotNull null
            VoiceService(ComponentName(s.packageName,s.name),r.loadLabel(c.packageManager).toString(),s.applicationInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0)
        }.distinctBy { it.component }.sortedBy { it.label }
    }.getOrDefault(emptyList())
    fun selected(c: Context): VoiceService? {
        val services=list(c)
        val preferred=VoiceSettings.provider(c)
        // An explicit selection that disappeared must be shown as unavailable, never silently rerouted.
        if(preferred.isNotEmpty()) return services.find { it.component.flattenToString()==preferred }
        val system=runCatching { Settings.Secure.getString(c.contentResolver,"voice_recognition_service") }.getOrNull()
        return services.find { it.component==ComponentName.unflattenFromString(system.orEmpty()) } ?: services.firstOrNull { it.system }
    }
    fun error(code: Int): String = when(code) {
        1,2,4 -> "手机语音服务连接失败，请检查网络或更换服务"
        3 -> "语音服务无法使用麦克风，请关闭其他录音后重试"
        5 -> "手机语音调用失败"
        6,7 -> "没有听清，请按住空格，看到正在聆听后再说话"
        8 -> "语音服务正忙，请稍后重试"
        9 -> "麦克风权限未开启，请在应用权限中开启"
        10 -> "语音服务请求过多，请稍后重试"
        11 -> "手机语音服务已断开，请重试"
        12 -> "当前语音服务不支持所选语言，请切换语言或服务"
        13 -> "所选语言尚未准备好，请在手机语音服务中下载语言包"
        else -> "手机语音服务暂不可用（$code），请在语音设置中检查"
    }
}
