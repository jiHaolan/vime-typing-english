package com.vime.ime

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.vime.ime.data.NoticeCatalog
import com.vime.ime.data.ThemePref
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Offline, selectable license documents. No WebView, networking or arbitrary file intents. */
class NoticesActivity:AppCompatActivity() {
    private lateinit var catalog:NoticeCatalog
    private lateinit var body:LinearLayout
    private lateinit var scroll:ScrollView
    private val io=Executors.newSingleThreadExecutor()
    private var document:String?=null
    private var indexScroll=0
    private var generation=0
    private val back=object:OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showIndex() }
    }
    override fun onCreate(state:Bundle?) {
        ThemePref.apply(this);super.onCreate(state)
        body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(Ui.dp(this,20),Ui.dp(this,16),Ui.dp(this,20),Ui.dp(this,32))
            setBackgroundColor(uiColor(R.color.panel_bg))
        }
        scroll=ScrollView(this).apply { isFillViewport=true;addView(body) }
        setContentView(scroll);Ui.window(this)
        onBackPressedDispatcher.addCallback(this,back)
        catalog=NoticeCatalog.parse(assets.open("notices.json").bufferedReader().use { it.readText() })
        indexScroll=state?.getInt("indexScroll") ?: 0
        val selected=state?.getString("document")?.takeIf { it in catalog.files }
        if(selected==null) showIndex() else {
            document=selected
            showDocument(selected,state?.getInt("scroll") ?: 0)
        }
    }
    private fun header(title:String,subtitle:String) {
        body.removeAllViews()
        body.addView(Ui.back(this,"‹ 返回") { if(document!=null) showIndex() else finish() })
        Ui.space(body,20);Ui.heading(this,body,title,subtitle)
    }
    private fun showIndex() {
        generation++;document=null;back.isEnabled=false
        header("开源与数据来源","查看随本版应用附带的来源说明与许可原文，无需联网。")
        var group=""
        for(entry in catalog.components) {
            if(entry.category!=group) { group=entry.category;Ui.section(this,body,group) }
            val card=Ui.card(body)
            card.addView(Ui.label(this,entry.title,18f,true))
            card.addView(Ui.label(this,"${entry.license}\n${entry.version}",14f,secondary=true))
            Ui.space(card,8)
            card.addView(Ui.label(this,entry.summary,15f).apply { setTextIsSelectable(true) })
            card.addView(Ui.label(this,entry.source,13f,secondary=true).apply { setTextIsSelectable(true) })
            for(file in entry.documents) card.addView(Ui.button(this,"查看 ${file.substringAfterLast('/')}",action={showDocument(file)}))
        }
        scroll.post { scroll.scrollTo(0,indexScroll) }
    }
    private fun showDocument(name:String,position:Int=0) {
        val expected=catalog.files[name] ?: return
        if(document==null) indexScroll=scroll.scrollY
        document=name;back.isEnabled=true
        val token=++generation
        header("许可原文",name.substringAfterLast('/'))
        val text=Ui.label(this,"正在读取许可…",15f).apply { id=R.id.noticeDocument;setTextIsSelectable(true);body.addView(this) }
        io.execute {
            val result=runCatching {
                val bytes=assets.open(name).use { it.readBytes() }
                check(bytes.size<=256*1024)
                val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(hash==expected)
                bytes.toString(Charsets.UTF_8)
            }
            runOnUiThread {
                if(!isFinishing && !isDestroyed && token==generation) {
                    text.text=result.getOrElse { "许可文件暂时无法读取，请重新安装本版本应用。" }
                    scroll.post { scroll.scrollTo(0,position) }
                }
            }
        }
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("document",document);out.putInt("indexScroll",if(document==null) scroll.scrollY else indexScroll);out.putInt("scroll",scroll.scrollY)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() { generation++;io.shutdown();super.onDestroy() }
}
