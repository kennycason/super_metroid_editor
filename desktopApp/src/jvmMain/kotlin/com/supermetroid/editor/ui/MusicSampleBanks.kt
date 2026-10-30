package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.MusicSampleEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import java.util.Base64

/** Explicit ownership of directory entries $28..$3F and BRR bytes in verified lower-RAM gaps. */
internal object MusicSampleBanks {
    data class Application(
        val target: SampleBankPreview.Target,
        val bank: MusicInstrumentBankEdit,
        val srcn: Int,
        val bytes: Int,
        val remainingBytes: Int,
        val largestGapBytes: Int,
        val reused: Boolean,
        val changed: Boolean,
    )

    fun decode(sample: MusicSampleEdit): ByteArray {
        require(sample.srcn in SpcData.STOCK_SAMPLE_COUNT until SpcData.SAMPLE_COUNT) { "Invalid project sample directory entry" }
        require(sample.name.isNotBlank() && sample.name.length <= 120) { "Sample name must contain 1–120 characters" }
        require(sample.brrBase64.length <= 24576) { "Project sample exceeds the sample memory limit" }
        val bytes = Base64.getDecoder().decode(sample.brrBase64)
        require(bytes.size <= SampleAuthoring.MAX_FRAMES / 16 * 9) { "Project sample is too long" }
        val loopOffset = sample.loopByteOffset
        require(loopOffset == null || loopOffset >= 0 && loopOffset % 9 == 0) { "Invalid project sample loop" }
        MusicSamplePlacement.validate(SampleAuthoring.Encoded(bytes, ShortArray(bytes.size / 9 * 16), sample.loopByteOffset?.let { it / 9 * 16 }))
        require(sample.startAddr >= MusicSamplePlacement.START && bytes.size <= MusicSamplePlacement.END - sample.startAddr) { "Project sample exceeds safe sample memory" }
        return bytes
    }

    fun resourceId(sample: MusicSampleEdit) = MusicSampleCatalog.resourceId(decode(sample), sample.loopByteOffset?.let { it / 9 * 16 })

    /** Validation uses the retained source, before any project sample is written into it. */
    fun writes(parser: RomParser, project: SmEditProject, songSet: Int, source: ByteArray, sources: Map<Int, ByteArray>): Map<Int, ByteArray> {
        val samples = project.musicInstrumentBanks[MusicInstrumentBanks.key(songSet)]?.samples.orEmpty()
        if (samples.isEmpty()) return emptyMap()
        val boot = SpcData.buildInitialSpcRam(parser)
        require((0x1500 until 0x5800).all { source[it] == boot[it] }) { "Custom samples require the original SPC engine" }
        val uploads = retainedUploads(parser, project, songSet, sources)
        val layout = MusicSamplePlacement.inspect(source, uploads)
        val native = NspcRenderer.readInstrumentTable(source)
        val occupied = mutableListOf<MusicSamplePlacement.Range>()
        val result = sortedMapOf<Int, ByteArray>()
        for ((slot, sample) in samples.toSortedMap()) {
            require(slot == sample.srcn) { "Project sample key does not match its directory entry" }
            val bytes = decode(sample)
            require(directoryAvailable(slot, source, uploads, native)) { "Project sample directory #$slot overlaps retained bank data" }
            val end = sample.startAddr + bytes.size
            require(layout.gaps.any { sample.startAddr >= it.start && end <= it.endExclusive }) { "Project sample overlaps retained music or sample data" }
            require(occupied.none { it.start < end && it.endExclusive > sample.startAddr }) { "Project samples overlap each other" }
            occupied += MusicSamplePlacement.Range(sample.startAddr, end)
            val loop = sample.startAddr + (sample.loopByteOffset ?: 0)
            result[sample.startAddr] = bytes
            result[SpcData.SAMPLE_DIRECTORY + slot * 4] = byteArrayOf(sample.startAddr.toByte(), (sample.startAddr ushr 8).toByte(), loop.toByte(), (loop ushr 8).toByte())
        }
        return result
    }

