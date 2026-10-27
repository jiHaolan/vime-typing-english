package com.vime.ime.engine

import com.vime.ime.data.KeyHints

/** Timer arms an action; only release on the same key can emit it. Pure JVM policy. */
object KeyHoldPolicy {
    const val CLEAR = -20004
    const val VOICE = -20008
    sealed interface Output {
        data class Key(val code: Int) : Output
        data class Text(val value: String) : Output
    }
    fun release(code: Int, armed: Boolean, sameKey: Boolean): Output? = when {
        !sameKey -> null
        !armed -> Output.Key(code)
        code == -5 -> Output.Key(CLEAR)
        code == 32 -> null // The hold timer starts recording; release ends it, never emits a space.
        KeyHints.secondary(code) != null -> Output.Text(KeyHints.secondary(code)!!)
        else -> Output.Key(code)
    }
}
