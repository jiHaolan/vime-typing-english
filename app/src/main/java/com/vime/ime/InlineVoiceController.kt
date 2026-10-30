package com.vime.ime

import android.content.Context
import com.vime.ime.voice.VoiceSettings

/** Keeps the gesture lifecycle identical for both explicit backend choices. */
class InlineVoiceController(context: Context, state: (String?, Boolean, Float) -> Unit, hint: (String) -> Unit) {
    private val system = SystemVoiceController(context, state, hint)
    private val cloud = CloudVoiceController(context, state, hint)
    private val appContext = context
    val active get() = system.active || cloud.active
    fun cancel() { system.cancel(); cloud.cancel() }
    fun close() { system.close(); cloud.close() }
    fun release() { if(system.active) system.release() else if(cloud.active) cloud.release() }
    fun start(keyboardLanguage: String, commit: (String) -> Unit) {
        cancel()
        if(VoiceSettings.mode(appContext) != VoiceSettings.SYSTEM) cloud.start(commit)
        else system.start(VoiceSettings.language(appContext,keyboardLanguage),commit)
    }
}
