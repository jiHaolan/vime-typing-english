package com.vime.ime.data

import android.content.Context
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Versioned generated public files only; never reads or deletes private learning data. */
object InputLexiconStore {
    data class BuiltIn(val index: MappedInputIndex, val associations: AssociationIndex,
                       val codes: Int, val entryCount: Int, val datasetId: String)
    @Volatile private var loaded: BuiltIn? = null
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(16384)
            while (true) { val size = input.read(bytes); if (size < 0) break; hash.update(bytes, 0, size) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun child(parent: File, name: String) = File(parent, name).also {
        check(it.absolutePath == it.canonicalPath) { "Unexpected linked public-lexicon path" }
    }
    @Synchronized fun load(context: Context): BuiltIn {
        loaded?.let { return it }
        check(Looper.myLooper() != Looper.getMainLooper())
        val manifest = context.assets.open("input/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getString("format") == "vime-input-lexicon-1" && manifest.getInt("version") == 1)
        require(!manifest.getBoolean("legacy_vime_input_read") && !manifest.getBoolean("english_sources_merged"))
        val files = manifest.getJSONObject("files")
        val names = files.keys().asSequence().toList().sorted()
        require(names == listOf("associations.json", "input.lex"))
        names.forEach { require(files.getString(it).matches(Regex("[a-f0-9]{64}"))) }
        val canonical = names.joinToString(",", "{", "}") { "\"$it\":\"${files.getString(it)}\"" }
        val id = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        require(id == manifest.getString("dataset_id"))
        val root = child(context.filesDir.canonicalFile, "lexicon-input").apply { check(isDirectory || mkdirs()) }
        val directory = child(root, id).apply { check(isDirectory || mkdirs()) }
        for (name in names) {
            val file = child(directory, name)
            if (!file.isFile || sha(file) != files.getString(name)) {
                val temp = child(directory, "$name.tmp")
                FileOutputStream(temp).use { stream ->
                    context.assets.open("input/$name").use { it.copyTo(stream) }; stream.fd.sync()
                }
                check(sha(temp) == files.getString(name)) { "Public lexicon asset differs" }
                check(temp.renameTo(file)) { "Public lexicon could not be saved" }
            }
        }
        val index = MappedInputIndex.open(child(directory, "input.lex"))
        val counts = manifest.getJSONObject("counts")
        require(index.count == counts.getInt("entries"))
        val associations = child(directory, "associations.json").bufferedReader().use { JSONObject(it.readText()) }
        require(associations.length() == counts.getInt("association_prefixes"))
        val pairs = HashMap<String, List<String>>()
        associations.keys().forEach { key ->
            val values = associations.getJSONArray(key)
            require(key.length in 1..4 && values.length() <= 12)
            pairs[key] = (0 until values.length()).map { values.getString(it) }
        }
        val result = BuiltIn(index, AssociationIndex(pairs), counts.getInt("codes"), counts.getInt("entries"), id)
        loaded = result
        // Old public versions can be retired only after the new version is usable.
        runCatching {
            fun remove(file: File) {
                check(file.absolutePath == file.canonicalPath)
                if (file.isDirectory) checkNotNull(file.listFiles()).forEach(::remove)
                check(!file.exists() || file.delete())
            }
            root.listFiles()?.filter { it.name != id && it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach(::remove)
        }
        return result
    }
}
