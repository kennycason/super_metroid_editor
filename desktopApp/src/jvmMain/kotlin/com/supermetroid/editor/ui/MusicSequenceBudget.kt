package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

internal object MusicSequenceBudget {
    const val SONG_TABLE_BASE = 0x581E
    const val SEQUENCE_DATA_MIN = 0x5820
    const val SEQUENCE_ALLOC_MIN = 0x582C
    const val LOW_SEQUENCE_MAX = 0x6C00
    const val SEQUENCE_EXPORT_MAX = RomConstants.SPC_RAM_SIZE

    private val COMMAND_PARAM_COUNT = mapOf(
        0xE0 to 1,
        0xE1 to 2,
        0xE2 to 3,
        0xE3 to 0,
        0xE4 to 1,
        0xE5 to 2,
        0xE6 to 0,
        0xE7 to 1,
        0xE8 to 1,
        0xE9 to 2,
        0xEA to 1,
        0xEB to 3,
        0xEC to 0,
        0xED to 1,
        0xEE to 2,
        0xEF to 3,
        0xF0 to 1,
        0xF1 to 3,
        0xF2 to 3,
        0xF3 to 0,
        0xF4 to 1,
        0xF5 to 3,
        0xF6 to 0,
        0xF7 to 3,
        0xF8 to 3,
        0xF9 to 3,
        0xFA to 1,
        0xFB to 2,
        0xFC to 0,
        0xFD to 0,
        0xFE to 0,
    )

    data class SpcRange(
        val start: Int,
        val endExclusive: Int
    ) {
        val size get() = endExclusive - start
    }

    data class FitResult(
        val song: NspcSequence.Song,
        val originalBytes: Int,
        val encodedBytes: Int,
        val budgetBytes: Int,
        val cutoffTick: Int,
        val removedNotes: Int,
        val removedCommands: Int
    ) {
        val trimmed get() = removedNotes > 0 || removedCommands > 0
    }

    data class TrackBudget(
        val trackBytes: Int,
        val relocationBytes: Int
    )

    data class RelocatedSequencePatch(
        val song: NspcSequence.Song,
        val fit: FitResult,
        val allocation: SpcRange,
        val writes: Map<Int, ByteArray>
    )

    fun budgetForTrack(romParser: RomParser, songSet: Int, playIndex: Int): Int {
        return trackBudgetForTrack(romParser, songSet, playIndex).trackBytes
    }

    fun trackBudgetForTrack(romParser: RomParser, songSet: Int, playIndex: Int): TrackBudget {
        val baseBlocks = SpcData.parseTransferBlocks(romParser.getRomData(), romParser.snesToPc(0xCF8000))
        val songBlocks = SpcData.findSongSetTransferData(romParser, songSet)
        val spcRam = SpcData.buildInitialSpcRam(romParser).also {
            SpcData.applyTransferBlocks(it, songBlocks)
        }
        val knownPlayIndexes = SpcData.KNOWN_TRACKS
            .filter { it.songSet == songSet }
            .mapTo(mutableSetOf()) { it.playIndex }
        knownPlayIndexes += playIndex
        val protectedPlayIndexes = knownPlayIndexes - playIndex
        val occupied = buildProtectedSpcOccupancy(baseBlocks, songBlocks, spcRam, protectedPlayIndexes)
        val originalSequenceBytes = originalSequenceBytes(spcRam, playIndex)
        return TrackBudget(
            trackBytes = originalSequenceBytes,
            relocationBytes = largestAvailableRange(occupied)?.size ?: 0
        )
    }

    fun originalSequenceBytes(spcRam: ByteArray, playIndex: Int): Int {
        return buildOriginalSequenceOccupancy(spcRam, setOf(playIndex)).sumOf { it.size }
    }

    fun buildProtectedSpcOccupancy(
        baseBlocks: List<SpcData.TransferBlock>,
        songBlocks: List<SpcData.TransferBlock>,
        spcRam: ByteArray,
        protectedPlayIndexes: Set<Int>
    ): List<SpcRange> {
        val ranges = mutableListOf<SpcRange>()
        ranges += baseBlocks.flatMap { protectedSongBlockRanges(it) }
        ranges += songBlocks.flatMap { protectedSongBlockRanges(it) }
        ranges += buildOriginalSequenceOccupancy(spcRam, protectedPlayIndexes)
        return coalesceSpcRanges(ranges)
    }