    fun plan(parser: RomParser, input: SampleBankPreview.Target, sample: SampleAuthoring.Encoded, name: String): Application {
        MusicSamplePlacement.validate(sample)
        val target = input.copy(note = input.note.copy(), project = MusicBankTransitions.snapshot(input.project))
        val project = target.project
        val key = MusicInstrumentBanks.key(target.songSet)
        val context = SampleBankPreview.load(parser, target)
        val bank = MusicInstrumentBanks.copy(project.musicInstrumentBanks[key]) ?: MusicInstrumentBankEdit()
        val existing = bank.samples.values.firstOrNull { it.loopByteOffset == sample.loopByteOffset && decode(it).contentEquals(sample.brr) }
        val source = MusicBankResolver.sourceRam(parser, project.copy(musicInstrumentBanks = project.musicInstrumentBanks.toMutableMap().also {
            it[key] = bank.copy(samples = mutableMapOf())
        }), target.songSet, includeOverrides = false)
        val uploads = retainedUploads(parser, project, target.songSet)
        val native = NspcRenderer.readInstrumentTable(source)
        val srcn = existing?.srcn ?: (SpcData.STOCK_SAMPLE_COUNT until SpcData.SAMPLE_COUNT).firstOrNull {
            it !in bank.samples && directoryAvailable(it, source, uploads, native)
        } ?: error("No custom sample slots left in this bank")
        val allocation = existing?.let { MusicSamplePlacement.Range(it.startAddr, it.startAddr + sample.brr.size) } ?: context.layout.place(sample)
        if (existing == null) bank.samples[srcn] = MusicSampleEdit(srcn, allocation.start,
            Base64.getEncoder().encodeToString(sample.brr), sample.loopByteOffset, name.trim().take(120))
        val instrument = NspcRenderer.readInstrumentTable(MusicBankResolver.sourceRam(parser, project, target.songSet))
            .firstOrNull { it.index == target.note.instrument } ?: error("This sound is no longer available")
        bank.instruments[instrument.index] = MusicInstrumentBanks.entry(instrument.copy(srcn = srcn))
        val staged = MusicBankTransitions.snapshot(project)
        staged.musicInstrumentBanks[key] = bank
        // Re-resolve the complete bank, including every sibling arrangement, before offering Apply.
        MusicBankResolver.resolve(parser, staged, target.songSet)
        val remaining = if (existing != null) context.layout else MusicSamplePlacement.Layout(context.layout.gaps.flatMap { gap ->
            if (allocation.start >= gap.start && allocation.endExclusive <= gap.endExclusive)
                listOf(MusicSamplePlacement.Range(gap.start, allocation.start), MusicSamplePlacement.Range(allocation.endExclusive, gap.endExclusive)).filter { it.size > 0 }
            else listOf(gap)
        })
        return Application(target, bank, srcn, sample.brr.size, remaining.totalBytes, remaining.largestBytes,
            existing != null, project.musicInstrumentBanks[key] != bank)
    }

    internal fun retainedUploads(parser: RomParser, project: SmEditProject, songSet: Int, sources: Map<Int, ByteArray> = emptyMap()): List<SpcData.TransferBlock> {
        val boot = SpcData.buildInitialSpcRam(parser)
        return SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000)) +
            SpcData.findSongSetTransferData(parser, songSet) +
            sources.map { (at, data) -> SpcData.TransferBlock(at, data) } +
            project.musicEdits.values.filter { it.songSet == songSet }.flatMap { it.nativePayload?.let(MusicEditConversion::toTransferBlocks).orEmpty() } +
            MusicSequenceBudget.sampleRanges(boot).map { SpcData.TransferBlock(it.start, boot.copyOfRange(it.start, it.endExclusive)) }
    }

    private fun directoryAvailable(slot: Int, ram: ByteArray, uploads: List<SpcData.TransferBlock>, instruments: List<NspcRenderer.InstrumentEntry>): Boolean {
        val at = SpcData.SAMPLE_DIRECTORY + slot * 4
        return slot in SpcData.STOCK_SAMPLE_COUNT until SpcData.SAMPLE_COUNT &&
            (at until at + 4).all { ram[it] == 0.toByte() } && instruments.none { it.srcn == slot } &&
            uploads.none { it.destAddr < at + 4 && it.destAddr + it.data.size > at }
    }
}
