package com.vime.ime
import android.Manifest
import android.os.Bundle
import android.widget.Toast
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Hosts only the Android permission dialog, with no recognition page. */
class VoiceInputActivity : ComponentActivity() {
    private var phase = ""
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this,"已授权，返回输入框长按空格约半秒说话",Toast.LENGTH_LONG).show()
            finish()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            explainSettings()
        } else {
            Toast.makeText(this,"未授权麦克风，可继续打字",Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { finish(); return }
        phase = state?.getString("phase").orEmpty()
        if (phase == "requesting") return
        val pref = getSharedPreferences("voice_permission", MODE_PRIVATE)
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            phase = "rationale"
            AlertDialog.Builder(this).setTitle("语音输入需要麦克风")
                .setMessage("只在你主动发起语音时收音。离线模式在手机处理，其他模式由你选择的服务处理；拒绝后仍可正常打字。")
                .setPositiveButton("继续授权") { _, _ -> requestMicrophone() }
                .setNegativeButton("取消") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        } else if (pref.getBoolean("asked", false)) {
            explainSettings()
        } else {
            requestMicrophone()
        }
    }
    private fun requestMicrophone() {
        phase = "requesting"
        getSharedPreferences("voice_permission", MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        microphone.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun explainSettings() {
        phase = "settings"
        AlertDialog.Builder(this).setTitle("开启麦克风权限").setMessage("语音输入需要麦克风。请在系统应用权限中开启后，返回长按空格约半秒说话。")
            .setPositiveButton("前往设置") { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    .onFailure { Toast.makeText(this,"请在手机设置中开启本应用的麦克风权限",Toast.LENGTH_LONG).show() }
                finish()
            }.setNegativeButton("取消") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putString("phase",phase)
        super.onSaveInstanceState(state)
    }
}
