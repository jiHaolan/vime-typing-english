package com.vime.ime.engine

object InputRules {
    // Android InputType class/variation masks; pure functions are covered by JVM tests.
    fun password(inputType: Int): Boolean {
        val klass = inputType and 0xf
        val variation = inputType and 0xff0
        return (klass == 1 && variation in setOf(0x80, 0x90, 0xe0)) || (klass == 2 && variation == 0x10)
    }
    fun numeric(inputType: Int): Boolean = (inputType and 0xf) in setOf(2, 3, 4)
    fun ascii(inputType: Int, imeOptions: Int = 0): Boolean = password(inputType) || (imeOptions and Int.MIN_VALUE) != 0 ||
        ((inputType and 0xf) == 1 && (inputType and 0xff0) in setOf(0x10, 0x20, 0xd0))
    fun learningAllowed(inputType: Int, imeOptions: Int): Boolean =
        !password(inputType) && (imeOptions and 0x1000000) == 0
    fun remaining(input: String, consumed: Int): String = input.drop(consumed.coerceIn(0, input.length))
}
