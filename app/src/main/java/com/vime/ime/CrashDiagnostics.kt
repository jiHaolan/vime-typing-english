package com.vime.ime

import android.content.Context
import com.vime.ime.engine.SafeCrashReport
import java.io.File

/** Bounded, local, opt-in copying. Legacy reports with exception text are never displayed. */
object CrashDiagnostics {
    const val FILE = "last_crash_safe.txt"
    private const val LEGACY = "last_crash.txt"
    private const val MAX_BYTES = 16384
    fun purgeLegacy(context: Context): Boolean = File(context.filesDir, LEGACY).let { !it.exists() || it.delete() }
    fun write(context: Context, report: String) {
        require(report.startsWith(SafeCrashReport.MAGIC) && report.toByteArray().size <= MAX_BYTES)
        File(context.filesDir, FILE).writeText(report)
    }
    fun consume(context: Context): String? {
        val file=File(context.filesDir,FILE)
        if(!file.isFile) return null
        val text=if(file.length()<=MAX_BYTES) runCatching { file.readText() }.getOrNull() else null
        file.delete()
        return text?.takeIf { it.startsWith(SafeCrashReport.MAGIC) }
    }
    fun clear(context: Context) {
        for(name in listOf(FILE,LEGACY)) { val file=File(context.filesDir,name);check(!file.exists() || file.delete()) }
    }
}
