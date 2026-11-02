package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.rom.*

/** Project-owned resource management. Never treats an existing nonzero sound as spare. */
internal object SoundBankManagement {
    data class Sample(val srcn: Int, val name: String, val bytes: Int, val users: List<Int>)
    data class Overview(val freeBytes: Int, val largestGap: Int, val samples: List<Sample>, val emptySounds: List<Int>, val instruments: List<NspcRenderer.InstrumentEntry>, val creationProblem: String? = null,
        val soundNames: Map<Int, String> = emptyMap())

    fun inspect(parser: RomParser, target: SampleBankPreview.Target): Overview {
        val context = SampleBankPreview.load(parser, target)
        val project = target.project
        val resolved = MusicBankResolver.resolve(parser, project, target.songSet).ram
        val base = MusicBankResolver.sourceRam(parser, project, target.songSet, includeOverrides = false)
        val instruments = NspcRenderer.readInstrumentTable(resolved)
        val referenced = mutableSetOf<Int>()
        val plays = (SpcData.KNOWN_TRACKS.filter { it.songSet == target.songSet }.map { it.playIndex } +
            project.musicEdits.values.filter { it.songSet == target.songSet }.map { it.playIndex } + listOf(1, 2, 3, 4)).distinct()
        val added = project.musicTracks.values.filter { it.songSet == target.songSet }.map { it.playIndex }
        val usage = listOf(NativeSoundUsage.inspect(base, plays - added.toSet()), NativeSoundUsage.inspect(resolved, (plays + added).distinct()))
        for (result in usage) referenced += result.instruments
        if (usage.any { !it.complete }) referenced += 24..41
        fun music(ram: ByteArray, play: Int) {
            val song = NspcSequence.parse(ram, play)
            referenced += song.channels.flatMap { it.notes }.map { it.instrument }
            referenced += song.channels.flatMap { it.commands }.filter { it.command == 0xE0 }.mapNotNull { it.params.firstOrNull()?.takeIf { id -> id < 0x80 } }
        }
        for (play in (SpcData.KNOWN_TRACKS.filter { it.songSet == target.songSet }.map { it.playIndex } +
            project.musicEdits.values.filter { it.songSet == target.songSet }.map { it.playIndex }).distinct()) {
            if (project.musicTracks.values.none { it.songSet == target.songSet && it.playIndex == play }) music(base, play)
            music(resolved, play)
        }
        for (ram in listOf(SpcData.buildInitialSpcRam(parser), SoundEffects.bootRam(parser, project))) {
            referenced += SoundEffects.catalog(ram).flatMap { it.voices }.flatMap { it.steps }.flatMap { it.operands }
                .filter { it.field == SoundEffects.Field.SOUND }.map { it.value }
        }
        val bank = project.musicInstrumentBanks[MusicInstrumentBanks.key(target.songSet)]
        val samples = bank?.samples.orEmpty().values.sortedBy { it.srcn }.map { sample ->
            Sample(sample.srcn, sample.name, MusicSampleBanks.decode(sample).size,
                (0 until 42).filter { (resolved[0x6C00 + it * 6].toInt() and 255) == sample.srcn })
        }
        val empty = instruments.filter { it.index >= 24 && it.index !in referenced && PianoRollPreviewLogic.instrumentBytes(it).all { b -> b == 0.toByte() } }.map { it.index }
        return Overview(context.layout.totalBytes, context.layout.largestBytes, samples, empty, instruments,
            if (usage.any { !it.complete }) "New sound is unavailable because native resource use could not be fully resolved." else null,
            instruments.mapNotNull { entry -> project.musicInstrumentNames[MusicTrackEdit.key(target.songSet, entry.index)]?.let { entry.index to it } }.toMap())
    }
    fun duplicate(parser: RomParser, target: SampleBankPreview.Target, source: Int, destination: Int): MusicInstrumentBankEdit {
        val overview = inspect(parser, target)
        require(source != destination)
        val entry = overview.instruments.first { it.index == source }
        require(InstrumentDescriptions.playable(entry)) { "Choose a playable source sound" }
        require(overview.instruments.any { it.index == destination }) { "Choose an available destination slot" }
        val bank = MusicInstrumentBanks.copy(target.project.musicInstrumentBanks[MusicInstrumentBanks.key(target.songSet)]) ?: MusicInstrumentBankEdit()
        bank.instruments[destination] = MusicInstrumentBanks.entry(entry.copy(index = destination, tableAddr = 0x6C00 + destination * 6))
        validate(parser, target, bank)
        return bank
    }
    fun removeSample(parser: RomParser, target: SampleBankPreview.Target, srcn: Int): MusicInstrumentBankEdit {
        val sample = inspect(parser, target).samples.first { it.srcn == srcn }
        require(sample.users.isEmpty()) { "Sample is used by sounds ${sample.users.joinToString()}" }
        val bank = requireNotNull(MusicInstrumentBanks.copy(target.project.musicInstrumentBanks[MusicInstrumentBanks.key(target.songSet)]))
        bank.samples.remove(srcn)
        validate(parser, target, bank)
        return bank
    }
    fun create(parser: RomParser, target: SampleBankPreview.Target): Pair<Int, MusicInstrumentBankEdit> {
        val slot = inspect(parser, target).emptySounds.firstOrNull() ?: error("No empty native sound slots. Copy into an existing slot to replace it.")
        val sample = SampleAuthoring.encode(SampleAuthoring.generate(SampleAuthoring.Shape.SINE))
        val preview = SampleAuthoring.preview(SpcData.buildInitialSpcRam(parser), sample)
        val pitch = NspcRenderer.readInstrumentTable(preview.ram).first { it.index == 24 }.pitchAdj
        val preset = SoundPreset(java.util.UUID.randomUUID().toString(), "New sine", 0x8F, 0xE0, 127, pitch,
            java.util.Base64.getEncoder().encodeToString(sample.brr), sample.loopByteOffset)
        return slot to SoundPresets.plan(parser, target.copy(note = target.note.copy(instrument = slot)), preset).bank
    }
    private fun validate(parser: RomParser, target: SampleBankPreview.Target, bank: MusicInstrumentBankEdit) {
        val project = MusicBankTransitions.snapshot(target.project)
        project.musicInstrumentBanks[MusicInstrumentBanks.key(target.songSet)] = bank
        MusicBankResolver.resolve(parser, project, target.songSet)
    }
}
