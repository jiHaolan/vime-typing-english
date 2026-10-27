package com.vime.ime.engine

object KeyboardTuning {
    fun height(value: Int) = value.coerceIn(70, 150)
    fun width(value: Int) = value.coerceIn(65, 100)
    fun position(value: Int) = value.coerceIn(0, 100)
    fun bottom(value: Int) = value.coerceIn(0, 48)
    fun haptic(value: Int) = value.coerceIn(0, 3)
    fun duration(level: Int, long: Boolean = false): Long =
        if (haptic(level) == 0) 0 else (longArrayOf(0, 8, 16, 26)[haptic(level)] * if (long) 2 else 1)
    fun amplitude(level: Int) = intArrayOf(0, 55, 125, 220)[haptic(level)]
}
