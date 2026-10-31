package com.vime.ime

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.vime.ime.data.CandidateIndex
import com.vime.ime.data.InputLearningPrefs
import com.vime.ime.engine.InputEngine
import com.vime.ime.engine.RimeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Actual JNI, shipped dictionaries and native user database on a dedicated emulator.
 * expectedPageSize is mandatory, so a normal 4KB run cannot silently certify 16KB support.
 * Does not replace the user's learning wordbook. Native learning uses this emulator's Rime data.
 */
class NativeRuntimeInstrumentation : Instrumentation() {
    private val cases = JSONArray()
    private val observations = JSONObject()
    private var expectedPageSize = 0L
    private val expected = 9

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        expectedPageSize = arguments?.getString("expectedPageSize")?.toLongOrNull() ?: 0L
        start()
    }

    private fun test(name: String, action: () -> Unit): Boolean {
        val started = SystemClock.elapsedRealtime()
        val result = runCatching(action)
        cases.put(JSONObject().put("name", name).put("pass", result.isSuccess)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("error", result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0, Bundle().apply { putString("stream", "${if (result.isSuccess) "PASS" else "FAIL"} $name\n") })
        return result.isSuccess
    }

    private fun phrase(raw: String, word: String) {
        check(RimeEngine.query(raw, limit = 256).any { it.word == word && it.consumed == raw.length && it.fromRime }) {
            "Missing complete native candidate for $raw"
        }
    }

    override fun onStart() {
        var previousChoices:Boolean?=null
        try {
            val environment = test("emulator-has-explicitly-required-page-size") {
                check(Build.PRODUCT.contains("sdk")) { "Dedicated emulator required" }
                check(expectedPageSize in listOf(4096L, 16384L)) { "Pass -e expectedPageSize 4096 or 16384" }
                val actual = Os.sysconf(OsConstants._SC_PAGESIZE)
                observations.put("process_page_size_bytes", actual).put("expected_page_size_bytes", expectedPageSize)
                check(actual == expectedPageSize) { "Required $expectedPageSize bytes, actual $actual bytes" }
            }
            if(environment) { previousChoices=InputLearningPrefs.choicesEnabled; InputEngine.setChoiceLearning(targetContext,true) }
            val initialized = environment && test("native-library-and-shipped-dictionaries-initialize") {
                RimeEngine.initialize(targetContext)
                observations.put("rime_state", RimeEngine.state.name).put("rime_version", RimeEngine.version)
                    .put("rime_startup_ms", RimeEngine.startupMs).put("rime_failure", RimeEngine.failure)
                    .put("dataset_id", RimeEngine.datasetId).put("shared_path", RimeEngine.sharedPath)
                check(RimeEngine.state == RimeEngine.State.READY && RimeEngine.version.isNotBlank())
                val mapped = File("/proc/self/maps").readLines().filter { "/files/rime/" in it }
                observations.put("mapped_dictionary_regions", JSONArray(mapped))
                check(mapped.any { "table.bin" in it } && mapped.any { "prism.bin" in it }) {
                    "Expected actual mapped table and prism dictionaries"
                }
                check(mapped.any { "${RimeEngine.sharedPath}/build/vime_pinyin.table.bin" in it }) { "Old public dictionary remains active" }
            }
            if (initialized) {
                test("rare-reading-does-not-win-common-word-first-candidate") {
                    for ((raw, forbidden) in listOf("e" to "我", "jiwo" to "给我", "nidi" to "你的", "changdai" to "长大")) {
                        val first=RimeEngine.query(raw).firstOrNull() ?: error("Missing native candidates for $raw")
                        check(first.word!=forbidden) { "Wrong common first candidate: $raw => $forbidden" }
                    }
                    check(RimeEngine.query("e").first().word in setOf("额","饿","恶","俄","鹅","呃","厄","蛾","娥"))
                }
                test("full-phrase-candidate-consumes-all-pinyin") { phrase("zhendejiade", "真的假的") }
                test("nine-key-and-pinyin-schema-switch-both-return-full-words") {
                    phrase("nh", "你好")
                    phrase("nihao".map(CandidateIndex::digit).joinToString(""), "你好")
                    phrase("nihao", "你好")
                }
                test("native-candidate-pagination-is-complete") {
                    fun rows(offset: Int, limit: Int) = RimeEngine.query("shi", offset, limit).map { it.word to it.consumed }
                    val all = rows(0, 24)
                    check(all.size == 24)
                    check(rows(0, 12) + rows(12, 12) == all)
                }
                test("repeated-native-schema-switching-keeps-candidates-valid") {
                    repeat(40) {
                        phrase("nihao", "你好")
                        phrase("nihao".map(CandidateIndex::digit).joinToString(""), "你好")
                    }
                }
                test("native-learning-call-retains-full-candidate-consumption") {
                    val candidate = RimeEngine.query("nihao").first { it.word == "你好" && it.consumed == 5 }
                    RimeEngine.learn("nihao", candidate)
                    phrase("nihao", "你好")
                    observations.put("native_user_database_files", JSONArray(
                        File(targetContext.filesDir, "rime/user").walkTopDown().filter { it.isFile }
                            .map { it.relativeTo(targetContext.filesDir).invariantSeparatorsPath }.toList()))
                }
                test("precompiled-runtime-works-without-raw-build-sources") {
                    val folder=File(targetContext.cacheDir,"runtime-probe-"+java.util.UUID.randomUUID())
                    val shared=File(folder,"shared").apply { check(mkdirs()) }
                    val user=File(folder,"user").apply { check(mkdirs()) }
                    val manifest=JSONObject(targetContext.assets.open("rime/manifest.json").bufferedReader().use { it.readText() })
                    val names=manifest.getJSONObject("files").keys().asSequence().filter { it !in setOf("essay.txt","vime_pinyin.dict.yaml") }.toList()
                    names.forEach { name ->
                        val dest=File(shared,name);check(dest.canonicalPath.startsWith(shared.canonicalPath+File.separator))
                        dest.parentFile!!.mkdirs();targetContext.assets.open("rime/$name").use { input -> dest.outputStream().use { input.copyTo(it) } }
                    }
                    synchronized(RimeEngine) {
                        com.vime.ime.engine.RimeNative.shutdown()
                        try {
                            check(com.vime.ime.engine.RimeNative.initialize(shared.path,user.path).isNotBlank())
                            for((raw,word) in mapOf("nihao" to "你好","zhendejiade" to "真的假的","zhangda" to "长大","yinhang" to "银行")) {
                                for(input in listOf(raw,raw.map(CandidateIndex::digit).joinToString("")))
                                    check(com.vime.ime.engine.RimeNative.candidates(input,0,100).any { it.substringBefore('\t')==word })
                            }
                            com.vime.ime.engine.RimeNative.learn("nihao","你好",5)
                            observations.put("source_free_runtime_probe",JSONObject().put("files",names.size).put("fresh_private_user",true)
                                .put("raw_essay_absent",!File(shared,"essay.txt").exists()).put("raw_dictionary_absent",!File(shared,"vime_pinyin.dict.yaml").exists()))
                        } finally {
                            com.vime.ime.engine.RimeNative.shutdown()
                            check(com.vime.ime.engine.RimeNative.initialize(RimeEngine.sharedPath,File(targetContext.filesDir,"rime/user").path).isNotBlank())
                        }
                    }
                    phrase("nihao","你好")
                }
            }
        } catch (error: Throwable) {
            cases.put(JSONObject().put("name", "unexpected-runner-error").put("pass", false).put("error", error.toString()))
        } finally {
            previousChoices?.let { InputEngine.setChoiceLearning(targetContext,it) }
            val passed = (0 until cases.length()).count { cases.getJSONObject(it).getBoolean("pass") }
            val report = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
                .put("expected", expected).put("passed", passed).put("failed", cases.length() - passed)
                .put("not_run", (expected - cases.length()).coerceAtLeast(0)).put("cases", cases)
                .put("observations", observations)
                .put("scope", "Actual native Rime calls; not a physical ARM64, ASR or long-term performance test")
            File(targetContext.filesDir, "native-runtime-tests.json").writeText(report.toString(2))
            finish(if (passed == expected && cases.length() == expected) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Bundle().apply { putString("summary", report.toString()) })
        }
    }
}
