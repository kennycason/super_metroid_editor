package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/** Retained import data plus editable overlays, shared by playback and every native export path. */
internal object ImportedMusicResolver {
    data class Resolved(
        val ram: ByteArray,
        val writes: Map<Int, ByteArray>,
        val allocation: MusicSequenceBudget.SpcRange?,
    ) {
        val blocks: List<SpcData.TransferBlock> get() = writes.map { (at, data) -> SpcData.TransferBlock(at, data) }
    }

    fun sourceRam(
        parser: RomParser,
        songSet: Int,
        playIndex: Int,
        payload: MusicNativePayloadEdit,
        instruments: List<NspcRenderer.InstrumentEntry> = emptyList(),
    ): ByteArray {
        val ram = SpcData.buildInitialSpcRam(parser)
        SpcData.applyTransferBlocks(ram, SpcData.findSongSetTransferData(parser, songSet))
        apply(ram, MusicTransferChainBudget.compactedNativePayloadWrites(payload, playIndex))
        apply(ram, instrumentWrites(ram, instruments, payload))
        return ram
    }

    fun resolve(
        parser: RomParser,
        edit: MusicTrackEdit,
        occupiedRanges: List<MusicSequenceBudget.SpcRange> = emptyList(),
        protectedPlayIndexes: Set<Int> = SpcData.KNOWN_TRACKS.filter { it.songSet == edit.songSet && it.playIndex != edit.playIndex }
            .mapTo(mutableSetOf()) { it.playIndex },
    ): Resolved {
        val payload = requireNotNull(edit.nativePayload) { "No retained native import" }
        val baseBlocks = SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000))
        val songBlocks = SpcData.findSongSetTransferData(parser, edit.songSet)
        val original = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, songBlocks) }
        val sourceWrites = MusicTransferChainBudget.compactedNativePayloadWrites(payload, edit.playIndex)
        val sourceBlocks = sourceWrites.map { (at, data) -> SpcData.TransferBlock(at, data) }
        val ram = original.copyOf().also { apply(it, sourceWrites) }
        val instrumentWrites = instrumentWrites(ram, MusicEditConversion.toInstrumentEntries(edit), payload)
        apply(ram, instrumentWrites)

        val song = MusicEditConversion.toSong(edit)
        val changedSequence = song != MusicEditConversion.nativeBaselineSong(edit)
        val sequence = if (changedSequence) {
            val occupied = occupiedRanges +
                MusicSequenceBudget.buildProtectedSpcOccupancy(baseBlocks, songBlocks, original, protectedPlayIndexes) +
                MusicSequenceBudget.buildProtectedSpcOccupancy(baseBlocks, songBlocks + sourceBlocks, ram, protectedPlayIndexes) +
                MusicSequenceBudget.sampleRanges(ram)
            MusicSequenceBudget.encodeRelocated(song, edit.playIndex, ram, occupied, MusicEditConversion.key(edit.songSet, edit.playIndex)).also {
                require(!it.fit.trimmed) {
                    "Edited imported music does not fit in safe SPC RAM; shorten the arrangement before exporting."
                }
            }
        } else null

        val bytes = java.util.TreeMap<Int, Int>()
        for (writes in listOf(sourceWrites, sequence?.writes.orEmpty(), instrumentWrites)) {
            for ((at, data) in writes) data.forEachIndexed { i, value -> bytes[at + i] = value.toInt() and 255 }
        }
        val writes = SpcData.packSpcBytesToWrites(bytes)
        apply(ram, writes)
        return Resolved(ram, writes, sequence?.allocation)
    }

    private fun instrumentWrites(ram: ByteArray, instruments: List<NspcRenderer.InstrumentEntry>, payload: MusicNativePayloadEdit): Map<Int, ByteArray> = buildMap {
        val baseline = payload.baseline?.instruments?.associateBy { it.index }
        for (entry in instruments) {
            require(entry.index in 0 until 42 && entry.tableAddr == 0x6C00 + entry.index * 6) { "Invalid imported instrument slot ${entry.index}" }
            val bytes = PianoRollPreviewLogic.instrumentBytes(entry)
            // Sparse raw payloads can leave zero-filled placeholders in their
            // editable model. Unchanged placeholders must not erase bank entries.
            val old = baseline?.get(entry.index)
            if (old != null && entry.srcn == old.srcn && entry.adsr1 == old.adsr1 && entry.adsr2 == old.adsr2 && entry.gain == old.gain && entry.pitchAdj == old.pitchAdj) continue
            if (baseline == null) continue // Legacy payloads were always stored as pristine imports.
            if (!ram.copyOfRange(entry.tableAddr, entry.tableAddr + 6).contentEquals(bytes)) put(entry.tableAddr, bytes)
        }
    }

    private fun apply(ram: ByteArray, writes: Map<Int, ByteArray>) {
        for ((at, data) in writes) data.copyInto(ram, at)
    }
}
