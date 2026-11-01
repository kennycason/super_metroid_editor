package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*

/** Extend only the verified native queue's two table operands; uploads still use the checked exporter. */
internal object MusicBankRomSetup {
    data class Plan(val allocation: RomAllocation?, val writes: Map<Int, ByteArray>)
    fun prepare(parser: RomParser, project: SmEditProject, allocator: RomFreeSpaceAllocator): Plan {
        MusicCatalog.validate(project)
        if (project.musicBanks.isEmpty()) return Plan(null, emptyMap())
        val table = SpcMusicBankTable.read(parser)
        val sets = (SpcData.TRANSFER_SONG_SETS + SpcMusicBankTable.songSets(parser)).distinct()
        val entries = sets.associateWith { SpcData.readSongSetPointer(parser, it).also { value -> require(value > 0) { "Invalid existing music bank" } } }.toMutableMap()
        val chains = (entries.values + 0xCF8000).distinct().flatMap { SpcTransferStream.read(parser, it).romRanges }
        val tableRange = if (table.expanded) table.pc - SpcMusicBankTable.HEADER_BYTES until table.pc + SpcMusicBankTable.TABLE_BYTES else table.pc until table.pc + 0x4B
        val installed = SpcTransferStream.installedCodePointer(parser)?.let { parser.snesToPc(it) until parser.snesToPc(it) + MusicTransferLoader.code.size }
        allocator.protectRanges(chains + listOfNotNull(installed) + listOf(tableRange, parser.snesToPc(0x808024) until parser.snesToPc(0x808111),
            parser.snesToPc(0x808F72) until parser.snesToPc(0x808F7E)))
        for (bank in project.musicBanks.values) entries.putIfAbsent(bank.songSet, entries.getValue(bank.sourceSongSet))
        val data = SpcMusicBankTable.data(entries)
        if (table.expanded) return Plan(null, mapOf(table.pc - SpcMusicBankTable.HEADER_BYTES to data))
        require(table.pointer == 0x8FE7E1) { "Cannot extend an unknown music table" }
        val allocation = allocator.reserve(data.size, (0xB8..0xFF).toList(), "expanded music bank table", 16)
            ?: error("No ROM space for the music bank table")
        allocator.validateWrite(allocation, data)
        return Plan(allocation, mapOf(allocation.pcOffset to data) + SpcMusicBankTable.patchWrites(parser, allocation.snesAddress + SpcMusicBankTable.HEADER_BYTES))
    }
}
