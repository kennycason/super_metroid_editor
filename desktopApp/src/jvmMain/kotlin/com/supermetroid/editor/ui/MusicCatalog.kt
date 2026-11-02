package com.supermetroid.editor.ui

import com.supermetroid.editor.data.*
import com.supermetroid.editor.rom.*
import java.util.Base64

/** Project identities and frozen palette sources. Native arrangements remain untouched. */
internal object MusicCatalog {
    fun tracks(project: SmEditProject): List<SpcData.TrackInfo> {
        validate(project)
        return SpcData.KNOWN_TRACKS + project.musicTracks.values.sortedWith(compareBy<MusicTrackDefinition> { it.songSet }.thenBy { it.playIndex })
            .map { SpcData.TrackInfo(it.songSet, it.playIndex, it.name, project.musicBanks[MusicInstrumentBanks.key(it.songSet)]?.name ?: "Project") }
    }
    fun banks(project: SmEditProject) = (SpcData.SONG_SETS + project.musicBanks.values.map { it.songSet }).distinct().sorted()
    fun bankName(project: SmEditProject, set: Int) = project.musicBanks[MusicInstrumentBanks.key(set)]?.name
        ?: SpcData.KNOWN_TRACKS.firstOrNull { it.songSet == set }?.name ?: "Bank ${MusicInstrumentBanks.key(set)}"
    fun baseSet(project: SmEditProject, set: Int) = project.musicBanks[MusicInstrumentBanks.key(set)]?.sourceSongSet ?: set
    fun blocks(parser: RomParser, project: SmEditProject, set: Int) = SpcData.findSongSetTransferData(parser, baseSet(project, set))
    fun baseline(project: SmEditProject, set: Int, play: Int): NspcSequence.Song? = project.musicTracks[MusicTrackEdit.key(set, play)]?.let {
        MusicEditConversion.toSong(MusicTrackEdit(set, play, it.name, it.baseline.tempo, it.baseline.channels))
    }
    fun paletteWrites(project: SmEditProject, set: Int): Map<Int, ByteArray> {
        val blocks = project.musicBanks[MusicInstrumentBanks.key(set)]?.sourceBlocks.orEmpty()
        require(blocks.size <= 4096) { "Palette snapshot has too many blocks" }
        val bytes = sortedMapOf<Int, Int>()
        for (block in blocks) {
            require(block.dataBase64.length <= 90_000) { "Palette block exceeds SPC RAM" }
            val data = Base64.getDecoder().decode(block.dataBase64)
            require(data.isNotEmpty() && block.destAddr >= 0x5800 && data.size <= 65536 - block.destAddr) { "Invalid bank palette snapshot" }
            data.forEachIndexed { i, value -> require(bytes.put(block.destAddr + i, value.toInt() and 255) == null) { "Palette source blocks overlap" } }
        }
        return SpcData.packSpcBytesToWrites(bytes)
    }
    fun requirePalette(project: SmEditProject, set: Int, ram: ByteArray) {
        if (project.musicBanks[MusicInstrumentBanks.key(set)]?.sourceBlocks.isNullOrEmpty()) return
        val allowed = listOf(MusicSequenceBudget.SpcRange(0x6C00, 0x6CFC), MusicSequenceBudget.SpcRange(0x6D00, SpcData.SAMPLE_DIRECTORY_END)) +
            MusicSequenceBudget.sampleRanges(ram).map { MusicSequenceBudget.SpcRange(maxOf(it.start, MusicSamplePlacement.START), it.endExclusive) }
        for ((at, bytes) in paletteWrites(project, set)) require(bytes.indices.all { i -> allowed.any { at + i in it.start until it.endExclusive } }) {
            "Palette snapshot contains non-resource data"
        }
    }
    fun validate(project: SmEditProject) {
        for ((key, bank) in project.musicBanks) {
            require(key == MusicInstrumentBanks.key(bank.songSet) && bank.songSet in 0x4B..0xFC && bank.songSet % 3 == 0 &&
                bank.sourceSongSet in SpcData.SONG_SETS && bank.name.isNotBlank() && bank.name.length <= 80) { "Invalid project music bank" }
        }
        for ((key, track) in project.musicTracks) {
            require(key == MusicTrackEdit.key(track.songSet, track.playIndex) && track.songSet in banks(project) &&
                track.playIndex in 5..127 && SpcData.KNOWN_TRACKS.none { it.id == (track.songSet shl 8 or track.playIndex) } &&
                track.name.isNotBlank() && track.name.length <= 80) { "Invalid project track identity" }
        }
    }
    fun requireSafeCells(parser: RomParser, project: SmEditProject, set: Int, ram: ByteArray) {
        val created = project.musicTracks.values.filter { it.songSet == set }
        if (created.isEmpty()) return
        val native = if (project.musicBanks.containsKey(MusicInstrumentBanks.key(set))) emptyList()
            else SpcData.KNOWN_TRACKS.filter { it.songSet == set }.map { it.playIndex }
        val usage = NativeSoundUsage.inspect(ram, native + listOf(1, 2, 3, 4))
        require(usage.complete) { "Cannot verify native song table ownership" }
        val resources = paletteWrites(project, set).map { (at, bytes) -> at until at + bytes.size } +
            MusicSequenceBudget.sampleRanges(ram).map { it.start until it.endExclusive }
        for (track in created) {
            val at = 0x581E + track.playIndex * 2
            require((at..at + 1).none { it in usage.sequenceBytes || resources.any { range -> it in range } }) {
                "New track #${track.playIndex} overlaps native music or palette data; choose a new bank"
            }
        }
    }
    /** Protect every reachable native byte, plus imported resources, before claiming a table cell. */
    fun freePlayIndex(parser: RomParser, project: SmEditProject, set: Int): Int {
        val ram = MusicBankResolver.sourceRam(parser, project, set)
        val native = if (project.musicBanks.containsKey(MusicInstrumentBanks.key(set))) emptyList()
            else SpcData.KNOWN_TRACKS.filter { it.songSet == set }.map { it.playIndex }
        val usage = NativeSoundUsage.inspect(ram, (native + listOf(1, 2, 3, 4)).distinct())
        require(usage.complete) { "Cannot fully inspect native track references in this bank" }
        val ranges = MusicCatalog.paletteWrites(project, set).map { (at, bytes) -> at until at + bytes.size } +
            MusicSequenceBudget.sampleRanges(ram).map { it.start until it.endExclusive } +
            project.musicEdits.values.filter { it.songSet == set }.flatMap { it.nativePayload?.let(MusicEditConversion::toTransferBlocks).orEmpty() }
                .map { it.destAddr until it.destAddr + it.data.size }
        val used = tracks(project).filter { it.songSet == set }.map { it.playIndex }.toSet()
        return (5..127).firstOrNull { play ->
            val at = 0x581E + play * 2
            play !in used && (at..at + 1).none { it in usage.sequenceBytes || ranges.any { range -> it in range } }
        } ?: error("No safe track-table slots in this bank. Choose New bank for an independent palette.")
    }
}
