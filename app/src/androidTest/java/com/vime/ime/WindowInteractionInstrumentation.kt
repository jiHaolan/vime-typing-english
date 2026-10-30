package com.vime.ime

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Real windows, actual touch/key dispatch, no fake insets or mocked activities.
 * Uses a dedicated emulator and only temporary text in the app's own trial field.
 */
@android.annotation.TargetApi(30)
class WindowInteractionInstrumentation : Instrumentation() {
    private val cases = JSONArray()
    private val observations = JSONArray()
    private val diagnostics = JSONObject()
    private val touches = JSONArray()
    private var home: Activity? = null
    private var wordbook: Activity? = null
    private var editor: EditText? = null
    private var originalText = ""
    private val expected = 7

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun <T> main(work: () -> T): T {
        val task = FutureTask(Callable { work() })
        check(Handler(Looper.getMainLooper()).post(task))
        try { return task.get(8, TimeUnit.SECONDS) }
        finally { if (!task.isDone) task.cancel(false) }
    }

    private fun await(reason: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 12000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error(reason)
    }

    private fun test(name: String, block: () -> Unit): Boolean {
        val result = runCatching(block)
        cases.put(JSONObject().put("name", name).put("pass", result.isSuccess)
            .put("error", result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0, Bundle().apply { putString("stream", "${if (result.isSuccess) "PASS" else "FAIL"} $name\n") })
        return result.isSuccess
    }

    private data class Geometry(val full: Rect, val visible: Rect, val window: Rect,
                                val ime: Int, val topBar: Int, val focused: Boolean,
                                val windowFocused: Boolean, val imeVisible: Boolean,
                                val layoutPending: Boolean, val lines: Int) {
        fun json() = JSONObject().put("editor", full.toShortString()).put("visible", visible.toShortString())
            .put("window", window.toShortString()).put("ime_bottom", ime).put("top_bar", topBar)
            .put("editor_focused", focused).put("window_focused", windowFocused).put("ime_visible", imeVisible)
            .put("layout_pending", layoutPending).put("lines", lines)
        fun fullyVisible() = ime > 0 && imeVisible && focused && windowFocused && !layoutPending &&
            visible.contains(full) && full.top >= window.top + topBar && full.bottom <= window.bottom - ime
    }

    private fun geometry(): Geometry = main {
        val activity = checkNotNull(home)
        val view = checkNotNull(editor)
        val point = IntArray(2); view.getLocationOnScreen(point)
        val full = Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
        val visible = Rect()
        if (view.getLocalVisibleRect(visible)) visible.offset(point[0], point[1])
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
        Geometry(full, visible, Rect(activity.windowManager.currentWindowMetrics.bounds),
            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top,
            view.hasFocus(), activity.hasWindowFocus(), insets.isVisible(WindowInsetsCompat.Type.ime()),
            view.isLayoutRequested, view.lineCount)
    }

    private fun observe(label: String) { observations.put(geometry().json().put("label", label)) }

    private fun observeFullyVisible(label: String, reason: String, minimumLines: Int = 1) {
        var snapshot: Geometry? = null
        await(reason) {
            val current = geometry()
            (current.fullyVisible() && current.lines >= minimumLines).also { if (it) snapshot = current }
        }
        // Save the exact sample that passed, rather than a different animation frame.
        observations.put(checkNotNull(snapshot).json().put("label", label))
    }

    private fun shell(command: String): String {
        val automation = getUiAutomation(UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun captureFailureState() {
        for (service in listOf("input_method", "window")) {
            val state = runCatching { shell("dumpsys $service") }
            val selected = state.getOrNull()?.lineSequence()?.filter {
                listOf("mCurMethod", "mCurClient", "mCurFocusedWindow", "mInputShown", "mViewsCreated",
                    "mWindowVisible", "mInputViewStarted", "mShowInputRequested", "mShowImeWithHardKeyboard",
                    "mImeInputTarget", "mImeControlTarget", "mImeLayeringTarget", "mCurrentFocus",
                    "imeInputTarget", "imeControlTarget", "imeLayeringTarget").any(it::contains)
            }?.toList().orEmpty()
            diagnostics.put(service, JSONArray(selected))
            state.exceptionOrNull()?.let { diagnostics.put("${service}_error", it.toString()) }
        }
    }

    private fun tap(view: View) {
        main { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        waitForIdleSync()
        var lastRect: Rect? = null
        var stableSince = SystemClock.uptimeMillis()
        await("Touch target is clipped or not in the foreground") {
            main {
                val visible = Rect()
                val point = IntArray(2); view.getLocationOnScreen(point)
                val current = Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
                if (current != lastRect || view.isLayoutRequested) {
                    lastRect = current
                    stableSince = SystemClock.uptimeMillis()
                }
                view.hasWindowFocus() && view.getLocalVisibleRect(visible) && !view.isLayoutRequested &&
                    visible.height() >= view.height - 2 && visible.width() >= view.width - 2 &&
                    SystemClock.uptimeMillis() - stableSince >= 250
            }
        }
        val rect = main {
            val point = IntArray(2); view.getLocationOnScreen(point)
            Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
        }
        val at = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), 0)
        val up = MotionEvent.obtain(at, at + 40, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN; up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            sendPointerSync(down)
            touches.put(JSONObject().put("view_id", view.id).put("bounds", rect.toShortString()))
            SystemClock.sleep(40); sendPointerSync(up)
        } finally { down.recycle(); up.recycle() }
    }

    override fun onStart() {
        try {
            val launched = test("home-launches-and-has-foreground-focus") {
                check(Build.PRODUCT.contains("sdk")) { "Run window checks only on a dedicated emulator" }
                check(Build.VERSION.SDK_INT >= 30) { "This window-metrics suite targets API30+" }
                check(targetContext.applicationInfo.targetSdkVersion >= 36)
                home = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                editor = main { checkNotNull(home?.findViewById<EditText>(R.id.typingPreview)) }
                originalText = main { editor!!.text.toString() }
                await("Home never acquired window focus; check system dialogs and startup health") {
                    main { home!!.hasWindowFocus() && !home!!.isFinishing }
                }
            }
            if (launched) {
                val shown = test("vime-keyboard-opens-after-tapping-editor") {
                    // Starting instrumentation restarts the target package, which also hosts
                    // our IME. Select it after the new process/window is ready, not before
                    // the runner kills its service and leaves an obsolete framework binding.
                    diagnostics.put("ime_selection", shell("ime set com.vime.ime/.VimeImeService").trim())
                    val selected = Settings.Secure.getString(targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    check(selected?.startsWith("com.vime.ime/") == true) { "Select Vime as the emulator input method before this test" }
                    observe("before-editor-touch")
                    tap(editor!!)
                    try {
                        await("No visible IME after a real editor touch") { geometry().ime > 0 }
                    } catch (error: Throwable) {
                        captureFailureState()
                        throw error
                    } finally { observe("after-editor-touch") }
                    observe("keyboard-open")
                }
                if (shown) {
                    test("whole-focused-editor-stays-above-keyboard") {
                        observeFullyVisible("whole-editor", "Focused trial field is clipped or covered by the keyboard")
                    }
                    test("three-line-editor-remains-fully-visible") {
                        main { editor!!.setText("window test\nsecond line\nthird line"); editor!!.setSelection(editor!!.length()) }
                        observeFullyVisible("three-lines", "Three-line trial field is clipped or covered", minimumLines = 3)
                    }
                    test("back-hides-keyboard-and-keeps-home-open") {
                        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        await("Back did not hide the IME while retaining Home") {
                            geometry().ime == 0 && main { !home!!.isFinishing && home!!.hasWindowFocus() }
                        }
                        observe("after-keyboard-back")
                    }
                    val opened = test("wordbook-opens-from-home-action") {
                        val monitor = addMonitor(LearningActivity::class.java.name, null, false)
                        try {
                            tap(main { home!!.findViewById<View>(R.id.btnWordbook) })
                            wordbook = waitForMonitorWithTimeout(monitor, 12000)
                            if (wordbook == null) captureFailureState()
                            check(wordbook != null) { "Wordbook did not launch from its Home action" }
                            await("Wordbook has no foreground focus") { main { wordbook!!.hasWindowFocus() } }
                        } finally { removeMonitor(monitor) }
                    }
                    if (opened) test("back-returns-from-wordbook-to-home") {
                        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        await("Back did not return to Home") {
                            main { wordbook!!.isFinishing && !home!!.isFinishing && home!!.hasWindowFocus() }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            cases.put(JSONObject().put("name", "unexpected-harness-error").put("pass", false).put("error", error.toString()))
        } finally {
            runCatching { main { editor?.setText(originalText); wordbook?.finish(); home?.finish() } }
            val passed = (0 until cases.length()).count { cases.getJSONObject(it).getBoolean("pass") }
            val failed = cases.length() - passed
            val report = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("expected", expected)
                .put("passed", passed).put("failed", failed).put("not_run", (expected - cases.length()).coerceAtLeast(0))
                .put("cases", cases).put("geometry", observations).put("diagnostics", diagnostics)
                .put("touches", touches)
                .put("scope", "real touch, window metrics and Back key; predictive edge gesture is separate")
            File(targetContext.filesDir, "window-interaction-tests.json").writeText(report.toString(2))
            finish(if (passed == expected && failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("summary", report.toString()) })
        }
    }
}
