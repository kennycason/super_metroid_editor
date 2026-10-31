package com.supermetroid.editor.rom

/** Lossless byte-stream factoring into the engine's single-level EF subroutines. */
internal object NspcPhrasePacking {
    data class Fixup(val offset: Int, val phrase: Int)
    data class Stream(val bytes: ByteArray, val fixups: List<Fixup> = emptyList())
    data class Plan(val streams: List<Stream?>, val phrases: List<ByteArray>, val flatBytes: Int) {
        val size = streams.sumOf { it?.bytes?.size ?: 0 } + phrases.sumOf { it.size }
        val calls = streams.sumOf { it?.fixups?.size ?: 0 }
        fun render(dataStart: Int): List<ByteArray?> {
            var at = dataStart + streams.sumOf { it?.bytes?.size ?: 0 }
            val addresses = phrases.map { bytes -> at.also { at += bytes.size } }
            return streams.map { stream -> stream?.bytes?.copyOf()?.also { bytes ->
                for (fixup in stream.fixups) {
                    val address = addresses[fixup.phrase]
                    require(address in 0..0xFFFF) { "Native phrase address exceeds SPC RAM" }
                    bytes[fixup.offset] = address.toByte(); bytes[fixup.offset + 1] = (address ushr 8).toByte()
                }
            } }
        }
    }
    private data class Token(val id: Int, val bytes: ByteArray)
    private data class Call(val phrase: Int, val repeats: Int, val tokens: Int)
    private class Voice(val tokens: List<Token>) {
        val covered = BooleanArray(tokens.size)
        val calls = mutableMapOf<Int, Call>()
        val hash = LongArray(tokens.size + 1).also { h -> for (i in tokens.indices) h[i + 1] = h[i] * 1_000_003L + tokens[i].id }
        val bytes = IntArray(tokens.size + 1).also { b -> for (i in tokens.indices) b[i + 1] = b[i] + tokens[i].bytes.size }
    }
    private data class Position(val voice: Int, val start: Int)
    private data class Run(val voice: Int, val start: Int, val repeats: Int)
    private data class Candidate(val length: Int, val body: ByteArray, val runs: List<Run>, val saving: Int)

    fun flat(channels: List<ByteArray?>) = Plan(channels.map { it?.let(::Stream) }, emptyList(), channels.sumOf { it?.size ?: 0 })

