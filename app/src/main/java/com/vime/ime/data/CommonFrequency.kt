package com.vime.ime.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A compact binary lookup; no word hash map or disk work in the keystroke path. */
class CommonFrequency(bytes: ByteArray) {
    private val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val count: Int
    init {
        require(bytes.size >= 8 && String(bytes, 0, 4, Charsets.US_ASCII) == "CFQ1")
        count = data.getInt(4); require(count in 1..500_000 && 8L + count * 4L < bytes.size)
        var previous = 8 + count * 4
        for (i in 0 until count) {
            val offset = data.getInt(8 + i * 4)
            require(offset == previous && offset <= bytes.size - 6)
            val length = data.getShort(offset).toInt() and 65535
            require(length in 6..48 && offset + 6L + length <= bytes.size && data.getInt(offset + 2) >= 5)
            previous = offset + 6 + length
        }
        require(previous == bytes.size)
    }
    fun get(word: String): Int {
        if (word.length !in 2..16) return 0
        val bytes = word.toByteArray(Charsets.UTF_8)
        var left = 0; var right = count
        while (left < right) {
            val mid = (left + right) ushr 1
            val at = data.getInt(8 + mid * 4)
            val length = data.getShort(at).toInt() and 65535
            var difference = 0
            for (i in 0 until minOf(length, bytes.size)) {
                difference = (data.get(at + 6 + i).toInt() and 255) - (bytes[i].toInt() and 255)
                if (difference != 0) break
            }
            if (difference == 0) difference = length - bytes.size
            if (difference == 0) return data.getInt(at + 2)
            if (difference < 0) left = mid + 1 else right = mid
        }
        return 0
    }
}
