package com.vime.ime.engine

/** A provider may return before finger-up. Final text can be taken exactly once, only after release. */
class HeldSpeechResult {
    private var released=false
    private var valid=true
    private var pending: String?=null
    fun result(values: List<String>?): String? {
        if(!valid) return null
        if(pending!=null) return take()
        val text=values?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        require(text.isNotEmpty()) { "没有听清，请再说一次" }
        require(text.length<=4000 && '\u0000' !in text) { "语音文字过长或格式异常，请分段输入" }
        pending=text
        return take()
    }
    fun release(): String? { if(!valid || released)return null;released=true;return take() }
    fun cancel() { valid=false;pending=null }
    private fun take(): String? = if(valid && released && pending!=null) pending.also { valid=false;pending=null } else null
}
