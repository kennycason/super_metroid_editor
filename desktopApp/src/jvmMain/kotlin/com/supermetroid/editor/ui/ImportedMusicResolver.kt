package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicTrackEdit
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
    ): ByteArray {
        val ram = SpcData.buildInitialSpcRam(parser)
        SpcData.applyTransferBlocks(ram, SpcData.findSongSetTransferData(parser, songSet))
        apply(ram, MusicTransferChainBudget.compactedNativePayloadWrites(payload, playIndex))
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
        for (writes in listOf(sourceWrites, sequence?.writes.orEmpty())) {
            for ((at, data) in writes) data.forEachIndexed { i, value -> bytes[at + i] = value.toInt() and 255 }
        }
        val writes = SpcData.packSpcBytesToWrites(bytes)
        apply(ram, writes)
        return Resolved(ram, writes, sequence?.allocation)
    }

    private fun apply(ram: ByteArray, writes: Map<Int, ByteArray>) {
        for ((at, data) in writes) data.copyInto(ram, at)
    }
}
