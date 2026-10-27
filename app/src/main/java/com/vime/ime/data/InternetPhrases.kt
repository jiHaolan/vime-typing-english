package com.vime.ime.data

/** Curated offline shortcuts, not a live trending feed or chat-history mining. */
object InternetPhrases {
    private val shortcuts = mapOf(
        "yyds" to ("永远的神" to "the GOAT; the best"),
        "xswl" to ("笑死我了" to "that is hilarious"),
        "awsl" to ("啊我死了" to "I cannot handle it (internet slang)"),
        "nsdd" to ("你说得对" to "you are right"),
        "dbq" to ("对不起" to "sorry"),
        "zqsg" to ("真情实感" to "genuine feelings")
    )
    fun query(input: String): List<Cand> {
        if (input.length in 2..20 && input.all { it == 'h' }) {
            return listOf("哈".repeat(input.length.coerceAtMost(12)), "哈哈哈哈哈", "哈哈哈", "哈哈", "笑死我了").distinct()
                .map { Cand(it, if (it == "笑死我了") "that is hilarious" else "hahaha (laughter)", "", input.length) }
        }
        val pair = shortcuts[input] ?: return emptyList()
        return listOf(Cand(pair.first, pair.second, "", input.length))
    }
}
