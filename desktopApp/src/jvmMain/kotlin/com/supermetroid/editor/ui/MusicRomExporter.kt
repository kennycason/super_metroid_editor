package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomAllocation
import com.supermetroid.editor.rom.RomFreeSpaceAllocator
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.SpcTransferStream
import com.supermetroid.editor.rom.SpcMusicBankTable

/** Reserve, serialize and validate the complete transaction before changing the caller's ROM. */
internal class MusicRomExporter(private val project: SmEditProject, private val onLog: (String) -> Unit) {
    internal data class Plan(
        val patchedCount: Int,
        val writes: Map<Int, ByteArray>,
        val allocations: List<RomAllocation>,
    )

    private data class BankPlan(
        val songSet: Int,
        val pointerEntry: Int,
        val originalPointer: Int,
        val patches: List<Pair<Int, ByteArray>>,
        val relocated: MusicTransferChainLayout.Layout?,
        val prefix: Map<Int, ByteArray>,
    )

    internal fun applyMusicEditsToRom(romData: ByteArray, freeSpaceAllocator: RomFreeSpaceAllocator? = null): Int {
        val plan = planMusicEdits(romData, freeSpaceAllocator)
        for ((offset, bytes) in plan.writes) bytes.copyInto(romData, offset)
        return plan.patchedCount
    }

    internal fun planMusicEdits(
        romData: ByteArray,
        freeSpaceAllocator: RomFreeSpaceAllocator? = null,
    ): Plan {
        if (project.soundEffectEdits.isEmpty() && project.soundEffectPrograms.isEmpty() && project.musicBanks.isEmpty()) {
            return planMusicOnly(romData, freeSpaceAllocator)
        }
        val original = RomParser(romData)
        val allocator = (freeSpaceAllocator ?: RomFreeSpaceAllocator(
            romData, original::snesToPc, original::pcToSnes, guardBytes = 2,
        )).scoped(
            owner = "music:project",
            kind = com.supermetroid.editor.rom.RomSegmentKind.MUSIC,
            idPrefix = "music:project",
            constraint = "SPC upload data and loader pointers must remain LoROM-addressable",
        )
        val patches = SoundEffects.exportWrites(RomParser(romData), project)
        val staged = romData.copyOf()
        patches.forEach { (at, value) -> staged[at] = value }
        val effects = SoundEffectRomSetup.prepare(RomParser(staged), project, allocator)
        effects.writes.forEach { (at, value) -> value.copyInto(staged, at) }
        val setup = MusicBankRomSetup.prepare(RomParser(staged), project, allocator, effects.allocations)
        setup.writes.forEach { (at, value) -> value.copyInto(staged, at) }
        val boot = SpcData.buildInitialSpcRam(RomParser(staged))
        check(boot.contentEquals(SoundEffects.bootRam(original, project))) { "Effect boot upload differs from the preview" }
        val music = MusicRomExporter(project.copy(soundEffectEdits = mutableMapOf()), onLog)
            .planMusicOnly(staged, allocator, setup.allocation, effects.allocations)
        val allWrites = mergeWrites(
            listOf(SpcData.packSpcBytesToWrites(patches.mapValues { it.value.toInt() and 0xFF })) +
                effects.writes + setup.writes + music.writes,
        )
        return Plan(
            patchedCount = music.patchedCount + project.soundEffectEdits.size + project.soundEffectPrograms.size,
            writes = allWrites,
            allocations = (effects.allocations + listOfNotNull(setup.allocation) + music.allocations)
                .distinctBy { it.pcOffset to it.size },
        )
    }

