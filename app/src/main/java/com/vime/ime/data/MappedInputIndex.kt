package com.vime.ime.data

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue
import kotlin.math.ln

/** Public, immutable vocabulary. Offset indexes stay mapped instead of becoming heap maps. */
class MappedInputIndex private constructor(private val data: ByteBuffer) {
    data class Row(val word: String, val code: String, val weight: Int, val consumed: Int)
    val count: Int
    private val indexes: IntArray
    private val prefixStart: Int
    private val prefixCount: Int
    private val dataStart: Int
    private data class Request(val raw: String, val nine: Boolean, val limit: Int)
    private val cache = object : LinkedHashMap<Request, List<Row>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Request, List<Row>>?) = size > 64
    }

    init {
        require(data.limit() >= 64)
        require((0 until 8).map { data.get(it).toInt().toChar() }.joinToString("") == "VIMELEX1")
        require(data.getInt(8) == 1)
        count = data.getInt(12)
        require(count in 1..2_000_000)
        indexes = intArrayOf(data.getInt(16), data.getInt(20), data.getInt(24))
        prefixStart = data.getInt(28); dataStart = data.getInt(32); prefixCount = data.getInt(40)
        require(indexes.contentEquals(intArrayOf(64, 64 + count * 4, 64 + count * 8)))
        require(prefixStart == 64 + count * 12 && prefixCount in 1..100_000)
        require(dataStart.toLong() >= prefixStart.toLong() + prefixCount * 4L && dataStart < data.limit())
        require(data.getInt(36) == data.limit() && data.getInt(44) in 1..128 && data.getInt(48) == 160)
        require((52..60 step 4).all { data.getInt(it) == 0 })
    }

    private fun byte(at: Int) = data.get(at).toInt() and 255
    private fun wordSize(at: Int) = data.getShort(at + 2).toInt() and 65535
    private fun record(at: Int): Int {
        require(at >= dataStart && at <= data.limit() - 8)
        require(byte(at) > 0 && byte(at + 1) in 1..128 && byte(at + 1) <= byte(at))
        require(wordSize(at) > 0 && at.toLong() + 8 + byte(at) + wordSize(at) <= data.limit())
        require(data.getInt(at + 4) >= 0)
        return at
    }
    private fun at(mode: Int, position: Int) = record(data.getInt(indexes[mode] + position * 4))
    private fun weight(at: Int) = data.getInt(at + 4)
    private fun word(at: Int): String {
        val start = at + 8 + byte(at)
        return String(ByteArray(wordSize(at)) { data.get(start + it) }, Charsets.UTF_8)
    }
    private fun code(at: Int): String = buildString(byte(at + 1)) {
        for (i in 0 until byte(at)) { val c = byte(at + 8 + i); if (c != 32) append(c.toChar()) }
    }
    private fun row(at: Int, consumed: Int) = Row(word(at), code(at), weight(at), consumed)

    /** Compare a mapped code/initials/nine-key key without allocating decoded strings. */
    private fun compare(at: Int, input: String, mode: Int): Int {
        var position = 0
        var initial = true
        for (i in 0 until byte(at)) {
            var c = byte(at + 8 + i)
            if (c == 32) { initial = true; continue }
            if (mode == 2 && !initial) continue
            initial = false
            if (position >= input.length) return 1
            if (mode == 1) c = CandidateIndex.digit(c.toChar()).code
            val difference = c - input[position++].code
            if (difference != 0) return difference
        }
        return position - input.length
    }
    private fun lower(input: String, mode: Int): Int {
        var start = 0; var end = count
        while (start < end) {
            val mid = (start + end) ushr 1
            if (compare(at(mode, mid), input, mode) < 0) start = mid + 1 else end = mid
        }
        return start
    }
    private fun exact(input: String, mode: Int, visit: (Int) -> Boolean) {
        var pos = lower(input, mode)
        while (pos < count) {
            val offset = at(mode, pos++)
            if (compare(offset, input, mode) != 0 || !visit(offset)) break
        }
    }
    private fun prefixRecord(position: Int): Int {
        val offset = data.getInt(prefixStart + position * 4)
        require(offset >= prefixStart + prefixCount * 4 && offset <= dataStart - 4)
        val size = data.getShort(offset + 2).toInt() and 65535
        require(byte(offset) in 0..1 && byte(offset + 1) in 1..4 && size in 1..160)
        require(offset.toLong() + 4 + byte(offset + 1) + size * 4L <= dataStart)
        return offset
    }
    private fun comparePrefix(offset: Int, input: String, mode: Int): Int {
        if (byte(offset) != mode) return byte(offset) - mode
        val length = byte(offset + 1)
        for (i in 0 until minOf(length, input.length)) {
            val difference = byte(offset + 4 + i) - input[i].code
            if (difference != 0) return difference
        }
        return length - input.length
    }
    private fun cachedPrefix(input: String, mode: Int, visit: (Int) -> Boolean) {
        var start = 0; var end = prefixCount
        while (start < end) {
            val mid = (start + end) ushr 1
            if (comparePrefix(prefixRecord(mid), input, mode) < 0) start = mid + 1 else end = mid
        }
        if (start >= prefixCount) return
        val offset = prefixRecord(start)
        if (comparePrefix(offset, input, mode) != 0) return
        val size = data.getShort(offset + 2).toInt() and 65535
        for (i in 0 until size) if (!visit(record(data.getInt(offset + 4 + byte(offset + 1) + i * 4)))) break
    }
    private fun compareWord(first: Int, second: Int): Int {
        val a = first + 8 + byte(first); val b = second + 8 + byte(second)
        for (i in 0 until minOf(wordSize(first), wordSize(second))) {
            val d = byte(a + i) - byte(b + i)
            if (d != 0) return d
        }
        return wordSize(first) - wordSize(second)
    }
    // Best first: higher weight, shorter reading, then canonical UTF-8 word order.
    private val best = Comparator<Int> { a, b ->
        weight(b).compareTo(weight(a)).takeIf { it != 0 }
            ?: (byte(a + 1) - byte(b + 1)).takeIf { it != 0 } ?: compareWord(a, b)
    }
    private fun completion(input: String, mode: Int, limit: Int, visit: (Int) -> Boolean) {
        if (input.length <= if (mode == 0) 3 else 4) { cachedPrefix(input, mode, visit); return }
        val end = lower(input + "{", mode)
        val top = PriorityQueue<Int>(limit * 2, best.reversed())
        for (pos in lower(input, mode) until end) {
            val offset = at(mode, pos)
            if (top.size < limit * 2) top.add(offset)
            else if (best.compare(offset, top.peek()) < 0) { top.poll(); top.add(offset) }
        }
        for (offset in top.sortedWith(best)) if (!visit(offset)) break
    }

    fun query(raw: String, nine: Boolean = false, limit: Int = 12): List<Row> {
        if (raw.isEmpty() || raw.length > 128 || limit <= 0) return emptyList()
        val normalized = raw.lowercase()
        if (nine && normalized.any { it !in '2'..'9' }) return emptyList()
        if (!nine && normalized.any { it !in 'a'..'z' && it != '\'' }) return emptyList()
        val input = normalized.replace("'", "")
        if (input.isEmpty()) return emptyList()
        val request = Request(normalized, nine, limit.coerceAtMost(100))
        synchronized(cache) { cache[request]?.let { return it } }
        val mode = if (nine) 1 else 0
        val found = LinkedHashMap<String, Row>()
        fun append(offset: Int, consumed: Int = raw.length): Boolean {
            if (found.size >= request.limit) return false
            val next = row(offset, consumed)
            found.putIfAbsent(next.word, next)
            return true
        }
        exact(input, mode) { append(it) }
        if (found.size < request.limit) completion(input, mode, request.limit) { append(it) }
        if (found.isEmpty() && !nine) exact(input, 2) { append(it) }
        if (found.isEmpty()) {
            assemble(input, mode)?.let { found[it] = Row(it, input, 0, raw.length) }
            for (end in input.length - 1 downTo 1) {
                var consumed = 0; var letters = 0
                while (consumed < raw.length && letters < end) { if (raw[consumed++] != '\'') letters++ }
                while (consumed < raw.length && raw[consumed] == '\'') consumed++
                exact(input.take(end), mode) { append(it, consumed) }
                if (found.isNotEmpty()) break
            }
        }
        val result = found.values.toList()
        synchronized(cache) { cache[request] = result }
        return result
    }
    private fun assemble(input: String, mode: Int): String? {
        if (input.length !in 4..48) return null
        val scores = DoubleArray(input.length + 1) { Double.NEGATIVE_INFINITY }
        val phrases = arrayOfNulls<String>(input.length + 1)
        val segments = HashMap<String, Int?>()
        scores[0] = 0.0; phrases[0] = ""
        for (end in 1..input.length) for (start in maxOf(0, end - 18) until end) {
            if (phrases[start] == null) continue
            val typed = input.substring(start, end)
            val offset = segments.getOrPut(typed) {
                val position = lower(typed, mode)
                if (position < count) at(mode, position).takeIf { compare(it, typed, mode) == 0 } else null
            } ?: continue
            val score = scores[start] + ln(if (weight(offset) > 0) weight(offset).toDouble() else .25) - 14.0
            if (score > scores[end]) { scores[end] = score; phrases[end] = phrases[start] + word(offset) }
        }
        return phrases[input.length]
    }
    companion object {
        fun open(file: File): MappedInputIndex = FileInputStream(file).channel.use {
            require(it.size() in 64..Int.MAX_VALUE.toLong())
            MappedInputIndex(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()).order(ByteOrder.LITTLE_ENDIAN))
        }
        internal fun fromBytes(bytes: ByteArray) = MappedInputIndex(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }
}
