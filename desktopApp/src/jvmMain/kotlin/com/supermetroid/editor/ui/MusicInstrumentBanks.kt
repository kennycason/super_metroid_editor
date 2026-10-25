package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.MusicInstrumentEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData

/** Reconciles old per-track settings without choosing an arbitrary winner. No project mutations. */
internal object MusicInstrumentBanks {
    fun key(songSet: Int) = songSet.toString(16).uppercase().padStart(2, '0')
    fun entry(value: NspcRenderer.InstrumentEntry) = MusicInstrumentEdit(
        value.index, value.tableAddr, value.srcn, value.adsr1, value.adsr2, value.gain, value.pitchAdj)
    fun native(value: MusicInstrumentEdit) = NspcRenderer.InstrumentEntry(
        value.srcn, value.adsr1, value.adsr2, value.gain, value.pitchAdj, value.index, value.tableAddr)
    fun copy(bank: MusicInstrumentBankEdit?) = bank?.copy(instruments = bank.instruments.toMutableMap())

    data class Choice(val trackKey: String, val trackName: String, val instrument: MusicInstrumentEdit)
    data class Conflict(val index: Int, val choices: List<Choice>)
    data class Inspection(val instruments: Map<Int, MusicInstrumentEdit>, val conflicts: List<Conflict>) {
        fun requireResolved() {
            require(conflicts.isEmpty()) { "Resolve shared instrument conflicts (${conflicts.joinToString { "#${it.index}" }}) in the sound editor before playback or export." }
        }
    }

    fun inspect(parser: RomParser, project: SmEditProject, songSet: Int): Inspection {
        project.musicInstrumentBanks[key(songSet)]?.let { bank ->
            bank.instruments.forEach { (index, value) -> validate(index, value) }
            return Inspection(bank.instruments.toMap(), emptyList())
        }
        val ram = SpcData.buildInitialSpcRam(parser).also {
            SpcData.applyTransferBlocks(it, SpcData.findSongSetTransferData(parser, songSet))
        }
        val stock = NspcRenderer.readInstrumentTable(ram).associate { it.index to entry(it) }
        val candidates = mutableMapOf<Int, MutableList<Choice>>()
        for ((trackKey, track) in project.musicEdits.toSortedMap().filterValues { it.songSet == songSet }) {
            val payload = track.nativePayload
            // Before baselines existed, retained native imports were always pristine.
            val baseline = if (payload != null) payload.baseline?.instruments?.associateBy { it.index } ?: continue else stock
            for (value in track.instruments) {
                validate(value.index, value)
                if (baseline[value.index] == value) continue
                // Missing raw-import slots may be placeholders, not deliberate patches.
                if (payload != null && baseline[value.index] == null) continue
                candidates.getOrPut(value.index) { mutableListOf() } += Choice(trackKey, track.trackName, value)
            }
        }
        val overrides = mutableMapOf<Int, MusicInstrumentEdit>()
        val conflicts = mutableListOf<Conflict>()
        for ((index, choices) in candidates.toSortedMap()) {
            val versions = choices.distinctBy { it.instrument }
            if (versions.size == 1) overrides[index] = versions.single().instrument
            else conflicts += Conflict(index, versions)
        }
        return Inspection(overrides, conflicts)
    }

    fun writes(instruments: Map<Int, MusicInstrumentEdit>): Map<Int, ByteArray> = instruments.map { (index, value) ->
        validate(index, value)
        value.tableAddr to PianoRollPreviewLogic.instrumentBytes(native(value))
    }.toMap()

    private fun validate(index: Int, value: MusicInstrumentEdit) {
        require(index in 0 until 42 && value.index == index && value.tableAddr == 0x6C00 + index * 6) { "Invalid shared instrument slot $index" }
        require(listOf(value.srcn, value.adsr1, value.adsr2, value.gain).all { it in 0..255 } && value.pitchAdj in 0..65535) { "Invalid shared instrument values for slot $index" }
    }
}
