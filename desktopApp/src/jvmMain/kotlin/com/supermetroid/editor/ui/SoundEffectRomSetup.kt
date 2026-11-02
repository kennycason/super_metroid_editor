package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.*
import java.io.ByteArrayOutputStream

/** A boot-time upload splice. The original small block and remainder retain their exact contents. */
internal object SoundEffectRomSetup {
    data class Plan(val allocations: List<RomAllocation>, val writes: Map<Int, ByteArray>)
    fun excludeOwned(ranges: List<IntRange>, owned: List<RomAllocation>): List<IntRange> {
        var result = ranges
        for (allocation in owned) result = result.flatMap { range ->
            val start = allocation.pcOffset; val end = start + allocation.size
            if (range.last < start || range.first >= end) listOf(range)
            else listOf(range.first until minOf(start, range.last + 1), maxOf(end, range.first)..range.last).filter { !it.isEmpty() }
        }
        return result
    }
    fun prepare(parser: RomParser, project: SmEditProject, allocator: RomFreeSpaceAllocator): Plan {
        if (project.soundEffectPrograms.isEmpty()) return Plan(emptyList(), emptyMap())
        SoundEffectPrograms.requireStorage(parser, project)
        val ram = SpcData.buildInitialSpcRam(parser)
        SoundEffects.requireEngine(ram)
        val compiled = SoundEffectPrograms.compile(ram, project.soundEffectPrograms)
        val pointers = (listOf(0xCF8000) + (SpcData.TRANSFER_SONG_SETS + SpcMusicBankTable.songSets(parser)).distinct().map { SpcData.readSongSetPointer(parser, it) }).distinct()
        val chains = pointers.associateWith { SpcTransferStream.read(parser, it) }
        val installed = SpcTransferStream.installedCodePointer(parser)
        val table = SpcMusicBankTable.read(parser)
        val metadata = listOf(parser.snesToPc(0x808024) until parser.snesToPc(0x808111), parser.snesToPc(0x808F72) until parser.snesToPc(0x808F7E),
            if (table.expanded) table.pc - SpcMusicBankTable.HEADER_BYTES until table.pc + SpcMusicBankTable.TABLE_BYTES else table.pc until table.pc + 0x4B) +
            listOfNotNull(installed?.let { parser.snesToPc(it) until parser.snesToPc(it) + MusicTransferLoader.code.size })
        allocator.protectRanges(chains.values.flatMap { it.romRanges } + metadata)
        val writes = sortedMapOf<Int, Byte>()
        for (chain in chains.values) for ((at, bytes) in compiled.writes) for (i in bytes.indices) {
            val address = at + i
            val source = chain.sourceBlocks.lastOrNull { address in it.destAddr until it.destAddr + it.data.size } ?: continue
            val old = source.data[address - source.destAddr]
            require(old == ram[address] || old == bytes[i]) { "A bank owns conflicting effect engine data" }
            writes[source.dataStartPc + address - source.destAddr] = bytes[i]
        }
        val allocated = mutableListOf<Pair<RomAllocation, ByteArray>>()
        if (!chains.getValue(0xCF8000).covers(SoundEffectPrograms.arenas.associate { it.first to compiled.ram.copyOfRange(it.first, it.last + 1) })) {
            SpcTransferStream.requireSupportedLoader(parser)
            val source = chains.getValue(0xCF8000).sourceBlocks.firstOrNull { it.destAddr == 0x5800 && it.data.size == 24 }
                ?: error("No verified boot upload splice for custom effects")
            val header = source.dataStartPc - 4
            require((parser.pcToSnes(header) and 65535) <= 0xFFFB) { "Boot splice crosses a ROM bank" }
            val out = ByteArrayOutputStream()
            fun word(value: Int) { out.write(value and 255); out.write(value ushr 8 and 255) }
            word(source.data.size); word(source.destAddr); out.write(source.data)
            for (range in SoundEffectPrograms.arenas) { word(range.count()); word(range.first); out.write(compiled.ram.copyOfRange(range.first, range.last + 1)) }
            val next = parser.pcToSnes(source.dataStartPc + source.data.size)
            word(0xFFFF); repeat(3) { out.write(next ushr (it * 8) and 255) }
            val data = out.toByteArray()
            val allocation = allocator.reserve(data.size, (0xB8..0xFF).toList(), "effect boot upload", 16) ?: error("No ROM space for custom effect upload")
            allocated += allocation to data
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), allocation.snesAddress.toByte(), (allocation.snesAddress ushr 8).toByte(), (allocation.snesAddress ushr 16).toByte())
                .forEachIndexed { i, value -> writes[header + i] = value }
            if (installed == null) {
                val code = allocator.reserve(MusicTransferLoader.code.size, (0xB8..0xFF).toList(), "effect boot loader", 16) ?: error("No ROM space for effect loader")
                val patch = MusicTransferLoader.patchWrites(parser, code.snesAddress)
                allocated += code to patch.getValue(code.pcOffset)
                for ((at, bytes) in patch.filterKeys { it != code.pcOffset }) bytes.forEachIndexed { i, value -> writes[at + i] = value }
            }
        }
        for ((allocation, data) in allocated) allocator.validateWrite(allocation, data)
        val packed = SpcData.packSpcBytesToWrites(writes.mapValues { it.value.toInt() and 255 })
        return Plan(allocated.map { it.first }, packed + allocated.associate { it.first.pcOffset to it.second })
    }
}
