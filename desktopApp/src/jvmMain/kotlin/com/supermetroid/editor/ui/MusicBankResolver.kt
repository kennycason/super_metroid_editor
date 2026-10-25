package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/** The bank that the game actually loads, shared by previews and ROM export. */
internal object MusicBankResolver {
    data class Resolved(val ram: ByteArray, val writes: Map<Int, ByteArray>, val patchedTracks: Int, val hasNativePayload: Boolean) {
        val blocks get() = writes.map { (at, data) -> SpcData.TransferBlock(at, data) }
    }

    fun sourceRam(parser: RomParser, project: SmEditProject, songSet: Int, includeOverrides: Boolean = true): ByteArray {
        val ram = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val sources = sourceWrites(project, songSet)
        for ((at, value) in sources) value.copyInto(ram, at)
        if (includeOverrides) {
            val bank = MusicInstrumentBanks.inspect(parser, project, songSet)
            bank.requireResolved()
            for ((at, value) in MusicInstrumentBanks.writes(bank.instruments)) value.copyInto(ram, at)
        }
        return ram
    }

    fun resolve(parser: RomParser, project: SmEditProject, songSet: Int): Resolved {
        val bank = MusicInstrumentBanks.inspect(parser, project, songSet)
        bank.requireResolved()
        val edits = project.musicEdits.toSortedMap().filterValues { it.songSet == songSet }
        val baseBlocks = SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000))
        val songBlocks = SpcData.findSongSetTransferData(parser, songSet)
        val original = SpcData.buildInitialSpcRam(parser).also { SpcData.applyTransferBlocks(it, songBlocks) }
        val sources = sourceWrites(project, songSet)
        val source = original.copyOf().also { ram -> sources.forEach { (at, data) -> data.copyInto(ram, at) } }
        val known = SpcData.KNOWN_TRACKS.filter { it.songSet == songSet }.mapTo(mutableSetOf()) { it.playIndex }
        known += edits.values.map { it.playIndex }
        val protected = known - edits.values.map { it.playIndex }.toSet()
        val occupied = (MusicSequenceBudget.buildProtectedSpcOccupancy(baseBlocks, songBlocks, original, protected) +
            MusicSequenceBudget.buildProtectedSpcOccupancy(baseBlocks, songBlocks, source, protected) +
            MusicSequenceBudget.sampleRanges(source) + sources.map { (at, data) -> MusicSequenceBudget.SpcRange(at, at + data.size) }).toMutableList()
        val bytes = java.util.TreeMap<Int, Int>()
        val owners = mutableMapOf<Int, String>()
        var patched = 0
        for ((key, edit) in edits) {
            val writes = if (edit.nativePayload != null) {
                val resolved = ImportedMusicResolver.resolve(parser, edit, occupied, protected, applyInstrumentOverrides = false)
                resolved.allocation?.let { occupied += it }
                resolved.writes
            } else {
                val song = MusicEditConversion.toSong(edit)
                if (song == NspcSequence.parse(source, edit.playIndex)) continue
                MusicSequenceBudget.encodeRelocated(song, edit.playIndex, source, occupied, key).also { patch ->
                    require(!patch.fit.trimmed) {
                        "Music edit '$key' (${edit.trackName}) exceeds safe SPC RAM: export would discard ${patch.fit.removedNotes} note(s) and ${patch.fit.removedCommands} command(s). Shorten the arrangement before exporting."
                    }
                    occupied += patch.allocation
                }.writes
            }
            merge(bytes, owners, writes, "'$key' (${edit.trackName})")
            patched++
        }
        // Explicit bank edits intentionally override retained source instrument bytes, once.
        for ((at, data) in MusicInstrumentBanks.writes(bank.instruments)) {
            data.forEachIndexed { i, value -> bytes[at + i] = value.toInt() and 255 }
        }
        val writes = SpcData.packSpcBytesToWrites(bytes)
        val ram = original.copyOf().also { result -> writes.forEach { (at, data) -> data.copyInto(result, at) } }
        return Resolved(ram, writes, if (writes.isEmpty()) 0 else maxOf(1, patched), edits.values.any { it.nativePayload != null })
    }

    private fun sourceWrites(project: SmEditProject, songSet: Int): Map<Int, ByteArray> {
        val bytes = java.util.TreeMap<Int, Int>()
        val owners = mutableMapOf<Int, String>()
        for ((key, edit) in project.musicEdits.toSortedMap().filterValues { it.songSet == songSet }) {
            val payload = edit.nativePayload ?: continue
            merge(bytes, owners, MusicTransferChainBudget.compactedNativePayloadWrites(payload, edit.playIndex), "import '$key'")
        }
        return SpcData.packSpcBytesToWrites(bytes)
    }

    private fun merge(bytes: MutableMap<Int, Int>, owners: MutableMap<Int, String>, writes: Map<Int, ByteArray>, owner: String) {
        for ((at, data) in writes) {
            require(at >= 0 && at < 65536 && data.size <= 65536 - at) { "Music data exceeds SPC RAM" }
            data.forEachIndexed { i, value ->
                require(at + i !in bytes) { "Shared sound-bank data overlaps at SPC 0x${(at + i).toString(16)} (${owners[at + i]} vs $owner). Keep one native import per overlapping bank." }
                bytes[at + i] = value.toInt() and 255
                owners[at + i] = owner
            }
        }
    }
}