    fun largestAvailableRange(occupiedRanges: List<SpcRange>): SpcRange? {
        var cursor = SEQUENCE_ALLOC_MIN
        var best: SpcRange? = null
        fun offer(start: Int, end: Int) {
            if (end > start) {
                val range = SpcRange(start, end)
                if (best == null || range.size > best!!.size) best = range
            }
        }

        for (range in coalesceSpcRanges(occupiedRanges)) {
            offer(cursor, range.start)
            cursor = maxOf(cursor, range.endExclusive)
        }
        offer(cursor, SEQUENCE_EXPORT_MAX)
        return best
    }

    fun allocateRange(occupiedRanges: List<SpcRange>, size: Int, key: String): SpcRange {
        require(size > 0) { "music edit $key produced an empty encoded sequence" }
        require(size <= SEQUENCE_EXPORT_MAX - SEQUENCE_DATA_MIN) {
            "music edit $key encoded sequence is too large for SPC sequence RAM ($size bytes)"
        }

        var cursor = SEQUENCE_ALLOC_MIN
        for (range in coalesceSpcRanges(occupiedRanges)) {
            if (cursor + size <= range.start) return SpcRange(cursor, cursor + size)
            cursor = maxOf(cursor, range.endExclusive)
        }
        if (cursor + size <= SEQUENCE_EXPORT_MAX) return SpcRange(cursor, cursor + size)

        error(
            "not enough free SPC sequence RAM for music edit $key " +
                "($size bytes required below 0x${SEQUENCE_EXPORT_MAX.toString(16)})"
        )
    }

    fun encodeRelocated(
        song: NspcSequence.Song,
        playIndex: Int,
        spcRam: ByteArray,
        occupiedRanges: List<SpcRange>,
        key: String
    ): RelocatedSequencePatch {
        val availableRange = largestAvailableRange(occupiedRanges)
            ?: error("no free SPC sequence RAM for music edit $key")
        val fit = fitSongToEncodedBudget(song, availableRange.size)
        val allocation = allocateRange(
            occupiedRanges = occupiedRanges,
            size = fit.encodedBytes,
            key = key
        )
        val writes = NspcSequence.encode(
            song = fit.song,
            playIndex = playIndex,
            spcRam = spcRam,
            failOnOverflow = true,
            conductorAddrOverride = allocation.start,
            sequenceEndAddr = allocation.endExclusive
        )
        validateRelocatedWrites(playIndex, allocation, writes, key)
        return RelocatedSequencePatch(
            song = fit.song,
            fit = fit,
            allocation = allocation,
            writes = writes
        )
    }

    fun validateRelocatedWrites(
        playIndex: Int,
        allocation: SpcRange,
        writes: Map<Int, ByteArray>,
        key: String
    ) {
        val songTableEntry = SONG_TABLE_BASE + playIndex * 2
        for ((addr, data) in writes) {
            val dest = addr and 0xFFFF
            val endExclusive = dest + data.size
            require(data.isNotEmpty()) { "music edit $key produced an empty sequence write at 0x${dest.toString(16)}" }
            if (dest == songTableEntry && data.size == 2) continue
            require(dest >= allocation.start && endExclusive <= allocation.endExclusive) {
                "music edit $key re-encode escaped relocated allocation " +
                    "0x${allocation.start.toString(16).padStart(4, '0')}.." +
                    "0x${(allocation.endExclusive - 1).toString(16).padStart(4, '0')} with write " +
                    "0x${dest.toString(16).padStart(4, '0')}.." +
                    "0x${(endExclusive - 1).toString(16).padStart(4, '0')}"
            }
        }
    }