    fun pack(channels: List<ByteArray?>): Plan {
        val dictionary = mutableMapOf<List<Byte>, Int>()
        val voices = channels.map { bytes -> bytes?.let { tokenize(it, dictionary) }?.let(::Voice) }
        // Bound work for pathological editable input; the ordinary checked encoder remains available.
        if (voices.sumOf { it?.tokens?.size ?: 0 } > 100_000) return flat(channels)
        val lengths = intArrayOf(256, 192, 128, 96, 64, 48, 32, 24, 16, 12, 10, 8, 6, 4, 3, 2, 1)
        val powers = LongArray(257).also { it[0] = 1; for (i in 1..256) it[i] = it[i - 1] * 1_000_003L }
        val phrases = mutableListOf<ByteArray>()
        repeat(24) {
            var best: Candidate? = null
            val occupied = voices.map { voice -> voice?.let { IntArray(it.tokens.size + 1).also { sums ->
                for (i in it.tokens.indices) sums[i + 1] = sums[i] + if (it.covered[i]) 1 else 0
            } } }
            for (length in lengths) {
                val matches = mutableMapOf<Long, MutableList<Position>>()
                for ((index, voice) in voices.withIndex()) {
                    if (voice == null || voice.tokens.size < length) continue
                    val used = occupied[index]!!
                    for (start in 0..voice.tokens.size - length) {
                        if (used[start + length] != used[start]) continue
                        val hash = voice.hash[start + length] - voice.hash[start] * powers[length]
                        matches.getOrPut(hash) { mutableListOf() } += Position(index, start)
                    }
                }
                for (positions in matches.values) {
                    if (positions.size < 2) continue
                    val first = positions.first(); val template = voices[first.voice]!!
                    val bodySize = template.bytes[first.start + length] - template.bytes[first.start]
                    val runs = mutableListOf<Run>()
                    var last: Run? = null
                    for (position in positions) {
                        val previous = last
                        if (previous != null && position.voice == previous.voice && position.start < previous.start + previous.repeats * length) continue
                        val voice = voices[position.voice]!!
                        // Hashes find candidates; exact token equality decides whether sharing is safe.
                        if ((0 until length).any { voice.tokens[position.start + it].id != template.tokens[first.start + it].id }) continue
                        if (previous != null && position.voice == previous.voice && position.start == previous.start + previous.repeats * length) {
                            last = previous.copy(repeats = previous.repeats + 1); runs[runs.lastIndex] = last
                        } else { last = Run(position.voice, position.start, 1); runs += last }
                    }
                    val profitable = runs.filter { bodySize.toLong() * it.repeats > 4L * ((it.repeats + 254) / 255) }
                    val saving = profitable.sumOf { bodySize * it.repeats - 4 * ((it.repeats + 254) / 255) } - bodySize - 1
                    if (saving > (best?.saving ?: 0)) {
                        val body = template.tokens.subList(first.start, first.start + length).flatMap { it.bytes.asList() }.toByteArray()
                        best = Candidate(length, body, profitable, saving)
                    }
                }
            }
            val candidate = best ?: return finish(channels, voices, phrases)
            val phrase = phrases.size; phrases += candidate.body + byteArrayOf(0)
            for (run in candidate.runs) {
                val voice = voices[run.voice]!!
                voice.calls[run.start] = Call(phrase, run.repeats, candidate.length * run.repeats)
                for (i in run.start until run.start + candidate.length * run.repeats) voice.covered[i] = true
            }
        }
        return finish(channels, voices, phrases)
    }

    private fun finish(channels: List<ByteArray?>, voices: List<Voice?>, phrases: List<ByteArray>): Plan {
        val streams = channels.mapIndexed { index, raw ->
            if (raw == null) return@mapIndexed null
            val voice = voices[index] ?: return@mapIndexed Stream(raw)
            val out = mutableListOf<Byte>(); val fixups = mutableListOf<Fixup>()
            var at = 0
            while (at < voice.tokens.size) {
                val call = voice.calls[at]
                if (call == null) { out += voice.tokens[at].bytes.asList(); at++ }
                else {
                    var count = call.repeats
                    while (count > 0) {
                        val repeats = minOf(255, count)
                        out += 0xEF.toByte(); fixups += Fixup(out.size, call.phrase)
                        out += listOf(0, 0, repeats.toByte()); count -= repeats
                    }
                    at += call.tokens
                }
            }
            out += 0
            Stream(out.toByteArray(), fixups)
        }
        return Plan(streams, phrases.toList(), channels.sumOf { it?.size ?: 0 }).also { check(it.size <= it.flatBytes) }
    }

    /** Keep a length/qv prefix with its note, and never share END or nested calls. */
    private fun tokenize(bytes: ByteArray, dictionary: MutableMap<List<Byte>, Int>): List<Token>? {
        if (bytes.isEmpty() || bytes.last() != 0.toByte()) return null
        val tokens = mutableListOf<Token>()
        var at = 0
        fun byte(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255) ?: -1
        while (at < bytes.lastIndex) {
            val start = at
            val opcode = byte(at++)
            when (opcode) {
                in 1..127 -> {
                    if (byte(at) in 0..127) at++
                    if (byte(at) !in 0x80..0xDF) return null
                    at++
                }
                in 0x80..0xDF -> Unit
                in 0xE0..0xFB -> {
                    if (opcode == 0xEF) return null
                    at += NspcSequence.commandParameterCount(opcode) ?: return null
                }
                else -> return null
            }
            if (at > bytes.lastIndex) return null
            val data = bytes.copyOfRange(start, at)
            val id = dictionary.getOrPut(data.asList()) { dictionary.size + 1 }
            tokens += Token(id, data)
        }
        return tokens
    }
}