    private fun planMusicOnly(
        romData: ByteArray,
        freeSpaceAllocator: RomFreeSpaceAllocator? = null,
        ownedTable: RomAllocation? = null,
        ownedEffects: List<RomAllocation> = emptyList(),
    ): Plan {
        val parser = RomParser(romData)
        val songSets = MusicBankTransitions.editedSongSets(project)
        val installed = SpcTransferStream.installedCodePointer(parser)
        val expandedTable = runCatching { SpcMusicBankTable.read(parser).expanded }.getOrDefault(false)
        if (songSets.isEmpty() && installed == null && !expandedTable) return Plan(0, emptyMap(), emptyList())
        require(songSets.all { it in 3..255 && it % 3 == 0 }) { "Music edits require a non-setup native song set" }
        // Inventory every native bank, including setup and unused $48, before reserving space.
        val pointers = (SpcData.TRANSFER_SONG_SETS + SpcMusicBankTable.songSets(parser) + songSets).distinct().associateWith {
            SpcData.readSongSetPointer(parser, it).also { pointer ->
                require(pointer > 0) { "No music transfer pointer for bank ${MusicInstrumentBanks.key(it)}" }
            }
        }
        val sources = (pointers.values + 0xCF8000).distinct().associateWith { SpcTransferStream.read(parser, it) }
        val sourceRanges = sources.values.flatMap { it.romRanges }
        val tableCells = pointers.keys.associateWith {
            SpcData.findSongSetPointerEntryPc(parser, it).also { pc -> require(pc >= 0) { "No writable music pointer entry" } }
        }
        val table = runCatching { SpcMusicBankTable.read(parser) }.getOrNull()
        val tableRanges = if (table?.expanded == true && ownedTable == null) listOf(table.pc - SpcMusicBankTable.HEADER_BYTES until table.pc + SpcMusicBankTable.TABLE_BYTES) else emptyList()
        val metadata = tableRanges + tableCells.values.filter { ownedTable == null || it !in ownedTable.pcOffset until ownedTable.pcOffset + ownedTable.size }.map { it until it + 3 } + listOf(parser.snesToPc(0x808024) until parser.snesToPc(0x808111), parser.snesToPc(0x808F72) until parser.snesToPc(0x808F7E)) +
            listOfNotNull(installed?.let { parser.snesToPc(it) until parser.snesToPc(it) + MusicTransferLoader.code.size })
        val allocator = (freeSpaceAllocator ?: RomFreeSpaceAllocator(
            romData, parser::snesToPc, parser::pcToSnes, guardBytes = 2,
        )).scoped(
            owner = "music:project",
            kind = com.supermetroid.editor.rom.RomSegmentKind.MUSIC,
            idPrefix = "music:project",
            constraint = "SPC upload data and loader pointers must remain LoROM-addressable",
        )
        allocator.protectRanges(SoundEffectRomSetup.excludeOwned(sourceRanges + metadata, ownedEffects + listOfNotNull(ownedTable)))
        if (songSets.isEmpty()) return Plan(0, emptyMap(), emptyList()) // Preserve installed source/code ownership for later exporters too.

        // Restorations are derived uploads, never saved overrides or authored track edits.
        val banks = songSets.associateWith { MusicBankResolver.resolve(parser, project, it) }.toMutableMap()
        val transitions = MusicBankTransitions.inspect(parser, project, banks)
        transitions.requireSafe()
        for (songSet in transitions.instrumentRestorations.keys + transitions.sampleDirectoryRestorations.keys) {
            val resets = transitions.instrumentRestorations[songSet].orEmpty() + transitions.sampleDirectoryRestorations[songSet].orEmpty()
            val bank = banks[songSet] ?: MusicBankResolver.resolve(parser, project, songSet)
            banks[songSet] = bank.copy(writes = MusicTransferChainBudget.mergeBlocks(bank.blocks, resets).associate { it.destAddr to it.data })
        }
        val proposed = MusicBankTransitions.inspect(parser, project, banks)
        proposed.requireSafe()
        check(proposed.instrumentRestorations.isEmpty()) { "Instrument restoration plan is incomplete" }
        check(proposed.sampleDirectoryRestorations.isEmpty()) { "Sample directory restoration plan is incomplete" }
        val plans = banks.filterValues { it.writes.isNotEmpty() }.map { (songSet, bank) ->
            val pointer = pointers.getValue(songSet)
            val source = sources.getValue(pointer)
            val loaded = BooleanArray(65536)
            for (block in source.sourceBlocks) loaded.fill(true, block.destAddr, block.destAddr + block.data.size)
            val missing = bank.writes.flatMap { (at, data) -> data.indices.map { at + it }.filter { !loaded[it] } }
            val samples = project.musicInstrumentBanks[MusicInstrumentBanks.key(songSet)]?.samples.orEmpty().values
                .map { it.startAddr until it.startAddr + MusicSampleBanks.decode(it).size }
            fun unit(at: Int): IntRange? = when (at) {
                in 0x6C00 until 0x6CFC -> (0x6C00 + (at - 0x6C00) / 6 * 6).let { it until it + 6 }
                in SpcData.SAMPLE_DIRECTORY + SpcData.STOCK_SAMPLE_COUNT * 4 until SpcData.SAMPLE_DIRECTORY_END ->
                    (SpcData.SAMPLE_DIRECTORY + (at - SpcData.SAMPLE_DIRECTORY) / 4 * 4).let { it until it + 4 }
                else -> samples.firstOrNull { at in it }
            }
            val canPrefix = missing.all { unit(it) != null }
            val prefix = if (canPrefix) missing.mapNotNull(::unit).distinct().associate {
                it.first to bank.ram.copyOfRange(it.first, it.last + 1)
            } else emptyMap()
            val covered = if (canPrefix) {
                val bytes = sortedMapOf<Int, Int>()
                // A retained partial entry must not overwrite its complete prefix.
                // Keep both entries when a larger authored write starts at the same address.
                for ((at, data) in bank.writes.entries + prefix.entries) data.forEachIndexed { i, value ->
                    if (loaded[at + i]) bytes[at + i] = value.toInt() and 255
                }
                SpcData.packSpcBytesToWrites(bytes)
            } else bank.writes
            val aliases = pointers.filterKeys { it != songSet }.values.flatMap { sources.getValue(it).romRanges } +
                sources.getValue(0xCF8000).romRanges + metadata
            val patches = if (source.covers(covered)) SpcData.buildRomWritesForSpcRamWrites(parser, songSet, covered)
                .flatMap { (at, data) -> data.indices.mapNotNull { i ->
                    (at + i to data[i]).takeIf { romData[at + i] != data[i] }
                } }
                .takeIf { writes -> writes.none { (at, _) -> aliases.any { at in it } } }
                ?.associate { it.first to (it.second.toInt() and 255) }?.let { SpcData.packSpcBytesToWrites(it).toList() }
            else null
            BankPlan(songSet, tableCells.getValue(songSet), pointer, patches.orEmpty(),
                if (patches == null) MusicTransferChainLayout.plan(MusicTransferChainBudget.mergeBlocks(source.blocks, covered), source.entryAddress) else null, prefix)
        }
        val needsContinuation = plans.any { it.prefix.isNotEmpty() || it.relocated?.continued == true }
        if (needsContinuation) SpcTransferStream.requireSupportedLoader(parser)
        // Reserve the complete transaction; none of these allocations have been written yet.
        val allowedBanks = musicTransferBanks(parser, romData)
        fun reserve(size: Int, label: String, alignment: Int): RomAllocation =
            allocator.reserve(size, allowedBanks, label, alignment) ?: error("not enough contiguous free ROM space for $size-byte $label")
        val hook = if (needsContinuation && installed == null) reserve(MusicTransferLoader.code.size, "music continuation code", 16) else null
        val relocated = plans.filter { it.relocated != null }.associateWith { plan ->
            requireNotNull(plan.relocated).segments.mapIndexed { index, segment ->
                reserve(segment.size, "music upload ${MusicInstrumentBanks.key(plan.songSet)} segment ${index + 1}", 16)
            }
        }
        val prefixes = plans.filter { it.prefix.isNotEmpty() }.associateWith {
            reserve(prefix(it.prefix, it.originalPointer).size,
                "instrument prefix for bank ${MusicInstrumentBanks.key(it.songSet)}", 2)
        }
        val allocated = mutableListOf<Pair<RomAllocation, ByteArray>>()
        val writes = mutableListOf<Pair<Int, ByteArray>>()
        for (plan in plans) {
            writes += plan.patches
            var pointer = plan.originalPointer
            relocated[plan]?.let { segments ->
                val layout = requireNotNull(plan.relocated)
                segments.forEachIndexed { index, allocation ->
                    allocated += allocation to layout.encode(index, segments.getOrNull(index + 1)?.snesAddress)
                }
                pointer = segments.first().snesAddress
            }
            prefixes[plan]?.let { allocation ->
                allocated += allocation to prefix(plan.prefix, pointer)
                pointer = allocation.snesAddress
            }
            if (pointer != plan.originalPointer) {
                require(sourceRanges.none { plan.pointerEntry <= it.last && plan.pointerEntry + 3 > it.first }) { "Music pointer overlaps live transfer data" }
                writes += plan.pointerEntry to byteArrayOf(pointer.toByte(), (pointer ushr 8).toByte(), (pointer ushr 16).toByte())
            }
        }
        if (hook != null) {
            val hookWrites = MusicTransferLoader.patchWrites(parser, hook.snesAddress)
            allocated += hook to hookWrites.getValue(hook.pcOffset)
            val patch = parser.snesToPc(MusicTransferLoader.PATCH_SNES)
            require(sourceRanges.none { patch <= it.last && patch + 6 > it.first }) { "Music loader patch overlaps live transfer data" }
            writes += patch to hookWrites.getValue(patch)
        }
        for ((allocation, _) in allocated) require((sourceRanges + metadata).none {
            allocation.pcOffset <= it.last && allocation.pcOffset + allocation.size > it.first
        }) { "Music allocation overlaps live source or loader data" }
        // Read precisely the serialized result before touching any input bytes.
        val staged = romData.copyOf()
        val physical = sortedMapOf<Int, Byte>()
        for ((at, data) in writes + allocated.map { it.first.pcOffset to it.second }) data.forEachIndexed { i, byte ->
            require(at + i in staged.indices) { "Music write exceeds ROM bounds" }
            require(physical[at + i]?.let { it == byte } != false) { "Music write plan overlaps incompatibly" }
            physical[at + i] = byte
            staged[at + i] = byte
        }
        val exported = RomParser(staged)
        val initial = SpcData.buildInitialSpcRam(parser)
        val concrete = pointers.keys.filter { it > 0 }.associateWith { songSet ->
            val stream = SpcTransferStream.read(exported, SpcData.readSongSetPointer(exported, songSet))
            val ram = initial.copyOf().also { SpcData.applyTransferBlocks(it, stream.blocks) }
            val expected = banks[songSet]?.ram ?: MusicBankResolver.sourceRam(parser, project, songSet)
            check(ram.contentEquals(expected)) { "Music prefix/export disagrees with bank ${MusicInstrumentBanks.key(songSet)}" }
            val bytes = sortedMapOf<Int, Int>()
            for (block in stream.blocks) block.data.forEachIndexed { i, value -> bytes[block.destAddr + i] = value.toInt() and 255 }
            MusicBankResolver.Resolved(ram, SpcData.packSpcBytesToWrites(bytes), 0, false)
        }
        val checked = MusicBankTransitions.inspect(parser, project, concrete)
        checked.requireSafe()
        check(checked.instrumentRestorations.isEmpty()) { "Serialized instrument restorations are incomplete" }
        check(checked.sampleDirectoryRestorations.isEmpty()) { "Serialized sample directory restorations are incomplete" }
        // Publish only after every stream, bank, transition and allocation has passed.
        for ((allocation, data) in allocated) allocator.validateWrite(allocation, data)
        for (plan in plans) onLog("[EXPORT] Bank ${MusicInstrumentBanks.key(plan.songSet)}: " + when {
            plan.prefix.isNotEmpty() -> "resource prefix (${plan.prefix.size} entries) + retained music"
            plan.relocated != null -> "relocated SPC transfer chain (${plan.relocated.size} bytes, ${plan.relocated.segments.size} ROM segment(s))"
            else -> "updated in place (${plan.patches.sumOf { it.second.size }} changed bytes; no bank copy)"
        })
        return Plan(
            patchedCount = songSets.sumOf { banks.getValue(it).patchedTracks },
            writes = SpcData.packSpcBytesToWrites(physical.mapValues { it.value.toInt() and 0xFF }),
            allocations = allocated.map { it.first },
        )
    }

