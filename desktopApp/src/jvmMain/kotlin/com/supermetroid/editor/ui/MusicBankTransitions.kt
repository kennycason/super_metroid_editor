package com.supermetroid.editor.ui

import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/**
 * Static resource-lifetime check, not a fresh-RAM preview or a sound-effect usage analysis.
 * The game uploads the next bank into existing RAM. Bytes changed by an edit must either
 * be reloaded there or be outside its protected resources, including common effect samples.
 */
internal object MusicBankTransitions {
    enum class Resource(val label: String) {
        COMMON_INSTRUMENT("Common instrument"),
        INSTRUMENT("Instrument"),
        DIRECTORY("Sample directory"),
        SAMPLE("Sample data"),
        ENGINE("SPC engine"),
    }

    data class Issue(
        val sourceSongSet: Int,
        val targetSongSet: Int,
        val resource: Resource,
        val start: Int,
        val endExclusive: Int,
        val editedSongSet: Int = sourceSongSet,
    )

    data class Report(
        val issues: List<Issue>,
        val checkedTransitions: Int,
        /** Small bank-local entries can be explicitly reloaded without copying common sample data. */
        val instrumentRestorations: Map<Int, Map<Int, ByteArray>> = emptyMap(),
    ) {
        val canExport: Boolean get() = issues.isEmpty()

        fun requireSafe() {
            val first = issues.firstOrNull() ?: return
            error("Music ROM export blocked: ${first.resource.label.lowercase()} at SPC \$${first.start.toString(16).uppercase().padStart(4, '0')} " +
                "from bank ${MusicInstrumentBanks.key(first.sourceSongSet)} is not restored when loading bank ${MusicInstrumentBanks.key(first.targetSongSet)}. " +
                "Reset the sound or replace the import in bank ${MusicInstrumentBanks.key(first.editedSongSet)}. " +
                "Audition and standalone SPC/N-SPC export remain available; common-resource restoration is not implemented.")
        }
    }

    /** Detach mutable sound containers before background UI validation. No save migrations. */
    fun snapshot(project: SmEditProject): SmEditProject = project.copy(
        musicEdits = project.musicEdits.mapValues { (_, edit) -> MusicEditConversion.copyProjectEdit(edit) }.toMutableMap(),
        musicInstrumentBanks = project.musicInstrumentBanks.mapValues { (_, bank) -> requireNotNull(MusicInstrumentBanks.copy(bank)) }.toMutableMap(),
    )

