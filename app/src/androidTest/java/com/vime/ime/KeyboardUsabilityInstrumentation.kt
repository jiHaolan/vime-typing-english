package com.vime.ime

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Build
import com.vime.ime.data.DictRepository
import com.vime.ime.engine.RimeEngine
import com.vime.ime.engine.RimeNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.content.Intent
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.EditText
import com.vime.ime.engine.InputEngine
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Public lookup probes use a fresh cache user, never record into the product user dictionary. */
@android.annotation.TargetApi(30)
class KeyboardUsabilityInstrumentation : Instrumentation() {
    private val cases=JSONArray()
    private val observations=JSONObject()
    private fun progress(phase:String) {
        File(targetContext.filesDir,"keyboard-usability-progress.json").writeText(JSONObject().put("phase",phase).put("cases",cases).put("observations",observations).toString())
    }
    private fun <T> main(work:()->T):T {
        val task=FutureTask(Callable { work() });Handler(Looper.getMainLooper()).post(task)
        return task.get(10,TimeUnit.SECONDS)
    }
    private fun test(name:String,work:()->Unit) {
        val result=runCatching(work)
        cases.put(JSONObject().put("name",name).put("pass",result.isSuccess).put("error",result.exceptionOrNull()?.toString()?:JSONObject.NULL))
        progress(name)
    }
    private data class Fixture(val view:VimeKeyboardView,val keys:MutableList<Int>,val voice:MutableList<String>)
    private fun fixture()=main {
        val keys=ArrayList<Int>();val voice=ArrayList<String>()
        val view=VimeKeyboardView(com.vime.ime.data.ThemePref.keyboardContext(targetContext)).apply {
            keyHaptics=false;keyboard=Keyboard(context,R.xml.keyboard_qwerty);layout(0,0,1080,650)
            setOnKeyboardActionListener(object:KeyboardView.OnKeyboardActionListener {
                override fun onKey(code:Int,codes:IntArray?){keys.add(code)}
                override fun onPress(code:Int){};override fun onRelease(code:Int){}
                override fun onText(text:CharSequence?){};override fun swipeLeft(){};override fun swipeRight(){}
                override fun swipeUp(){};override fun swipeDown(){}
            })
            setVoiceGestureListener({voice.add("start")},{voice.add("end")},{voice.add("cancel")})
        }
        Fixture(view,keys,voice)
    }
    private fun center(f:Fixture,code:Int)=main {
        val keyboard=f.view.keyboard!!;val key=keyboard.keys.single { it.codes?.firstOrNull()==code }
        val content=keyboard.keys.maxOf { it.y+it.height }.coerceAtLeast(1)
        (key.x+key.width/2f)*f.view.width/keyboard.minWidth to (key.y+key.height/2f)*f.view.height/content
    }
    private fun event(f:Fixture,action:Int,p:Pair<Float,Float>)=main {
        val now=SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,action,p.first,p.second,0)
        try { f.view.dispatchTouchEvent(e) } finally { e.recycle() }
    }
    private fun children(root:View):List<View> = listOf(root)+(if(root is ViewGroup)(0 until root.childCount).flatMap { children(root.getChildAt(it)) } else emptyList())
    private fun await(reason:String,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+12000
        while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(80)}
        error(reason)
    }
    private fun viewChecks() {
        test("delete-tap-emits-one-delete") {
            val f=fixture();val p=center(f,-5);event(f,0,p);event(f,1,p);check(main { f.keys.toList() }==listOf(-5))
        }
        test("stationary-delete-hold-cannot-clear") {
            val f=fixture();val p=center(f,-5);event(f,0,p);SystemClock.sleep(1100);event(f,1,p);check(main { f.keys.toList() }==listOf(-5))
        }
        test("held-delete-upward-release-clears-without-neighbor-key") {
            val f=fixture();val p=center(f,-5);val up=p.first to p.second-42*f.view.resources.displayMetrics.density
            event(f,0,p);SystemClock.sleep(350);event(f,2,up);check(main { f.keys.isEmpty() });event(f,1,up)
            check(main { f.keys.toList() }==listOf(-20004))
        }
        test("returning-to-delete-disarms-clear") {
            val f=fixture();val p=center(f,-5);val up=p.first to p.second-42*f.view.resources.displayMetrics.density
            event(f,0,p);SystemClock.sleep(350);event(f,2,up);event(f,2,p);event(f,1,p);check(main { f.keys.toList() }==listOf(-5))
        }
        test("cancel-and-layout-change-discard-armed-clear") {
            for(cancel in listOf(true,false)) {
                val f=fixture();val p=center(f,-5);val up=p.first to p.second-42*f.view.resources.displayMetrics.density
                event(f,0,p);SystemClock.sleep(350);event(f,2,up)
                if(cancel)event(f,3,up) else main { f.view.keyboard=f.view.keyboard }
                event(f,1,up);check(main { f.keys.isEmpty() })
            }
        }
        test("second-finger-disarms-armed-clear") {
            val f=fixture();val p=center(f,-5);val up=p.first to p.second-42*f.view.resources.displayMetrics.density;val q=center(f,'q'.code)
            event(f,0,p);SystemClock.sleep(350);event(f,2,up)
            main {
                val properties=Array(2){i->MotionEvent.PointerProperties().apply{id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
                val coords=arrayOf(up,q).map { point->MotionEvent.PointerCoords().apply{x=point.first;y=point.second;pressure=1f;size=1f} }.toTypedArray()
                val now=SystemClock.uptimeMillis()
                val e=MotionEvent.obtain(now,now,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),2,properties,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
                try{f.view.dispatchTouchEvent(e)}finally{e.recycle()}
            }
            event(f,3,up);check(main { -20004 !in f.keys })
        }
        test("short-space-still-emits-one-space") {
            val f=fixture();val p=center(f,32);event(f,0,p);SystemClock.sleep(160);event(f,1,p)
            check(main { f.keys.toList()==listOf(32)&&f.voice.isEmpty() })
        }
        test("voice-starts-around-half-second-and-ends-on-release") {
            val f=fixture();val p=center(f,32);val started=SystemClock.uptimeMillis();event(f,0,p)
            SystemClock.sleep(350);check(main { f.voice.isEmpty() })
            await("voice trigger",{main { f.voice==listOf("start") }})
            val elapsed=SystemClock.uptimeMillis()-started;check(elapsed in 450..950)
            event(f,1,p);check(main { f.voice==listOf("start","end")&&f.keys.isEmpty() })
            observations.put("space_hold_trigger_ms",elapsed)
        }
        test("moving-off-space-cancels-without-space-or-neighbor-output") {
            val f=fixture();val p=center(f,32);event(f,0,p)
            await("voice trigger",{main { f.voice==listOf("start") }})
            event(f,2,p.first to -20f);event(f,1,p.first to -20f)
            check(main { f.voice==listOf("start","cancel")&&f.keys.isEmpty() })
        }
    }
    private fun windowChecks() {
        val home=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val editor=main { home.findViewById<EditText>(R.id.typingPreview) }
            await("home focus",{main { home.hasWindowFocus() }})
            for(command in listOf("ime enable com.vime.ime/.VimeImeService","ime set com.vime.ime/.VimeImeService","settings put secure show_ime_with_hard_keyboard 1")) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            }
            val position=main { val p=IntArray(2);editor.getLocationOnScreen(p);(p[0]+editor.width/2).toFloat() to (p[1]+editor.height/2).toFloat() }
            val now=SystemClock.uptimeMillis()
            for(action in listOf(0,1)) {
                val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,position.first,position.second,0).apply{source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                try { check(uiAutomation.injectInputEvent(e,true)) } finally {e.recycle()}
            }
            var keyboard:VimeKeyboardView?=null
            await("real IME visible",{main { keyboard=WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<VimeKeyboardView>(R.id.keyboard) }?.takeIf { it.isShown };keyboard!=null }})
            test("real-keyboard-leaves-system-control-row-clear") {
                main {
                    val k=keyboard!!;val bottom=k.rootView.findViewById<View>(R.id.bottomInset)
                    check(bottom.height>=48*k.resources.displayMetrics.density)
                    val rect=Rect();check(k.getGlobalVisibleRect(rect))
                    val controls=children(k.rootView).filter { it.javaClass.simpleName=="NavigationBarFrame"&&it.isShown }
                    for(control in controls){val r=Rect();check(control.getGlobalVisibleRect(r));check(!Rect.intersects(rect,r))}
                    observations.put("keyboard_bounds",rect.toShortString()).put("system_footer_px",bottom.height).put("system_control_frames",controls.size)
                }
            }
            test("formatting-sheet-copy-and-bottom-safety") {
                main { keyboard!!.rootView.findViewById<View>(R.id.tbSettings).performClick() }
                main {
                    val item=children(keyboard!!.rootView).filterIsInstance<TextView>().single { it.text.toString()=="本地排版" }
                    var clickable:View=item
                    while(!clickable.isClickable && clickable.parent is View)clickable=clickable.parent as View
                    check(clickable.performClick())
                }
                main {
                    val root=keyboard!!.rootView;val panel=root.findViewById<View>(R.id.panelSheet)
                    check(panel.isShown&&panel.paddingBottom>=48*panel.resources.displayMetrics.density)
                    val texts=children(panel).filterIsInstance<TextView>().map { it.text.toString() }
                    check("本地排版" in texts && texts.none { "非AI" in it || "不是AI" in it })
                }
            }
        } finally { main { home.finish() } }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching {
            check(Build.PRODUCT.contains("sdk"))
            progress("loading")
            val manager=targetContext.getSystemService(android.app.ActivityManager::class.java)
            val history=JSONArray()
            for(exit in manager.getHistoricalProcessExitReasons(targetContext.packageName,0,8)) {
                history.put(JSONObject().put("pid",exit.pid).put("timestamp",exit.timestamp).put("reason",exit.reason))
                if(exit.pid==32084 && exit.reason==android.app.ApplicationExitInfo.REASON_ANR)
                    exit.traceInputStream?.use { input->File(targetContext.filesDir,"keyboard-anr-32084.txt").outputStream().use { input.copyTo(it) } }
            }
            observations.put("historical_exits",history)
            DictRepository.load(targetContext)
            RimeEngine.initialize(targetContext)
            check(RimeEngine.state == RimeEngine.State.READY)
            val rows = JSONArray()
            synchronized(RimeEngine) {
                val shared = RimeEngine.sharedPath
                val isolated = File(targetContext.cacheDir, "keyboard-public-probe-${System.nanoTime()}").apply { check(mkdir()) }
                RimeNative.shutdown()
                try {
                    RimeNative.initialize(shared, isolated.absolutePath)
                    val samples = ('a'..'z').map { it.toString() } + listOf("shi", "de", "ni", "hao", "wo", "zai", "you", "chong", "zhong", "shijie", "nihao", "zhendejiade", "nh", "shihao")
                    for (raw in samples) { progress("probe:$raw");rows.put(JSONObject().put("raw", raw)
                        .put("native", JSONArray(RimeNative.candidates(raw, 0, 20).toList()))
                        .put("mapped", JSONArray(DictRepository.candidates(raw, 20).map { it.word }))
                        .put("sorted",JSONArray(InputEngine.query(raw,false,limit=20,personalized=false).map { it.word }))) }
                    test("common-word-first-with-reading-and-whole-phrase-preserved") {
                        for((raw,word) in mapOf("shihao" to "嗜好","e" to "额","wo" to "我","nihao" to "你好","zhendejiade" to "真的假的","shijie" to "世界"))
                            check(InputEngine.query(raw,false,personalized=false).first().word==word){raw}
                    }
                    test("pagination-across-ranked-head-is-stable-and-complete") {
                        val full=InputEngine.query("shi",false,limit=100,personalized=false).map { it.word }
                        val pages=(0..8).flatMap { InputEngine.query("shi",false,offset=it*12,limit=12,personalized=false) }.take(100).map { it.word }
                        check(full==pages&&full.distinct().size==full.size)
                    }
                    test("fallback-uses-same-comparable-word-order") {
                        val field=RimeEngine.javaClass.getDeclaredField("state").apply { isAccessible=true };val previous=field.get(null)
                        try { field.set(null,RimeEngine.State.FAILED);check(InputEngine.query("shihao",false,personalized=false).first().word=="嗜好") }
                        finally {field.set(null,previous)}
                    }
                } finally {
                    RimeNative.shutdown()
                    RimeNative.initialize(shared, File(targetContext.filesDir, "rime/user").absolutePath)
                }
            }
            viewChecks()
            windowChecks()
            check(cases.length()==14){"Missing checks: ${cases.length()}"}
            JSONObject().put("passed", (0 until cases.length()).all { cases.getJSONObject(it).getBoolean("pass") })
                .put("fresh_native_user", true).put("samples", rows).put("cases",cases).put("observations",observations)
        }
        val report=result.getOrElse { JSONObject().put("passed", false).put("error", it.toString()).put("cases",cases).put("observations",observations) }
        File(targetContext.filesDir,"keyboard-usability-results.json").writeText(report.toString())
        finish(if(result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("summary", report.toString())
        })
    }
}
