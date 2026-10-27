package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/**
 * Conservative BRR placement, not an unused instrument/SRCN detector or export permission.
 * Keep every uploaded byte, including zero/FF padding, boot data, sibling arrangements and
 * retained imports. Only gaps in lower sequence RAM are candidates; no engine/echo/table
 * space, old recording footprints or high-RAM tails are reclaimed.
 */
internal object MusicSamplePlacement {
    const val START = MusicSequenceBudget.SEQUENCE_ALLOC_MIN
    const val END = MusicSequenceBudget.LOW_SEQUENCE_MAX

    data class Range(val start: Int, val endExclusive: Int) {
        val size get() = endExclusive - start
    }
    data class Layout(val gaps: List<Range>) {
        val largestBytes get() = gaps.maxOfOrNull { it.size } ?: 0
        val totalBytes get() = gaps.sumOf { it.size }

        /** A recording must fit contiguously. Never truncate or split a BRR stream. */
        fun place(sample: SampleAuthoring.Encoded): Range {
            validate(sample)
            val gap = gaps.firstOrNull { it.size >= sample.brr.size }
                ?: error("Needs ${sample.brr.size} B · largest bank gap $largestBytes B. Trim the recording or choose another bank.")
            return Range(gap.start, gap.start + sample.brr.size)
        }
    }

    fun inspect(ram: ByteArray, uploads: List<SpcData.TransferBlock>): Layout {
        require(ram.size == 65536) { "Expected complete SPC RAM" }
        val occupied = BooleanArray(END - START)
        fun protect(start: Int, end: Int) {
            for (at in maxOf(START, start) until minOf(END, end)) occupied[at - START] = true
        }
        for (block in uploads) {
            require(block.destAddr in 0..65535 && block.data.size <= 65536 - block.destAddr) { "Sound upload exceeds SPC RAM" }
            protect(block.destAddr, block.destAddr + block.data.size)
        }
        // Include both start and loop chains, even for unreferenced directory entries.
        // An invalid unterminated chain conservatively consumes its remaining RAM span.
        for (range in MusicSequenceBudget.sampleRanges(ram)) protect(range.start, range.endExclusive)
        val gaps = buildList {
            var at = 0
            while (at < occupied.size) {
                if (occupied[at]) { at++; continue }
                val start = at
                while (at < occupied.size && !occupied[at]) at++
                add(Range(START + start, START + at))
            }
        }
        return Layout(gaps)
    }

    private fun validate(sample: SampleAuthoring.Encoded) {
        require(sample.brr.isNotEmpty() && sample.brr.size % 9 == 0 && sample.brr.size / 9 * 16 == sample.pcm.size) {
            "Recording must contain complete BRR blocks"
        }
        val last = sample.brr.size - 9
        for (at in sample.brr.indices step 9) require((sample.brr[at].toInt() and 1 != 0) == (at == last)) {
            "Recording must end at its last BRR block"
        }
        require(sample.loopFrame == null || sample.loopFrame in sample.pcm.indices && sample.loopFrame % 16 == 0) {
            "Loop must lie on a BRR block inside the recording"
        }
        require((sample.brr[last].toInt() and 2 != 0) == (sample.loopFrame != null)) { "Recording loop flag does not match its loop point" }
    }
}

/** Detached bank-aware audition. No project mutation, new SRCN ownership or ROM writes. */
internal class SampleBankPreview private constructor(
    val bankName: String,
    val soundName: String,
    val layout: MusicSamplePlacement.Layout,
    private val ram: ByteArray,
    private val instrument: NspcRenderer.InstrumentEntry,
    private val note: NspcSequence.Note,
) {
    data class Target(val songSet: Int, val note: NspcSequence.Note, val bankName: String, val soundName: String, val project: SmEditProject)

    fun build(sample: SampleAuthoring.Encoded): InstrumentAudition.Preview {
        val allocation = layout.place(sample)
        val result = ram.copyOf()
        sample.brr.copyInto(result, allocation.start)
        // This replaces one directory entry ONLY in disposable audition RAM. It makes
        // no assertion that a native slot is unused, or that replacing it is export-safe.
        val srcn = instrument.srcn.takeIf { it in 0 until 40 } ?: 0
        val loop = allocation.start + (sample.loopByteOffset ?: 0)
        byteArrayOf(allocation.start.toByte(), (allocation.start ushr 8).toByte(), loop.toByte(), (loop ushr 8).toByte())
            .copyInto(result, 0x6D00 + srcn * 4)
        return InstrumentAudition.build(result, instrument.copy(srcn = srcn), note)
    }

    companion object {
        fun load(parser: RomParser, target: Target): SampleBankPreview {
            require(target.songSet in SpcData.TRANSFER_SONG_SETS && target.songSet > 0) { "Choose a native music bank" }
            require(target.note.instrument in 0 until 42) { "Choose an available sound" }
            val resolved = MusicBankResolver.resolve(parser, target.project, target.songSet)
            val boot = SpcData.buildInitialSpcRam(parser)
            require((0x1500 until 0x5800).all { resolved.ram[it] == boot[it] }) {
                "Bank tryout requires the original SPC engine"
            }
            val uploads = SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000)) +
                SpcData.findSongSetTransferData(parser, target.songSet) + resolved.blocks +
                target.project.musicEdits.values.filter { it.songSet == target.songSet }.flatMap { edit ->
                    edit.nativePayload?.let(MusicEditConversion::toTransferBlocks).orEmpty()
                }
            // Keep inherited boot recording ranges as well as the resolved bank's ranges.
            val protected = uploads + MusicSequenceBudget.sampleRanges(boot).map { range ->
                SpcData.TransferBlock(range.start, boot.copyOfRange(range.start, range.endExclusive))
            }
            return SampleBankPreview(target.bankName, target.soundName, MusicSamplePlacement.inspect(resolved.ram, protected),
                resolved.ram.copyOf(), NspcRenderer.readInstrumentTable(resolved.ram)[target.note.instrument], target.note.copy())
        }
    }
}
