package com.vime.ime
import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Hosts only the Android permission dialog, with no recognition page. */
class VoiceInputActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) { finish(); return }
        val pref = getSharedPreferences("voice_permission", MODE_PRIVATE)
        if (state == null && pref.getBoolean("asked", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this).setTitle("开启麦克风权限").setMessage("语音输入需要麦克风。请在系统应用权限中开启后，返回长按空格约半秒说话。")
                .setPositiveButton("前往设置") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); finish() }
                .setNegativeButton("取消") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        } else if (state == null) {
            pref.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 12)
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 12) {
            Toast.makeText(this, if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
                "已授权，返回输入框长按空格约半秒说话" else "未授权麦克风，可继续打字", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
