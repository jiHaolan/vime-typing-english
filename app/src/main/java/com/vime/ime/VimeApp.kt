package com.vime.ime

import android.app.Application
import android.util.Log
import java.io.File

/**
 * 崩溃自捕获：把未捕获异常写到 filesDir/last_crash.txt。
 * 下次启动由 MainActivity 弹窗展示，真机上「装上打不开」这类问题
 * 不用连 adb 也能看到原因。
 */
class VimeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        com.vime.ime.data.PersonalLexicon.init(this)
        Thread { com.vime.ime.engine.RimeEngine.initialize(this) }.apply { name = "vime-rime-init" }.start()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    buildString {
                        appendLine("线程：${t.name}")
                        appendLine("异常：${e.javaClass.name}: ${e.message}")
                        appendLine()
                        append(Log.getStackTraceString(e))
                    }
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
