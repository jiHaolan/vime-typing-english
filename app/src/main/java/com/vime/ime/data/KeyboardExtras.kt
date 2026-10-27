package com.vime.ime.data

/** Text-only, offline contents. No copied sticker packs, remote URLs or product branding. */
object KeyboardExtras {
    val symbols = linkedMapOf(
        "中文" to listOf("，", "。", "、", "？", "！", "；", "：", "“", "”", "‘", "’", "（", "）", "《", "》", "「", "」", "【", "】", "…", "——", "·", "～", "￥"),
        "英文" to listOf(",", ".", "?", "!", ";", ":", "'", "\"", "(", ")", "[", "]", "{", "}", "_", "-", "/", "\\", "|", "~", "`", "@", "#", "$"),
        "数学" to listOf("+", "−", "×", "÷", "=", "≠", "≈", "≤", "≥", "%", "‰", "√", "π", "∞", "°", "²", "³", "±", "∑", "∠", "∴", "∵", "∈", "∅"),
        "网络" to listOf("@", "#", "&", "*", "/", "\\", "_", "-", ".com", ".cn", "https://", "www.", "://", "@qq.com", "@163.com", "@gmail.com", "+86", "©", "®", "™", "→", "←", "↑", "↓")
    )
    val emoji = linkedMapOf(
        "Emoji" to listOf("😀", "😄", "😁", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎", "🤔", "🥺", "😭", "😅", "😴", "😇", "👍", "👎", "👏", "🙏", "👌", "✌️", "💪", "🤝", "❤️", "💛", "💚", "💙", "✨", "🎉", "🔥", "💯", "🌹", "☕", "🍀", "🌙", "🐱", "🐶", "🍎", "🎂", "📚", "✏️", "✅", "❌", "📌", "💡", "🎯", "🎁"),
        "颜文字" to listOf("(＾▽＾)", "(≧▽≦)", "(｡•̀ᴗ-)✧", "(๑•̀ㅂ•́)و✧", "(•‿•)", "(づ｡◕‿‿◕｡)づ", "(╥﹏╥)", "(；一_一)", "¯\\_(ツ)_/¯", "(｡･ω･｡)", "(ง •̀_•́)ง", "(￣▽￣)")
    )
    val allSymbols: Set<String> = symbols.values.flatten().toSet()
    val allEmoji: Set<String> = emoji.values.flatten().toSet()
    fun recent(existing: List<String>, picked: String, allowed: Set<String>, limit: Int = 24): List<String> {
        if (limit <= 0) return emptyList()
        val valid = existing.filter { it in allowed }.distinct()
        return (if (picked in allowed) listOf(picked) + valid.filterNot { it == picked } else valid).take(limit)
    }
}

/** Visible top-right hints correspond exactly to the long-press output. */
object KeyHints {
    private val hints = "qwertyuiop".zip("1234567890").associate { it.first to it.second.toString() } +
        mapOf('a' to "@", 's' to "#", 'd' to "$", 'f' to "%", 'g' to "&", 'h' to "*", 'j' to "(", 'k' to ")", 'l' to "\"",
            'z' to "-", 'x' to "_", 'c' to "=", 'v' to "+", 'b' to ";", 'n' to ":", 'm' to "?")
    fun secondary(code: Int): String? = if (code in 'a'.code..'z'.code) hints[code.toChar()] else null
}
