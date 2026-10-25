package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.MusicInstrumentEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.NspcRenderer

/** Access and validation for the single bank-owned instrument model. No project mutations. */
internal object MusicInstrumentBanks {
    fun key(songSet: Int) = songSet.toString(16).uppercase().padStart(2, '0')
    fun entry(value: NspcRenderer.InstrumentEntry) = MusicInstrumentEdit(
        value.index, value.tableAddr, value.srcn, value.adsr1, value.adsr2, value.gain, value.pitchAdj)
    fun native(value: MusicInstrumentEdit) = NspcRenderer.InstrumentEntry(
        value.srcn, value.adsr1, value.adsr2, value.gain, value.pitchAdj, value.index, value.tableAddr)
    fun copy(bank: MusicInstrumentBankEdit?) = bank?.copy(instruments = bank.instruments.toMutableMap())

    fun instruments(project: SmEditProject, songSet: Int): Map<Int, MusicInstrumentEdit> {
        val instruments = project.musicInstrumentBanks[key(songSet)]?.instruments.orEmpty()
        instruments.forEach { (index, value) -> validate(index, value) }
        return instruments.toMap()
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
