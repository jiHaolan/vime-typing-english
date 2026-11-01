package com.vime.ime

import com.vime.ime.voice.RecordedPcm
import com.vime.ime.voice.VoiceProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class RecordedPcmTest {
    @Test fun recorderWavPreservesSignedSamplesAndRejectsOtherFormats() {
        val samples = shortArrayOf(0, 1, -1, Short.MIN_VALUE, Short.MAX_VALUE)
        val out = ByteArrayOutputStream()
        VoiceProtocol.wavHeader(out, samples.size * 2)
        samples.forEach { out.write(it.toInt() and 255); out.write(it.toInt() ushr 8 and 255) }
        val wav = out.toByteArray()
        assertArrayEquals(samples, RecordedPcm.decode(wav))
        for (offset in listOf(20, 22, 24, 34, 40)) {
            val invalid = wav.clone().apply { this[offset] = (this[offset].toInt() xor 1).toByte() }
            assertTrue(runCatching { RecordedPcm.decode(invalid) }.isFailure)
        }
        assertTrue(runCatching { RecordedPcm.decode(wav.copyOf(wav.size - 1)) }.isFailure)
        assertTrue(runCatching { RecordedPcm.decode(ByteArray(960_046)) }.isFailure)
    }
}
