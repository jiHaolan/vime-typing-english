package com.vime.ime

import com.vime.ime.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LexiconDatasetTest {
    @Test fun officialPackCoverageAndDesktopQueryBenchmark() {
        val location = System.getenv("VIME_PACK_PATH")
        assumeTrue("Set VIME_PACK_PATH for the separately downloaded licensed dataset", !location.isNullOrBlank())
        val started = System.nanoTime()
        val pack = File(location!!).inputStream().use(LexiconPackCodec::read)
        val parseMs = (System.nanoTime() - started) / 1e6
        assertEquals(121814, pack.entries.size)
        val asset = listOf(File("src/main/assets/dict.android.json"), File("app/src/main/assets/dict.android.json")).first { it.exists() }
        val root = JSONObject(asset.readText())
        val pw = root.getJSONObject("pw")
        val words = HashMap<String, MutableList<String>>()
        val freq = HashMap<String, Int>()
        val en = HashMap<String, String>()
        val rawEn = root.getJSONObject("en")
        rawEn.keys().forEach { en[it] = rawEn.getString(it) }
        pw.keys().forEach { key ->
            val rows = pw.getJSONArray(key)
            words[key] = (0 until rows.length()).map { rows.getJSONArray(it).getString(0) }.toMutableList()
            freq[key] = rows.getJSONArray(0).optInt(1, 0)
        }
        val baseCount = words.values.sumOf { it.size }
        val baseGloss = en.size
        for (entry in LexiconSupplement.entries) {
            val list = words.getOrPut(entry.pinyin) { ArrayList() }
            if (entry.word !in list) list.add(0, entry.word)
            en.putIfAbsent(entry.word, entry.english); freq.putIfAbsent(entry.pinyin, 1000)
        }
        for (entry in pack.entries) {
            val list = words.getOrPut(entry.pinyin) { ArrayList() }
            if (entry.word !in list) list.add(entry.word)
            en.putIfAbsent(entry.word, entry.gloss); freq.putIfAbsent(entry.pinyin, 1)
        }
        val indexStarted = System.nanoTime()
        val index = CandidateIndex(words, freq, en, emptyMap())
        val buildMs = (System.nanoTime() - indexStarted) / 1e6
        val examples = linkedMapOf("jinbi" to "金币", "mushu" to "木薯", "jiantieban" to "剪贴板",
            "shurufa" to "输入法", "liuchang" to "流畅", "lixian" to "离线", "liulian" to "榴莲", "pengzhang" to "膨胀")
        val coverage = JSONObject()
        for ((py, word) in examples) {
            val candidates = index.query(py, limit = 100)
            assertTrue("Missing input word: $word", candidates.any { it.word == word })
            assertFalse("Missing gloss: $word", en[word].isNullOrBlank())
            assertTrue("Missing T9 word: $word", index.query(py.map(CandidateIndex::digit).joinToString(""), true, 100).any { it.word == word })
            coverage.put(word, JSONObject().put("pinyin", py).put("position", candidates.indexOfFirst { it.word == word } + 1).put("gloss", en[word]))
        }
        val requests = words.keys.sorted().filter { it.length >= 3 }.take(2000).flatMap { listOf(it.take(1), it.take(2), it.take(3), it) }
        requests.take(200).forEach { index.query(it) }
        val nanos = requests.map { text ->
            val t = System.nanoTime(); index.query(text); System.nanoTime() - t
        }.sorted()
        fun percentile(p: Double) = nanos[((nanos.size - 1) * p).toInt()] / 1e6
        val report = JSONObject().put("kind", "Windows desktop JVM query microbenchmark, NOT Android UI or vivo measurements")
            .put("phone_tested", false).put("pack_records", pack.entries.size).put("base_input_records", baseCount)
            .put("merged_input_records", words.values.sumOf { it.size }).put("base_glosses", baseGloss).put("merged_glosses", en.size)
            .put("pack_parse_ms", parseMs).put("candidate_index_build_ms", buildMs).put("requests", requests.size)
            .put("p50_ms", percentile(.50)).put("p95_ms", percentile(.95)).put("p99_ms", percentile(.99))
            .put("max_ms", nanos.last() / 1e6).put("coverage", coverage).put("java", System.getProperty("java.version"))
            .put("os", System.getProperty("os.name")).put("scope", "CandidateIndex only; excludes IPC, drawing, touches and full repository reload")
        val reportFile = File("build/reports/lexicon-desktop-benchmark.json")
        reportFile.parentFile?.mkdirs(); reportFile.writeText(report.toString(2))
        println(report.toString(2))
    }
}