    fun fitSongToEncodedBudget(song: NspcSequence.Song, budgetBytes: Int): FitResult {
        require(budgetBytes > 0) { "music sequence byte budget must be positive" }
        val originalBytes = NspcSequence.encodedSequenceSize(song)
        if (originalBytes <= budgetBytes) {
            return FitResult(
                song = song,
                originalBytes = originalBytes,
                encodedBytes = originalBytes,
                budgetBytes = budgetBytes,
                cutoffTick = song.totalTicks,
                removedNotes = 0,
                removedCommands = 0
            )
        }

        val candidateTicks = buildCutoffCandidates(song)
        var lo = 0
        var hi = candidateTicks.lastIndex
        var bestSong: NspcSequence.Song? = null
        var bestBytes = Int.MAX_VALUE
        var bestCutoff = 0

        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cutoff = candidateTicks[mid]
            val truncated = truncateSongAtTick(song, cutoff)
            val bytes = NspcSequence.encodedSequenceSize(truncated)
            if (bytes <= budgetBytes) {
                bestSong = truncated
                bestBytes = bytes
                bestCutoff = cutoff
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val fitted = bestSong ?: truncateSongAtTick(song, 0)
        val fittedBytes = if (bestSong != null) bestBytes else NspcSequence.encodedSequenceSize(fitted)
        require(fittedBytes <= budgetBytes) {
            "music edit cannot fit even after trimming all note data ($fittedBytes > $budgetBytes bytes)"
        }

        val originalNoteCount = song.channels.sumOf { it.notes.size }
        val originalCommandCount = song.channels.sumOf { it.commands.size }
        val fittedNoteCount = fitted.channels.sumOf { it.notes.size }
        val fittedCommandCount = fitted.channels.sumOf { it.commands.size }
        fitted.isModified = true
        return FitResult(
            song = fitted,
            originalBytes = originalBytes,
            encodedBytes = fittedBytes,
            budgetBytes = budgetBytes,
            cutoffTick = bestCutoff,
            removedNotes = originalNoteCount - fittedNoteCount,
            removedCommands = originalCommandCount - fittedCommandCount
        )
    }

    private fun buildCutoffCandidates(song: NspcSequence.Song): List<Int> {
        val ticks = sortedSetOf(0, song.totalTicks)
        for (channel in song.channels) {
            for (note in channel.notes) {
                ticks += note.tick.coerceAtLeast(0)
                ticks += note.endTick.coerceAtLeast(0)
            }
            for (command in channel.commands) {
                ticks += command.tick.coerceAtLeast(0)
                ticks += (command.tick + 1).coerceAtLeast(0)
            }
        }
        return ticks.toList()
    }

    private fun truncateSongAtTick(song: NspcSequence.Song, cutoffTick: Int): NspcSequence.Song {
        val out = NspcSequence.Song(tempo = song.tempo, title = song.title, isModified = song.isModified)
        for (ch in 0 until 8) {
            for (note in song.channels[ch].notes) {
                if (note.tick >= cutoffTick) continue
                val clippedDuration = minOf(note.duration, cutoffTick - note.tick)
                if (clippedDuration <= 0) continue
                out.channels[ch].notes += note.copy(duration = clippedDuration)
            }
            for (command in song.channels[ch].commands) {
                if (command.tick < cutoffTick) {
                    out.channels[ch].commands += command.copy(params = command.params.copyOf())
                }
            }
        }
        return out
    }

    private fun transferBlockRange(block: SpcData.TransferBlock): SpcRange {
        val start = block.destAddr and 0xFFFF
        return SpcRange(start, start + block.data.size)
    }

    private fun protectedSongBlockRanges(block: SpcData.TransferBlock): List<SpcRange> {
        val range = transferBlockRange(block)
        val ranges = mutableListOf<SpcRange>()
        if (range.start < SEQUENCE_ALLOC_MIN) {
            ranges += SpcRange(range.start, minOf(range.endExclusive, SEQUENCE_ALLOC_MIN))
        }
        if (range.endExclusive > LOW_SEQUENCE_MAX) {
            ranges += SpcRange(maxOf(range.start, LOW_SEQUENCE_MAX), range.endExclusive)
        }
        if (range.start >= LOW_SEQUENCE_MAX || range.endExclusive <= SEQUENCE_ALLOC_MIN) {
            ranges += range
        }
        return ranges
    }

    private fun buildOriginalSequenceOccupancy(spcRam: ByteArray, playIndexes: Set<Int>): List<SpcRange> {
        val ranges = mutableListOf<SpcRange>()
        for (playIndex in playIndexes.sorted()) {
            ranges += collectOriginalSequenceRanges(spcRam, playIndex)
        }
        return coalesceSpcRanges(ranges)
    }

    private fun collectOriginalSequenceRanges(spcRam: ByteArray, playIndex: Int): List<SpcRange> {
        val ranges = mutableListOf<SpcRange>()
        val visitedChannelStreams = mutableSetOf<Int>()
        val conductorAddr = readSpcU16(spcRam, SONG_TABLE_BASE + playIndex * 2)
        if (!isMusicSequenceDataAddr(conductorAddr)) return emptyList()

        var conductorPtr = conductorAddr
        repeat(16) {
            val wordAddr = conductorPtr
            val blockTableAddr = readSpcU16(spcRam, conductorPtr)
            conductorPtr += 2
            ranges += SpcRange(wordAddr, conductorPtr)

            when {
                blockTableAddr == 0 -> return coalesceSpcRanges(ranges)
                blockTableAddr == 0x0080 || blockTableAddr == 0x0081 -> return@repeat
                blockTableAddr < 0x0100 -> {
                    val loopTargetAddr = conductorPtr
                    conductorPtr += 2
                    ranges += SpcRange(loopTargetAddr, conductorPtr)
                }
                !isMusicSequenceDataAddr(blockTableAddr) -> return@repeat
                else -> {
                    ranges += SpcRange(blockTableAddr, blockTableAddr + 16)
                    for (ch in 0 until 8) {
                        val channelAddr = readSpcU16(spcRam, blockTableAddr + ch * 2)
                        ranges += collectChannelStreamRanges(spcRam, channelAddr, visitedChannelStreams)
                    }
                }
            }
        }
        return coalesceSpcRanges(ranges)
    }

    private fun collectChannelStreamRanges(spcRam: ByteArray, start: Int, visited: MutableSet<Int>): List<SpcRange> {
        if (!isMusicSequenceDataAddr(start) || !visited.add(start)) return emptyList()

        val ranges = mutableListOf<SpcRange>()
        var ptr = start
        var safety = 0
        while (ptr in SEQUENCE_DATA_MIN until SEQUENCE_EXPORT_MAX && safety++ < 20000) {
            val b = spcRam[ptr].toInt() and 0xFF
            ptr++
            when {
                b == 0x00 -> {
                    ranges += SpcRange(start, ptr)
                    return coalesceSpcRanges(ranges)
                }
                b in 0x01..0x7F -> {
                    if (ptr < SEQUENCE_EXPORT_MAX) {
                        val next = spcRam[ptr].toInt() and 0xFF
                        if (next < 0x80) ptr++
                    }
                }
                b == 0xEF -> {
                    val subroutineAddr = readSpcU16(spcRam, ptr)
                    ptr += 3
                    ranges += collectChannelStreamRanges(spcRam, subroutineAddr, visited)
                }
                b >= 0xE0 -> {
                    ptr += COMMAND_PARAM_COUNT[b] ?: 0
                }
            }
        }
        ranges += SpcRange(start, ptr.coerceAtMost(SEQUENCE_EXPORT_MAX))
        return coalesceSpcRanges(ranges)
    }

    fun coalesceSpcRanges(ranges: List<SpcRange>): List<SpcRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges
            .mapNotNull { range ->
                val start = range.start.coerceAtLeast(SEQUENCE_DATA_MIN)
                val endExclusive = range.endExclusive.coerceAtMost(SEQUENCE_EXPORT_MAX)
                if (start < endExclusive) SpcRange(start, endExclusive) else null
            }
            .sortedWith(compareBy<SpcRange> { it.start }.thenBy { it.endExclusive })

        val out = mutableListOf<SpcRange>()
        for (range in sorted) {
            val last = out.lastOrNull()
            if (last == null || range.start > last.endExclusive) {
                out += range
            } else if (range.endExclusive > last.endExclusive) {
                out[out.lastIndex] = SpcRange(last.start, range.endExclusive)
            }
        }
        return out
    }

    private fun readSpcU16(spcRam: ByteArray, addr: Int): Int {
        if (addr < 0 || addr + 1 >= spcRam.size) return 0
        return (spcRam[addr].toInt() and 0xFF) or ((spcRam[addr + 1].toInt() and 0xFF) shl 8)
    }

    private fun isMusicSequenceDataAddr(addr: Int): Boolean =
        addr in SEQUENCE_DATA_MIN until SEQUENCE_EXPORT_MAX
}