    fun inspect(
        parser: RomParser,
        project: SmEditProject,
        resolved: Map<Int, MusicBankResolver.Resolved> = editedSongSets(project).associateWith {
            MusicBankResolver.resolve(parser, project, it)
        },
    ): Report {
        if (resolved.isEmpty()) return Report(emptyList(), 0)
        val initial = SpcData.buildInitialSpcRam(parser)
        val baseBlocks = SpcData.parseTransferBlocks(parser.getRomData(), parser.snesToPc(0xCF8000))
        val songSets = (SpcData.SONG_SETS + resolved.keys).distinct().sorted()
        val banks = songSets.associateWith { songSet ->
            val originalBlocks = SpcData.findSongSetTransferData(parser, songSet)
            require(originalBlocks.isNotEmpty()) { "No music transfer data for bank ${MusicInstrumentBanks.key(songSet)}" }
            val original = initial.copyOf().also { SpcData.applyTransferBlocks(it, originalBlocks) }
            val edit = resolved[songSet]
            val ram = edit?.ram ?: original
            val uploads = originalBlocks + edit?.blocks.orEmpty()
            val loaded = BooleanArray(65536)
            for (block in uploads) for (at in block.destAddr until block.destAddr + block.data.size) loaded[at] = true
            val protected = IntArray(65536)
            fun protect(start: Int, end: Int, resource: Resource) {
                for (at in start until end) protected[at] = resource.ordinal + 1
            }
            // The complete instrument/directory tables are conservative: unproven unused
            // entries are not advertised as safe for duplication or effect isolation.
            protect(0x6C00, 0x6C90, Resource.COMMON_INSTRUMENT)
            protect(0x6C90, 0x6CFC, Resource.INSTRUMENT)
            protect(0x6D00, 0x6DA0, Resource.DIRECTORY)
            for (range in MusicSequenceBudget.sampleRanges(initial) + MusicSequenceBudget.sampleRanges(ram)) {
                protect(range.start, range.endExclusive, Resource.SAMPLE)
            }
            for (block in baseBlocks.filter { it.destAddr < 0x5800 }) {
                protect(block.destAddr, minOf(0x5800, block.destAddr + block.data.size), Resource.ENGINE)
            }
            val changed = edit?.writes.orEmpty().flatMap { (at, data) ->
                data.indices.mapNotNull { i -> (at + i).takeIf { original[it] != ram[it] } }
            }.sorted()
            // Check incoming loads too: a newly selected SRCN can expose a sample
            // the destination never uploads, even when no other bank was edited.
            val dependencies = BooleanArray(65536)
            val tracks = project.musicEdits.values.filter { it.songSet == songSet }
            val slots = project.musicInstrumentBanks[MusicInstrumentBanks.key(songSet)]?.instruments?.keys.orEmpty().toMutableSet()
            slots += tracks.flatMap { it.channels.flatMap { channel -> channel.notes.map { note -> note.instrument } } }
            slots += tracks.flatMap { it.channels.flatMap { channel ->
                channel.commands.filter { command -> command.command == 0xE0 }.mapNotNull { command -> command.params.firstOrNull() }
            } }
            for (track in tracks) track.nativePayload?.let { payload ->
                for (block in MusicEditConversion.toTransferBlocks(payload)) {
                    val start = maxOf(0x6C00, block.destAddr)
                    val end = minOf(0x6CFC, block.destAddr + block.data.size)
                    if (start < end) slots += (start - 0x6C00) / 6 until (end - 0x6C00 + 5) / 6
                }
            }
            fun depend(start: Int, end: Int) { for (at in start until end) dependencies[at] = true }
            fun sample(at: Int) {
                if (at !in 0x1500 until 65536) return
                val size = SpcData.measureBrrSize(ram, at)
                if (size > 0) depend(at, at + size)
            }
            for (slot in slots.filter { it in 0 until 42 }) {
                val entry = 0x6C00 + slot * 6
                depend(entry, entry + 6)
                val srcn = ram[entry].toInt() and 255
                if (srcn >= 40) continue // Noise does not consume BRR data.
                val dir = 0x6D00 + srcn * 4
                depend(dir, dir + 4)
                sample((ram[dir].toInt() and 255) or ((ram[dir + 1].toInt() and 255) shl 8))
                sample((ram[dir + 2].toInt() and 255) or ((ram[dir + 3].toInt() and 255) shl 8))
            }
            Bank(ram, loaded, protected, changed, dependencies.indices.filter { dependencies[it] })
        }
        val issues = mutableListOf<Issue>()
        val restorations = sortedMapOf<Int, MutableMap<Int, ByteArray>>()
        val checked = mutableSetOf<Pair<Int, Int>>()
        fun inspectByte(sourceSet: Int, targetSet: Int, at: Int, editedSet: Int) {
            val source = banks.getValue(sourceSet)
            val target = banks.getValue(targetSet)
            if (target.loaded[at] || target.protected[at] == 0 || source.ram[at] == target.ram[at]) return
            val resource = Resource.entries[target.protected[at] - 1]
            if (resource == Resource.INSTRUMENT) {
                val entry = 0x6C00 + (at - 0x6C00) / 6 * 6
                restorations.getOrPut(targetSet) { sortedMapOf() }[entry] = target.ram.copyOfRange(entry, entry + 6)
                return
            }
            val last = issues.lastOrNull()
            if (last != null && last.sourceSongSet == sourceSet && last.targetSongSet == targetSet &&
                last.editedSongSet == editedSet && last.resource == resource && last.endExclusive == at) {
                issues[issues.lastIndex] = last.copy(endExclusive = at + 1)
            } else issues += Issue(sourceSet, targetSet, resource, at, at + 1, editedSet)
        }
        for (sourceSet in resolved.keys.sorted()) {
            val source = banks.getValue(sourceSet)
            for (targetSet in banks.keys) {
                if (sourceSet == targetSet) continue
                checked += sourceSet to targetSet
                for (at in source.changed) inspectByte(sourceSet, targetSet, at, sourceSet)
            }
        }
        for (targetSet in resolved.keys.sorted()) {
            val target = banks.getValue(targetSet)
            for (sourceSet in banks.keys) {
                if (sourceSet == targetSet) continue
                checked += sourceSet to targetSet
                for (at in target.dependencies) inspectByte(sourceSet, targetSet, at, targetSet)
            }
        }
        return Report(issues.distinct(), checked.size, restorations)
    }

    fun editedSongSets(project: SmEditProject): List<Int> =
        (project.musicEdits.values.map { it.songSet } + project.musicInstrumentBanks.keys.map { it.toInt(16) }).distinct().sorted()

    private data class Bank(val ram: ByteArray, val loaded: BooleanArray, val protected: IntArray, val changed: List<Int>, val dependencies: List<Int>)
}
