package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import com.supermetroid.editor.rom.SpcTransferStream

/**
 * Detached sample-lifetime prototype. This is NOT an export permission or a spare-slot
 * detector: production export owns project samples separately. Native acceptance must use
 * these concrete writes through the CPU/APU uploader, not just a fresh-RAM preview.
 */
internal object MusicSampleRestoration {
    data class Plan(val writes: Map<Int, Map<Int, ByteArray>>) {
        val payloadBytes get() = writes.values.sumOf { bank -> bank.values.sumOf { it.size } }
        val prefixBytes get() = writes.values.sumOf { bank -> bank.values.sumOf { it.size + 4 } + 5 }
    }

    /** All uploads are retained; they are ownership, not erased-looking free space. */
    internal data class Bank(val ram: ByteArray, val uploads: List<SpcData.TransferBlock>)
    internal data class Need(val songSet: Int, val start: Int, val endExclusive: Int)

    fun plan(
        parser: RomParser,
        project: SmEditProject,
        resolved: Map<Int, MusicBankResolver.Resolved> = MusicBankTransitions.editedSongSets(project).associateWith {
            MusicBankResolver.resolve(parser, project, it)
        },
    ): Plan {
        if (resolved.isEmpty()) return Plan(emptyMap())
        val initial = SpcData.buildInitialSpcRam(parser)
        val baseBlocks = SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000))
        val banks = (SpcData.TRANSFER_SONG_SETS.filter { it > 0 } + resolved.keys).distinct().associateWith { set ->
            val native = SpcData.findSongSetTransferData(parser, set)
            val edited = resolved[set]
            val ram = edited?.ram ?: initial.copyOf().also { SpcData.applyTransferBlocks(it, native) }
            require((0x1500 until 0x5800).all { ram[it] == initial[it] }) {
                "Sample restoration requires the original SPC engine"
            }
            val uploads = native + edited?.blocks.orEmpty()
            val loaded = initial.copyOf().also { SpcData.applyTransferBlocks(it, uploads) }
            require(loaded.contentEquals(ram)) { "Sample restoration bank RAM does not match its uploads" }
            Bank(ram, uploads)
        }
        val report = MusicBankTransitions.inspect(parser, project, resolved)
        require(report.issues.none { it.resource == MusicBankTransitions.Resource.ENGINE }) {
            "Sample restoration cannot restore SPC engine code"
        }
        val needs = report.issues.map { Need(it.targetSongSet, it.start, it.endExclusive) } +
            report.instrumentRestorations.flatMap { (set, writes) -> writes.map { (at, data) -> Need(set, at, at + data.size) } } +
            report.sampleDirectoryRestorations.flatMap { (set, writes) -> writes.map { (at, data) -> Need(set, at, at + data.size) } }
        val engine = baseBlocks.filter { it.destAddr < 0x5800 }.map {
            MusicSequenceBudget.SpcRange(it.destAddr, minOf(0x5800, it.destAddr + it.data.size))
        }
        return derive(initial, banks, needs, engine).also {
            if (it.writes.isNotEmpty()) SpcTransferStream.requireSupportedLoader(parser)
        }
    }

    /**
     * Expand requests to complete DIR entries, instruments and touched nine-byte BRR
     * blocks. Then close over the NEW write footprints too: restoring a whole block can
     * expose another byte to a third bank which the original one-byte edit never touched.
     */
    internal fun derive(
        initial: ByteArray,
        banks: Map<Int, Bank>,
        needs: List<Need>,
        engine: List<MusicSequenceBudget.SpcRange> = emptyList(),
    ): Plan {
        require(initial.size == 65536) { "Expected complete initial SPC RAM" }
        require(engine.all { it.start >= 0 && it.endExclusive in it.start..65536 }) { "Invalid SPC engine range" }
        val resources = banks.toSortedMap().mapValues { (_, bank) -> Resources(initial, bank, engine) }
        val bytes = banks.keys.associateWith { sortedMapOf<Int, Int>() }
        val pending = ArrayDeque<Pair<Int, Int>>()
        fun restore(set: Int, at: Int) {
            val target = resources[set] ?: error("Unknown restoration bank $set")
            val writes = bytes.getValue(set)
            if (at in writes) return
            for (unit in target.units(at)) {
                require(engine.none { it.start < unit.endExclusive && it.endExclusive > unit.start }) {
                    "Sample restoration cannot write SPC engine code"
                }
                for (i in unit.start until unit.endExclusive) {
                    if (writes.put(i, target.ram[i].toInt() and 255) == null) pending.addLast(set to i)
                }
            }
        }
        for (need in needs.sortedWith(compareBy({ it.songSet }, { it.start }, { it.endExclusive }))) {
            require(need.start in 0..65535 && need.endExclusive in need.start + 1..65536) { "Invalid sample restoration range" }
            for (at in need.start until need.endExclusive) restore(need.songSet, at)
        }
        while (pending.isNotEmpty()) {
            val (sourceSet, at) = pending.removeFirst()
            val source = resources.getValue(sourceSet)
            for ((targetSet, target) in resources) {
                if (targetSet == sourceSet || target.loaded[at] || at in bytes.getValue(targetSet) ||
                    !target.protected(at) || source.ram[at] == target.ram[at]) continue
                restore(targetSet, at)
            }
        }
        val writes = bytes.filterValues { it.isNotEmpty() }.mapValues { (_, values) -> SpcData.packSpcBytesToWrites(values) }
        for ((set, values) in writes) require(values.values.sumOf { it.size + 4 } + 5 <= MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES) {
            "Sample restoration for bank ${MusicInstrumentBanks.key(set)} exceeds a single ROM bank"
        }
        return Plan(writes)
    }

    private class Resources(initial: ByteArray, bank: Bank, private val engine: List<MusicSequenceBudget.SpcRange>) {
        // Own our bytes: later UI/project mutations must not change a derived plan.
        val ram = bank.ram.copyOf()
        val loaded = BooleanArray(65536)
        private class Stream(val range: MusicSequenceBudget.SpcRange, val ram: ByteArray, var validated: Boolean = false)
        private val streams: List<Stream>

        init {
            require(ram.size == 65536) { "Expected complete bank SPC RAM" }
            for (block in bank.uploads) {
                require(block.destAddr in 0..65535 && block.data.size <= 65536 - block.destAddr) { "Sound upload exceeds SPC RAM" }
                loaded.fill(true, block.destAddr, block.destAddr + block.data.size)
            }
            streams = MusicSequenceBudget.sampleRanges(initial).map { Stream(it, initial) } +
                MusicSequenceBudget.sampleRanges(ram).map { Stream(it, ram) }
        }

        fun protected(at: Int) = at in 0x6C00 until 0x6CFC || at in 0x6D00 until SpcData.SAMPLE_DIRECTORY_END ||
            engine.any { at in it.start until it.endExclusive } || streams.any { at in it.range.start until it.range.endExclusive }

        fun units(at: Int): List<MusicSequenceBudget.SpcRange> {
            require(engine.none { at in it.start until it.endExclusive }) { "Sample restoration cannot write SPC engine code" }
            if (at in 0x6C00 until 0x6CFC) {
                val start = 0x6C00 + (at - 0x6C00) / 6 * 6
                return listOf(MusicSequenceBudget.SpcRange(start, start + 6))
            }
            if (at in 0x6D00 until SpcData.SAMPLE_DIRECTORY_END) {
                val start = 0x6D00 + (at - 0x6D00) / 4 * 4
                return listOf(MusicSequenceBudget.SpcRange(start, start + 4))
            }
            val owners = streams.filter { at in it.range.start until it.range.endExclusive }
            require(owners.isNotEmpty()) { "No recording owns restoration address 0x${at.toString(16)}" }
            return owners.map { stream ->
                if (!stream.validated) {
                    val range = stream.range
                    require(range.start >= 0x5800 && range.size > 0 && range.size % 9 == 0 &&
                        !(range.start < SpcData.SAMPLE_DIRECTORY_END && range.endExclusive > 0x6C00) &&
                        engine.none { it.start < range.endExclusive && it.endExclusive > range.start } &&
                        (stream.ram[range.endExclusive - 9].toInt() and 1) != 0) {
                        "Cannot restore an incomplete or overlapping BRR recording at 0x${range.start.toString(16)}"
                    }
                    stream.validated = true
                }
                val start = stream.range.start + (at - stream.range.start) / 9 * 9
                MusicSequenceBudget.SpcRange(start, start + 9)
            }.distinct()
        }
    }
}
