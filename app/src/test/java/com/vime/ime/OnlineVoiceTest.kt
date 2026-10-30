package com.vime.ime

import com.vime.ime.data.*
import com.vime.ime.voice.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OnlineVoiceTest {
    @Test fun fullPhraseExistsInShippedDictionaryAndConsumesAllPinyin() {
        val asset = listOf(File("src/main/assets/input/input.lex"), File("app/src/main/assets/input/input.lex")).first { it.exists() }
        val index = MappedInputIndex.open(asset)
        for (nine in listOf(false, true)) {
            val input = if (nine) "zhendejiade".map(CandidateIndex::digit).joinToString("") else "zhendejiade"
            val c = index.query(input, nine).first { it.word == "真的假的" }
            assertEquals(input.length, c.consumed)
        }
    }
    @Test fun laughterAndSlangAreOfflineCandidatesWithCorrectConsumption() {
        val index = CandidateIndex(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        val laugh = index.query("hhhhhh")
        assertEquals("哈哈哈哈哈哈", laugh.first().word)
        assertTrue(laugh.any { it.word == "哈哈哈哈哈" })
        assertTrue(laugh.all { it.consumed == 6 })
        assertEquals("永远的神", index.query("yyds").first().word)
        assertTrue(index.query("hhh1").isEmpty())
        assertTrue(index.query("444444", true).isEmpty())
        assertEquals(1, index.query("hhhhhh", limit = 1).size)
    }
    @Test fun onlySecureEndpointsAndSafeCredentialsAccepted() {
        VoiceProtocol.validate(VoiceProtocol.ENDPOINT, VoiceProtocol.MODEL, "test-token")
        for (url in listOf("http://example.com/asr", "https://me:secret@example.com/asr", "https://example.com/asr?key=x", "https://example.com/asr#key", "file:///tmp/audio"))
            assertThrows(IllegalArgumentException::class.java) { VoiceProtocol.validate(url, VoiceProtocol.MODEL, "test") }
        assertThrows(IllegalArgumentException::class.java) { VoiceProtocol.validate(VoiceProtocol.ENDPOINT, VoiceProtocol.MODEL, "test\r\nInjected: x") }
        assertThrows(IllegalArgumentException::class.java) { VoiceProtocol.validate("http://127.0.0.1/asr", VoiceProtocol.MODEL, "test") }
        VoiceProtocol.validate("http://127.0.0.1/asr", VoiceProtocol.MODEL, "test", true)
    }
    @Test fun wavHasAccuratePcm16kHeaderAndDuration() {
        val out = ByteArrayOutputStream(); VoiceProtocol.wavHeader(out, 32000)
        val b = out.toByteArray(); val le = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, b.size); assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals(32036, le.getInt(4)); assertEquals(1, le.getShort(22).toInt())
        assertEquals(16000, le.getInt(24)); assertEquals(16, le.getShort(34).toInt()); assertEquals(32000, le.getInt(40))
        assertThrows(IllegalArgumentException::class.java) { VoiceProtocol.wavHeader(out, VoiceProtocol.MAX_BYTES + 2) }
    }
    @Test fun multilingualResultValidationRejectsMissingAndOversizedText() {
        assertEquals("你好 Hello", VoiceProtocol.transcript("{\"text\":\" 你好 Hello \"}"))
        for (json in listOf("{}", "{\"text\":null}", "{\"text\":123}", "{\"text\":\" \"}", JSONObject().put("text", "a".repeat(4001)).toString()))
            assertThrows(IllegalArgumentException::class.java) { VoiceProtocol.transcript(json) }
    }
    @Test fun multipartUploadIsValidAndRedirectNeverCarriesKey() {
        val server = ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
        val received = java.util.concurrent.atomic.AtomicBoolean(false)
        server.soTimeout = 5000
        val serving = Thread {
            repeat(2) { index -> server.accept().use { socket ->
                val input = socket.getInputStream(); val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) { val c = input.read(); if (c < 0) return@Thread; head.append(c.toChar()) }
                val length = Regex("(?im)^Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length); var n = 0
                while (n < length) { val count = input.read(body, n, length - n); if (count < 0) break; n += count }
                val s = String(body, Charsets.ISO_8859_1)
                if (index == 0) received.set(head.contains("Authorization: Bearer fixture-token") && s.contains("name=\"model\"") && s.contains("FunAudioLLM/SenseVoiceSmall") && s.contains("RIFF") && s.contains("filename=\"speech.wav\""))
                val result = if (index == 0) "{\"text\":\"你好 Hello\"}".toByteArray() else ByteArray(0)
                val response = if (index == 0) "200 OK" else "307 Temporary Redirect"
                socket.getOutputStream().apply { write("HTTP/1.1 $response\r\nContent-Length: ${result.size}\r\nLocation: /key-leak\r\nConnection: close\r\n\r\n".toByteArray()); write(result); flush() }
            } }
        }.apply { isDaemon = true; start() }
        val file = File.createTempFile("voice-test", ".wav")
        try {
            file.outputStream().use { VoiceProtocol.wavHeader(it, 6400); it.write(ByteArray(6400)) }
            fun config(path: String) = VoiceConfig("http://127.0.0.1:${server.localPort}/$path", VoiceProtocol.MODEL, "fixture-token")
            assertEquals("你好 Hello", VoiceTransport().transcribe(config("ok"), file, true)); assertTrue(received.get())
            val error = assertThrows(IllegalArgumentException::class.java) { VoiceTransport().transcribe(config("redirect"), file, true) }
            assertTrue(error.message!!.contains("307")); serving.join(1000)
        } finally { server.close(); file.delete() }
    }
}
