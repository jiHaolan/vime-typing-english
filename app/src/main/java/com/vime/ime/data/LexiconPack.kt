package com.vime.ime.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipInputStream

data class LexiconEntry(val pinyin: String, val word: String, val gloss: String, val reading: String)
data class LexiconPack(val name: String, val version: String, val source: String, val license: String,
    val notice: String, val licenseText: String, val entries: List<LexiconEntry>, val hash: String, val id: String = "legacy-$hash")

/** Data only, no code or extracted filenames. Untrusted packs must pass every bound and checksum. */
object LexiconPackCodec {
    const val MAX_COMPRESSED = 16 * 1024 * 1024
    private const val MAX_EXPANDED = 40 * 1024 * 1024
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun read(input: InputStream): LexiconPack {
        val files = HashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.properties", "entries.tsv", "NOTICE.txt", "LICENSE.txt") && !entry.isDirectory) { "词典包包含未知文件" }
                require(!files.containsKey(entry.name)) { "词典包含重复文件" }
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val bound = if (entry.name == "entries.tsv") MAX_EXPANDED else 128 * 1024
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_EXPANDED && out.size() + n <= bound) { "词典解压后过大" }
                    out.write(buffer, 0, n)
                }
                files[entry.name] = out.toByteArray()
            }
        }
        require(files.size == 4) { "缺少词典清单、数据或授权声明" }
        fun utf8(name: String) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(files.getValue(name))).toString()
        val props = Properties().apply { load(utf8("manifest.properties").reader()) }
        require(props.getProperty("format") == "vimelex-1") { "不支持的词典包格式" }
        fun field(key: String, max: Int = 256): String = props.getProperty(key).orEmpty().also {
            require(it.isNotBlank() && it.length <= max && it.none { c -> c < ' ' }) { "词典元信息无效: $key" }
        }
        val hash = sha256(files.getValue("entries.tsv"))
        require(hash == field("sha256")) { "词典数据校验失败，文件可能损坏" }
        val count = field("entries").toInt()
        require(count in 1..180000) { "词条数超出范围" }
        val entries = ArrayList<LexiconEntry>(count)
        utf8("entries.tsv").lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            require(entries.size < count && line.length <= 1400) { "词条数量或长度不符" }
            val parts = line.split('\t')
            require(parts.size == 4) { "词条必须包含四列" }
            val (py, word, gloss, reading) = parts
            require(py.length in 1..64 && py.all { it in 'a'..'z' }) { "拼音格式无效" }
            require(word.length in 1..64 && gloss.length in 1..1000 && reading.length <= 160) { "词条字段长度无效" }
            require(parts.all { value -> value.none { it < ' ' || it == '\u007f' } }) { "词条包含控制字符" }
            entries.add(LexiconEntry(py, word, gloss, reading))
        }
        require(entries.size == count) { "词条数量与清单不一致" }
        val id=props.getProperty("id")?.also { require(it.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}"))) { "词包标识无效" } } ?: "legacy-$hash"
        return LexiconPack(field("name"), field("version"), field("source", 1024), field("license"),
            utf8("NOTICE.txt"), utf8("LICENSE.txt"), entries, hash, id)
    }
}
