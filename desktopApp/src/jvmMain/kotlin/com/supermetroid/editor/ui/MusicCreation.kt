package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import java.util.Base64

internal object MusicCreation {
    data class Plan(val before: SmEditProject, val after: SmEditProject, val track: SpcData.TrackInfo, val bankCreated: Boolean,
        val sequenceBytes: Int, val gapBytes: Int)

    fun sameMusic(a: SmEditProject, b: SmEditProject) = a.projectId == b.projectId && a.romPath == b.romPath &&
        a.musicBanks == b.musicBanks && a.musicTracks == b.musicTracks && a.musicEdits == b.musicEdits &&
        a.musicInstrumentBanks == b.musicInstrumentBanks && a.soundEffectEdits == b.soundEffectEdits && a.soundEffectPrograms == b.soundEffectPrograms &&
        a.musicInstrumentNames == b.musicInstrumentNames && a.musicSampleNames == b.musicSampleNames

    fun plan(parser: RomParser, input: SmEditProject, name: String, palette: Int, newBankName: String?, duplicate: SpcData.TrackInfo?): Plan {
        val before = MusicBankTransitions.snapshot(input)
        val after = MusicBankTransitions.snapshot(input)
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 80) { "Name the track using 1–80 characters" }
        require(palette in MusicCatalog.banks(before)) { "Choose a sound bank" }
        val set = if (newBankName != null) {
            val label = newBankName.trim()
            require(label.isNotEmpty() && label.length <= 80) { "Name the new bank using 1–80 characters" }
            val table = SpcMusicBankTable.read(parser)
            require(table.expanded || table.pointer == 0x8FE7E1) { "Cannot extend an unknown custom music table" }
            val occupied = SpcMusicBankTable.songSets(parser).toSet() + before.musicBanks.values.map { it.songSet }
            val id = (0x4B..0xFC step 3).firstOrNull { it !in occupied } ?: error("All native music bank IDs are in use")
            val root = MusicCatalog.baseSet(before, palette)
            val raw = SoundEffects.bootRam(parser, before).also { SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, root)) }
            val source = MusicBankResolver.sourceRam(parser, before, palette, includeOverrides = false, includeSamples = false)
            SoundEffects.requireEngine(source)
            val ranges = listOf(MusicSequenceBudget.SpcRange(0x6C00, 0x6CFC), MusicSequenceBudget.SpcRange(0x6D00, SpcData.SAMPLE_DIRECTORY_END)) + MusicSequenceBudget.sampleRanges(source)
            val bytes = sortedMapOf<Int, Int>()
            for (range in ranges) for (at in range.start until range.endExclusive) if (raw[at] != source[at]) bytes[at] = source[at].toInt() and 255
            val blocks = SpcData.packSpcBytesToWrites(bytes).map { (at, data) -> MusicTransferBlockEdit(at, Base64.getEncoder().encodeToString(data)) }.toMutableList()
            after.musicBanks[MusicInstrumentBanks.key(id)] = MusicBankDefinition(id, label, root, blocks)
            MusicInstrumentBanks.copy(before.musicInstrumentBanks[MusicInstrumentBanks.key(palette)])?.let { after.musicInstrumentBanks[MusicInstrumentBanks.key(id)] = it }
            val oldPrefix = "${MusicInstrumentBanks.key(palette)}:"
            val newPrefix = "${MusicInstrumentBanks.key(id)}:"
            for ((key, value) in before.musicInstrumentNames.filterKeys { it.startsWith(oldPrefix) }) after.musicInstrumentNames[newPrefix + key.removePrefix(oldPrefix)] = value
            for ((key, value) in before.musicSampleNames.filterKeys { it.startsWith(oldPrefix) }) after.musicSampleNames[newPrefix + key.removePrefix(oldPrefix)] = value
            id
        } else palette
        val play = MusicCatalog.freePlayIndex(parser, after, set)
        val song = if (duplicate == null) NspcSequence.Song() else {
            require(MusicCatalog.tracks(before).any { it.id == duplicate.id }) { "The source track changed" }
            before.musicEdits[MusicTrackEdit.key(duplicate.songSet, duplicate.playIndex)]?.let(MusicEditConversion::toSong)
                ?: MusicCatalog.baseline(before, duplicate.songSet, duplicate.playIndex)
                ?: NspcSequence.parse(MusicBankResolver.resolve(parser, before, duplicate.songSet).ram, duplicate.playIndex)
        }
        song.title = title
        val track = SpcData.TrackInfo(set, play, title, MusicCatalog.bankName(after, set))
        val data = MusicEditConversion.toProjectEdit(track, song)
        after.musicTracks[MusicTrackEdit.key(set, play)] = MusicTrackDefinition(set, play, title, MusicNativeBaselineEdit(data.tempo, data.channels))
        val resolved = MusicBankResolver.resolve(parser, after, set)
        MusicBankTransitions.inspect(parser, after).requireSafe()
        // A ready form must also fit the current ROM allocator and upload format.
        MusicRomExporter(after, {}).applyMusicEditsToRom(parser.copyRomData())
        val bytes = NspcSequence.encodedSequenceSize(song)
        return Plan(before, after, track, newBankName != null, bytes,
            MusicSequenceBudget.largestAvailableRange(resolved.writes.map { (at, value) -> MusicSequenceBudget.SpcRange(at, at + value.size) } +
                MusicSequenceBudget.buildProtectedSpcOccupancy(com.supermetroid.editor.rom.SpcTransferStream.read(parser, 0xCF8000).blocks,
                    MusicCatalog.blocks(parser, after, set), resolved.ram, MusicCatalog.tracks(after).filter { it.songSet == set }.map { it.playIndex }.toSet()))?.size ?: 0)
    }

    fun requireUnusedByRooms(project: SmEditProject, removed: Set<String>) {
        for (room in project.rooms.values) for (state in listOfNotNull(room.stateDataChange) + room.states.mapNotNull { it.stateDataChange }) {
            val set = state.musicData; val play = state.musicTrack
            require(removed.none { key ->
                val parts = key.split(':'); val removedSet = parts[0].toInt(16); val removedPlay = parts[1].toInt(16)
                (set == removedSet && (play == null || play == removedPlay)) || (set == null && play == removedPlay)
            }) {
                "Undo the room music assignment before undoing track creation"
            }
        }
    }
}
