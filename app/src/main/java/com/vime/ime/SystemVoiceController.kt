package com.vime.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vime.ime.engine.HeldSpeechResult
import com.vime.ime.engine.VoiceSessionGate
import com.vime.ime.voice.SystemVoiceServices

/** Only the chosen OS service captures audio; this controller stores no recording. All calls run on main. */
internal class SystemVoiceController(private val context: Context,
    private val state: (String?,Boolean,Float)->Unit, private val hint: (String)->Unit) {
    private class Session(val token: Long,val recognizer: SpeechRecognizer,val commit: (String)->Unit) {
        val result=HeldSpeechResult()
        var ready=false
        var ended=false
        var released=false
    }
    private val main=Handler(Looper.getMainLooper())
    private val gate=VoiceSessionGate()
    private var session: Session?=null
    val active get()=session!=null
    private fun alive(s: Session)=session===s && gate.accepts(s.token)
    fun cancel() {
        gate.cancel();main.removeCallbacksAndMessages(null)
        val old=session;session=null;old?.result?.cancel()
        old?.recognizer?.let { runCatching { it.cancel() };runCatching { it.destroy() } }
        state(null,false,0f)
    }
    fun close()=cancel()
    private fun fail(s: Session,message: String) { if(alive(s)){cancel();hint(message)} }
    private fun finish(s: Session,text: String) { if(alive(s)){cancel();s.commit(text)} }
    fun release() {
        val s=session ?: return
        if(s.released)return
        if(!s.ready){fail(s,"语音服务尚未就绪；请重试并等到正在聆听后说话");return}
        s.released=true
        val text=s.result.release()
        if(text!=null){finish(s,text);return}
        state("正在识别…",false,0f)
        main.postDelayed({fail(s,"语音服务等待超时，请重试或更换服务")},20000)
        if(!s.ended) runCatching { s.recognizer.stopListening() }.onFailure { fail(s,"无法结束语音收音，请重试") }
    }
    fun start(language: String,commit: (String)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        cancel()
        val provider=SystemVoiceServices.selected(context)
        if(provider==null){hint("没有可用的手机语音服务，请打开首页「语音输入」查看并启用服务");return}
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            runCatching { context.startActivity(Intent(context,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { hint("请在应用权限中开启麦克风后再试") }
            return
        }
        val sr=runCatching { SpeechRecognizer.createSpeechRecognizer(context,provider.component) }.getOrElse {
            hint("无法连接所选语音服务，请在语音设置中检查");return
        }
        val s=Session(gate.start(),sr,commit);session=s
        state("正在连接手机语音… 松手取消",false,0f)
        sr.setRecognitionListener(object:RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if(!alive(s))return
                s.ready=true;state("正在聆听 · 松手结束",true,0f)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if(alive(s)&&s.ready&&!s.released&&!s.ended)state("正在聆听 · 松手结束",true,rmsdB.coerceIn(0f,10f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if(!alive(s))return
                s.ended=true;state(if(s.released)"正在识别…" else "服务已结束收音 · 松手识别",false,0f)
            }
            override fun onError(error: Int) { fail(s,SystemVoiceServices.error(error)) }
            override fun onResults(results: Bundle?) {
                if(!alive(s))return
                s.ended=true
                runCatching { s.result.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)) }
                    .onSuccess { text -> if(text!=null)finish(s,text) else state("已识别 · 松手输入",false,0f) }
                    .onFailure { fail(s,it.message ?: "语音结果不可用，请重试") }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int,params: Bundle?) {}
        })
        main.postDelayed({if(alive(s)&&!s.ready)fail(s,"语音服务连接超时，请在语音设置中更换服务")},12000)
        main.postDelayed({
            if(alive(s)&&!s.released&&!s.ended) {
                s.ended=true
                state("已达60秒 · 松手识别",false,0f)
                runCatching { s.recognizer.stopListening() }.onFailure { fail(s,"无法结束语音收音，请重试") }
            }
        },60000)
        val request=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
        runCatching { sr.startListening(request) }.onFailure { fail(s,"无法开始语音，请检查麦克风权限和语音服务") }
    }
}
