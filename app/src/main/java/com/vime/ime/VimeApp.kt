package com.vime.ime

import android.app.Application
import android.os.Looper
import com.vime.ime.data.InputLearningPrefs
import com.vime.ime.engine.SafeCrashReport

/**
 * Local crash diagnostics contain code locations and types, never exception payload text.
 */
class VimeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { CrashDiagnostics.purgeLegacy(this) }
        InputLearningPrefs.init(this)
        com.vime.ime.data.PersonalLexicon.init(this)
        Thread { com.vime.ime.engine.RimeEngine.initialize(this) }.apply { name = "vime-rime-init" }.start()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                CrashDiagnostics.write(this,SafeCrashReport.failure(SafeCrashReport.Stage.APP,e,t===Looper.getMainLooper().thread))
            }
            prev?.uncaughtException(t, e)
        }
    }

    companion object {
        const val CRASH_FILE = CrashDiagnostics.FILE
    }
}
