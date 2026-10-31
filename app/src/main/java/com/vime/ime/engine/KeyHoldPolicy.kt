package com.vime.ime.engine

import com.vime.ime.data.KeyHints

/** Space and secondary characters; destructive delete gestures have a separate directional policy. */
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
        code == -5 -> Output.Key(-5)
        code == 32 -> null // The hold timer starts recording; release ends it, never emits a space.
        KeyHints.secondary(code) != null -> Output.Text(KeyHints.secondary(code)!!)
        else -> Output.Key(code)
    }
}
