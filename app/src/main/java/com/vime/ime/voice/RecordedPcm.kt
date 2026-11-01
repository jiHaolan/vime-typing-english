package com.vime.ime.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Accept only the bounded PCM WAV format emitted by our recorder. */
object RecordedPcm {
    fun decode(wav: ByteArray): ShortArray {
        require(wav.size in 46..960_044 && (wav.size - 44) % 2 == 0) { "录音文件有误，请重试" }
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        fun ascii(offset: Int, text: String) = text.indices.all { wav[offset + it].toInt() == text[it].code }
        require(ascii(0, "RIFF") && ascii(8, "WAVEfmt ") && ascii(36, "data") &&
            header.getInt(4) == wav.size - 8 && header.getInt(16) == 16 &&
            header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1 &&
            header.getInt(24) == 16000 && header.getInt(28) == 32000 &&
            header.getShort(32).toInt() == 2 && header.getShort(34).toInt() == 16 &&
            header.getInt(40) == wav.size - 44) { "录音格式有误，请重试" }
        return ShortArray((wav.size - 44) / 2).also { pcm ->
            header.position(44); header.asShortBuffer().get(pcm)
        }
    }
}
