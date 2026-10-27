package com.vime.ime.engine

object NavigationRules {
    enum class Destination { BOOK, FINISH }
    fun back(practicing: Boolean) = if (practicing) Destination.BOOK else Destination.FINISH
}
