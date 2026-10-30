package com.vime.ime.engine

/** Never serialize exception messages, file names, thread names, or suppressed payloads. */
object SafeCrashReport {
    const val MAGIC = "VIME-CRASH-2\n"
    const val MAX_CHARS = 8192
    enum class Stage { APP, KEYBOARD_VIEW, KEYBOARD_LAYOUT }
    private val symbol = Regex("[A-Za-z0-9_.$<>/\\-]{1,200}")
    private fun safe(value: String) = value.takeIf { symbol.matches(it) } ?: "omitted"
    fun failure(stage: Stage, error: Throwable, mainThread: Boolean): String {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return buildString {
            append(MAGIC); appendLine("阶段：${stage.name}"); appendLine(if(mainThread) "线程：界面线程" else "线程：后台线程")
            var current: Throwable? = error
            repeat(4) {
                val item = current ?: return@repeat
                if (!seen.add(item)) { current = null; return@repeat }
                appendLine("异常类型：${safe(item.javaClass.name)}")
                item.stackTrace.take(24).forEach { frame ->
                    appendLine("  ${safe(frame.className)}.${safe(frame.methodName)}:${frame.lineNumber.coerceIn(-2,1000000)}")
                }
                current = item.cause
            }
        }.take(MAX_CHARS)
    }
    fun layout(panel: String, keys: Int, expected: Int, height: Int) =
        MAGIC + "阶段：KEYBOARD_LAYOUT\n面板：${if(panel in setOf("QWERTY","NINE","SYMBOLS","NUMBER","NUMPAD")) panel else "unknown"}" +
            "\n按键：${keys.coerceIn(0,1000)}/${expected.coerceIn(0,1000)}\n高度：${height.coerceIn(0,10000)}"
}