    private fun mergeWrites(groups: List<Map<Int, ByteArray>>): Map<Int, ByteArray> {
        val bytes = sortedMapOf<Int, Int>()
        for (group in groups) for ((offset, data) in group) data.forEachIndexed { index, value ->
            val pc = offset + index
            val byte = value.toInt() and 0xFF
            require(bytes[pc]?.let { it == byte } != false) { "Music write plan overlaps incompatibly at PC 0x${pc.toString(16)}" }
            bytes[pc] = byte
        }
        return SpcData.packSpcBytesToWrites(bytes)
    }

    private fun prefix(entries: Map<Int, ByteArray>, pointer: Int): ByteArray =
        if (entries.keys.all { it in 0x6C00 until 0x6CFC }) MusicTransferLoader.instrumentPrefix(entries, pointer)
        else MusicTransferLoader.samplePrefix(entries, pointer)

    private fun musicTransferBanks(parser: RomParser, rom: ByteArray): List<Int> {
        val preferred = listOf(0xB8, 0xCE, 0x85, 0x83, 0x89)
        return (preferred + (0x80..0xFF).filterNot { it in preferred }).filter {
            val start = parser.snesToPc((it shl 16) or 0x8000)
            val end = parser.snesToPc((it shl 16) or 0xFFFF) + 1
            start >= 0 && end <= rom.size && start < end
        }
    }
}
