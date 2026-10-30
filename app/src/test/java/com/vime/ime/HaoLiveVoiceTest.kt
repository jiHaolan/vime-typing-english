package com.vime.ime

import com.vime.ime.voice.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class HaoLiveVoiceTest {
    @Test fun noKeyEndpointStillRequiresSafeAddressAndCorrectRoute() {
        VoiceProtocol.validate(VoiceConfig("https://speech.example/v1/transcribe", "", "", true), false)
        VoiceProtocol.validateHaoLive("http://127.0.0.1:18781/v1/transcribe", true)
        for (url in listOf("http://127.0.0.1:18781/v1/transcribe", "http://speech.example/v1/transcribe",
            "https://user:secret@speech.example/v1/transcribe", "https://speech.example/v1/transcribe?key=x",
            "https://speech.example/v1/transcribe#x", "https://speech.example/v1/audio/transcriptions", "file:///v1/transcribe")) {
            assertThrows(IllegalArgumentException::class.java) { VoiceProtocol.validateHaoLive(url) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceProtocol.validate(VoiceConfig(VoiceProtocol.ENDPOINT, VoiceProtocol.MODEL, ""), false)
        }
    }

    @Test fun rawWavReachesServerWithoutAuthorizationAndRedirectIsNotFollowed() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val failure = AtomicReference<Throwable?>()
        val file = File.createTempFile("haolive-voice", ".wav")
        file.outputStream().use { VoiceProtocol.wavHeader(it, 6400); it.write(ByteArray(6400) { n -> (n % 127).toByte() }) }
        val original = file.readBytes()
        val serving = Thread {
            try {
                repeat(2) { index -> server.accept().use { socket ->
                    val input = socket.getInputStream(); val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); head.append(c.toChar()) }
                    assertTrue(head.startsWith("POST /v1/transcribe HTTP/1.1"))
                    assertTrue(head.contains("Content-Type: audio/wav", ignoreCase = true))
                    assertFalse(head.contains("Authorization:", ignoreCase = true))
                    val length = Regex("(?im)^Content-Length: (\\d+)").find(head)!!.groupValues[1].toInt()
                    assertEquals(original.size, length)
                    val body = ByteArray(length); var offset = 0
                    while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
                    assertArrayEquals(original, body)
                    val result = if (index == 0) "{\"text\":\"你好 Hello\"}".toByteArray() else ByteArray(0)
                    val response = if (index == 0) "200 OK" else "307 Temporary Redirect"
                    socket.getOutputStream().use { out ->
                        out.write("HTTP/1.1 $response\r\nContent-Length: ${result.size}\r\nLocation: /unexpected\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(result); out.flush()
                    }
                } }
            } catch (e: Throwable) { failure.set(e) }
        }.apply { isDaemon = true; start() }
        try {
            val config = VoiceConfig("http://127.0.0.1:${server.localPort}/v1/transcribe", "", "", true)
            assertEquals("你好 Hello", VoiceTransport().transcribe(config, file, true))
            assertTrue(assertThrows(IllegalArgumentException::class.java) { VoiceTransport().transcribe(config, file, true) }.message!!.contains("307"))
            serving.join(1500); assertFalse(serving.isAlive); failure.get()?.let { throw AssertionError(it) }
        } finally { server.close(); file.delete() }
    }

    @Test fun thirtySecondServerLimitIsAppliedBeforeUpload() {
        val file = File.createTempFile("haolive-too-long", ".wav")
        try {
            file.writeBytes(ByteArray(VoiceProtocol.HAOLIVE_MAX_BYTES + 46))
            assertThrows(IllegalStateException::class.java) {
                VoiceTransport().transcribe(VoiceConfig("http://127.0.0.1:1/v1/transcribe", "", "", true), file, true)
            }
        } finally { file.delete() }
    }
}
