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

    fun sourceRam(parser: RomParser, project: SmEditProject, songSet: Int, includeOverrides: Boolean = true, includeSamples: Boolean = true): ByteArray {
        val ram = SoundEffects.bootRam(parser, project).also {
            SpcData.applyTransferBlocks(it, MusicCatalog.blocks(parser, project, songSet))
        }
        val sources = sourceWrites(project, songSet)
        for ((at, value) in sources) value.copyInto(ram, at)
        MusicCatalog.requirePalette(project, songSet, ram)
        if (includeSamples) for ((at, value) in MusicSampleBanks.writes(parser, project, songSet, ram, sources)) value.copyInto(ram, at)
        if (includeOverrides) {
            val instruments = MusicInstrumentBanks.instruments(project, songSet)
            for ((at, value) in MusicInstrumentBanks.writes(instruments)) value.copyInto(ram, at)
        }
        return ram
    }

    fun resolve(parser: RomParser, project: SmEditProject, songSet: Int): Resolved {
        val instruments = MusicInstrumentBanks.instruments(project, songSet)
        MusicCatalog.validate(project)
        val edits = project.musicEdits.toMutableMap().also { map ->
            for ((key, definition) in project.musicTracks.filterValues { it.songSet == songSet }) if (key !in map) {
                map[key] = MusicEditConversion.toProjectEdit(SpcData.TrackInfo(songSet, definition.playIndex, definition.name, "Project"), MusicCatalog.baseline(project, songSet, definition.playIndex)!!)
            }
        }.toSortedMap().filterValues { it.songSet == songSet }
        val baseBlocks = SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000))
        val songBlocks = MusicCatalog.blocks(parser, project, songSet)
        val original = SoundEffects.bootRam(parser, project).also { SpcData.applyTransferBlocks(it, songBlocks) }
        val retainedSources = sourceWrites(project, songSet)
        val retained = original.copyOf().also { ram -> retainedSources.forEach { (at, data) -> data.copyInto(ram, at) } }
        MusicCatalog.requirePalette(project, songSet, retained)
        val samples = MusicSampleBanks.writes(parser, project, songSet, retained, retainedSources)
        val sources = retainedSources + samples
        val source = retained.copyOf().also { ram -> samples.forEach { (at, data) -> data.copyInto(ram, at) } }
        MusicCatalog.requireSafeCells(parser, project, songSet, source)
        val known = MusicCatalog.tracks(project).filter { it.songSet == songSet }.mapTo(mutableSetOf()) { it.playIndex }
        known += edits.values.map { it.playIndex }
        val protected = known - edits.values.map { it.playIndex }.toSet()
        val cells = project.musicTracks.values.filter { it.songSet == songSet }.map { MusicSequenceBudget.SpcRange(0x581E + it.playIndex * 2, 0x5820 + it.playIndex * 2) }
        val occupied = (cells + MusicSequenceBudget.buildProtectedSpcOccupancy(baseBlocks, songBlocks, original, protected) +
            MusicSequenceBudget.buildProtectedSpcOccupancy(baseBlocks, songBlocks, source, protected) +
            MusicSequenceBudget.sampleRanges(source) + sources.map { (at, data) -> MusicSequenceBudget.SpcRange(at, at + data.size) }).toMutableList()
        val bytes = java.util.TreeMap<Int, Int>()
        val owners = mutableMapOf<Int, String>()
        var patched = 0
        for ((key, edit) in edits) {
            val writes = if (edit.nativePayload != null) {
                val resolved = ImportedMusicResolver.resolve(parser, edit, occupied, protected, project)
                resolved.allocation?.let { occupied += it }
                resolved.writes
            } else {
                val song = MusicEditConversion.toSong(edit)
                if (key !in project.musicTracks && song == NspcSequence.parse(source, edit.playIndex)) continue
                val created = project.musicTracks.values.filter { it.songSet == songSet }.map { it.playIndex }.toSet()
                val siblings = NativeSoundUsage.inspect(source, (known + setOf(1, 2, 3, 4) - edit.playIndex - created).toList())
                val operands = if (key !in project.musicTracks && siblings.complete) NativeCommandPatches.plan(source, edit.playIndex, song, siblings.sequenceBytes) else null
                operands ?: MusicSequenceBudget.encodeRelocated(song, edit.playIndex, source, occupied, key).also { patch ->
                    require(!patch.fit.trimmed) {
                        "Music edit '$key' (${edit.trackName}) exceeds safe SPC RAM: export would discard ${patch.fit.removedNotes} note(s) and ${patch.fit.removedCommands} command(s). Shorten the arrangement before exporting."
                    }
                    occupied += patch.allocation
                }.writes
            }
            merge(bytes, owners, writes, "'$key' (${edit.trackName})")
            patched++
        }
        merge(bytes, owners, MusicCatalog.paletteWrites(project, songSet), "palette")
        merge(bytes, owners, samples, "project samples")
        // Explicit bank edits intentionally override retained source instrument bytes, once.
        for ((at, data) in MusicInstrumentBanks.writes(instruments)) {
            data.forEachIndexed { i, value -> bytes[at + i] = value.toInt() and 255 }
        }
        val writes = SpcData.packSpcBytesToWrites(bytes)
        val ram = original.copyOf().also { result -> writes.forEach { (at, data) -> data.copyInto(result, at) } }
        return Resolved(ram, writes, if (writes.isEmpty()) 0 else maxOf(1, patched), edits.values.any { it.nativePayload != null })
    }

    private fun sourceWrites(project: SmEditProject, songSet: Int): Map<Int, ByteArray> {
        val bytes = java.util.TreeMap<Int, Int>()
        val owners = mutableMapOf<Int, String>()
        merge(bytes, owners, MusicCatalog.paletteWrites(project, songSet), "palette")
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
