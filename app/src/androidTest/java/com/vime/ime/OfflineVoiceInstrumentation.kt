package com.vime.ime

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import com.vime.ime.voice.NativeOfflineSpeech
import com.vime.ime.voice.OfflineModelStore
import com.vime.ime.voice.RecordedPcm
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Fixed-fixture transactions/JNI in an isolated test cache; never records a microphone. */
class OfflineVoiceInstrumentation : Instrumentation() {
    private var cloud = false
    private var nonce = ""
    private var reportReceiver:android.os.ResultReceiver?=null
    private val cases = JSONArray()
    private val observations = JSONObject()
    private fun test(name: String, action: () -> Unit) {
        val result = runCatching(action)
        cases.put(JSONObject().put("name", name).put("pass", result.isSuccess)
            .put("error", result.exceptionOrNull()?.toString() ?: JSONObject.NULL))
        sendStatus(0, Bundle().apply { putString("stream", "${if (result.isSuccess) "PASS" else "FAIL"} $name\n") })
    }
    override fun onCreate(arguments: Bundle?) {
        cloud=arguments?.getBoolean("vimeControlledCloud",false)==true
        nonce=arguments?.getString("resultNonce").orEmpty()
        @Suppress("DEPRECATION")
        val receiver:android.os.ResultReceiver?=arguments?.getParcelable("resultReceiver")
        reportReceiver=receiver
        super.onCreate(arguments); start()
    }
    override fun onStart() {
        var cloudRoot:File?=null
        try {
            val pageBytes=android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            if(cloud){
                check(nonce.matches(Regex("[a-f0-9-]{36}")))
                check(reportReceiver!=null)
                check(android.os.Process.is64Bit() && Build.SUPPORTED_ABIS.first() in listOf("arm64-v8a","x86_64"))
                check(pageBytes in listOf(4096L,16384L))
                check(targetContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                check(targetContext.packageManager.checkSignatures(targetContext.packageName,"com.vime.cloudfixture")==android.content.pm.PackageManager.SIGNATURE_MATCH)
            }else{
                check(Build.PRODUCT.contains("sdk") && Build.SUPPORTED_ABIS.first() == "x86_64")
                check(pageBytes == 16384L)
            }
            observations.put("abi",Build.SUPPORTED_ABIS.first()).put("page_bytes",pageBytes).put("model",Build.MODEL)
            val root = File(targetContext.cacheDir, if(cloud) "offline-voice-cloud-checks" else "offline-voice-checks")
            if(cloud){check(!root.exists());check(root.mkdirs());cloudRoot=root;prepareCloudFixtures(root)}
            else check(root.isDirectory || root.mkdirs())
            val directory = File(root, "isolated").apply { check(isDirectory || mkdirs()) }
            val sandbox = object : ContextWrapper(targetContext) { override fun getFilesDir() = directory }
            val fixture = File(root, "model.gguf")
            fun hash(file: File): String = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            fun pcm(name: String): ShortArray {
                val bytes = File(root, "$name.pcm16").readBytes()
                return ShortArray(bytes.size / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() }
            }
            val model = File(directory, "offline-voice/x-asr-zh-en-q4_k.gguf")
            test("pinned-model-import-and-real-content-readback") {
                check(hash(fixture) == OfflineModelStore.SHA256)
                fixture.inputStream().use { OfflineModelStore.importModel(sandbox, it, {}, {}) }
                check(OfflineModelStore.present(sandbox) && hash(model) == OfflineModelStore.SHA256)
                OfflineModelStore.withModel(sandbox, {}) { check(it.canonicalPath == model.canonicalPath) }
            }
            test("short-invalid-model-preserves-previous-valid-model") {
                check(runCatching { byteArrayOf(1, 2, 3).inputStream().use { OfflineModelStore.importModel(sandbox, it, {}, {}) } }.isFailure)
                check(hash(model) == OfflineModelStore.SHA256)
            }
            test("full-size-corrupted-model-preserves-previous-valid-model") {
                fixture.inputStream().use { original ->
                    val corrupt = object : FilterInputStream(original) {
                        var first = true
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            val count = super.read(bytes, offset, length)
                            if (first && count > 0) { bytes[offset] = (bytes[offset].toInt() xor 1).toByte(); first = false }
                            return count
                        }
                    }
                    check(runCatching { OfflineModelStore.importModel(sandbox, corrupt, {}, {}) }.isFailure)
                }
                check(hash(model) == OfflineModelStore.SHA256)
            }
            test("cancelled-model-import-preserves-previous-valid-model") {
                var transferred = 0L
                fixture.inputStream().use { input ->
                    check(runCatching { OfflineModelStore.importModel(sandbox, input,
                        { if (transferred > 65536) throw java.util.concurrent.CancellationException() }, { transferred = it }) }.isFailure)
                }
                check(hash(model) == OfflineModelStore.SHA256)
            }
            for (language in listOf("zh", "en")) test("real-jni-offline-$language") {
                val job = NativeOfflineSpeech.create()
                try {
                    val start = System.nanoTime()
                    val text = OfflineModelStore.withModel(sandbox, {}) { NativeOfflineSpeech.transcribe(job, it.absolutePath, pcm(language)) }
                    observations.put(language, JSONObject().put("text", text).put("milliseconds", (System.nanoTime() - start) / 1_000_000))
                    check(!text.isNullOrBlank())
                    if (language == "zh") check(text.contains("今天开始练习英语"))
                    else check(text.lowercase().contains("nice to meet you") && text.lowercase().contains("practice english"))
                } finally { NativeOfflineSpeech.destroy(job) }
                check(NativeOfflineSpeech.activeJobsForChecks() == 0)
            }
            test("cancel-before-decode-and-double-destroy-are-safe") {
                val job = NativeOfflineSpeech.create()
                NativeOfflineSpeech.cancel(job)
                check(NativeOfflineSpeech.transcribe(job, model.absolutePath, pcm("en")) == null)
                NativeOfflineSpeech.destroy(job); NativeOfflineSpeech.destroy(job); NativeOfflineSpeech.cancel(job)
                check(NativeOfflineSpeech.activeJobsForChecks() == 0)
            }
            test("cancel-during-native-decode-returns-without-a-result") {
                val job = NativeOfflineSpeech.create()
                val finished = CountDownLatch(1)
                var outcome: String? = "not completed"
                var error: Throwable? = null
                val original = pcm("en")
                val longAudio = ShortArray(480_000) { original[it % original.size] }
                thread(name = "offline-cancel-check") {
                    try { outcome = NativeOfflineSpeech.transcribe(job, model.absolutePath, longAudio) }
                    catch (failure: Throwable) { error = failure }
                    finally { NativeOfflineSpeech.destroy(job); finished.countDown() }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!NativeOfflineSpeech.startedForChecks(job) && finished.count > 0 && System.nanoTime() < deadline) Thread.sleep(10)
                check(NativeOfflineSpeech.startedForChecks(job)) { "No actual GGML graph callback observed" }
                val start = System.nanoTime(); NativeOfflineSpeech.cancel(job)
                check(finished.await(10, TimeUnit.SECONDS)) { "Native cancellation did not finish within 10 seconds" }
                observations.put("cancel_milliseconds", (System.nanoTime() - start) / 1_000_000)
                check(outcome == null && error == null)
                check(NativeOfflineSpeech.activeJobsForChecks() == 0)
            }
            test("existing-rime-still-returns-full-candidates-after-offline-jni") {
                com.vime.ime.data.DictRepository.load(targetContext)
                com.vime.ime.engine.RimeEngine.initialize(targetContext)
                check(com.vime.ime.engine.RimeEngine.state == com.vime.ime.engine.RimeEngine.State.READY)
                for ((pinyin, word) in mapOf("nihao" to "你好", "zhendejiade" to "真的假的", "xuexi" to "学习")) {
                    check(com.vime.ime.engine.RimeEngine.query(pinyin, limit = 100).any { it.word == word && it.en.isNotBlank() })
                }
                com.vime.ime.engine.RimeEngine.query("", limit = 10)
            }
            test("model-removal-affects-only-isolated-model-files") {
                val sentinel = File(directory, "unrelated.txt").apply { writeText("keep") }
                OfflineModelStore.remove(sandbox, {})
                check(!OfflineModelStore.present(sandbox) && sentinel.readText() == "keep")
                check(runCatching { OfflineModelStore.withModel(sandbox, {}) {} }.isFailure)
                check(sentinel.delete())
            }
        } catch (failure: Throwable) {
            cases.put(JSONObject().put("name", "harness").put("pass", false).put("error", failure.toString()))
        }
        cloudRoot?.let { root -> test("cloud-fixture-cache-cleaned") {
            for(name in listOf("model.gguf","zh.pcm16","en.pcm16","isolated/unrelated.txt","isolated/offline-voice/x-asr-zh-en-q4_k.gguf","isolated/offline-voice/x-asr-zh-en-q4_k.gguf.bak","isolated/offline-voice/x-asr-zh-en-q4_k.gguf.new")){
                val file=File(root,name);check(!file.exists() || file.delete())
            }
            for(name in listOf("isolated/offline-voice","isolated","")){val dir=if(name.isEmpty())root else File(root,name);check(!dir.exists() || dir.delete())}
        } }
        val report = JSONObject().put("cases", cases).put("observations", observations).put("scope","fixed synthetic PCM; no microphone or IME accuracy qualification")
        File(targetContext.cacheDir, if(cloud)"cloud-offline-voice-checks-result.json" else "offline-voice-checks-result.json").writeText(report.toString(2))
        if(cloud) reportReceiver?.send(Activity.RESULT_OK,Bundle().apply { putString("resultNonce",nonce);putString("offlineReport",report.toString()) })
        val passed = (0 until cases.length()).all { cases.getJSONObject(it).getBoolean("pass") }
        finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "OFFLINE_VOICE_RESULT=$report\n") })
    }
    private fun prepareCloudFixtures(root:File){
        fun uri(id:String)=android.net.Uri.parse("content://com.vime.cloudfixture.publicassets/$id")
        targetContext.contentResolver.openInputStream(uri("model"))!!.use { input ->
            File(root,"model.gguf").outputStream().use { output ->
                val buffer=ByteArray(65536);var total=0L
                while(true){val count=input.read(buffer);if(count<0)break;check(count>0);total+=count;check(total<=OfflineModelStore.BYTES);output.write(buffer,0,count)}
                check(total==OfflineModelStore.BYTES)
            }
        }
        for(language in listOf("zh","en")){
            val wav=targetContext.contentResolver.openInputStream(uri(language))!!.use { it.readBytes() }
            val pcm=RecordedPcm.decode(wav)
            File(root,"$language.pcm16").outputStream().buffered().use { output ->
                for(sample in pcm){output.write(sample.toInt() and 255);output.write((sample.toInt() shr 8) and 255)}
            }
        }
        val source=File(targetContext.applicationInfo.sourceDir)
        val digest=source.inputStream().use { java.security.MessageDigest.getInstance("SHA-256").let { hash ->
            val buffer=ByteArray(65536);while(true){val n=it.read(buffer);if(n<0)break;hash.update(buffer,0,n)};hash.digest().joinToString(""){b->"%02x".format(b)}
        } }
        observations.put("main_apk_sha256",digest)
    }
}
