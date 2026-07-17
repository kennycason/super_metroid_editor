package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicChannelEdit
import com.supermetroid.editor.data.MusicCommandEdit
import com.supermetroid.editor.data.MusicInstrumentEdit
import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicNoteEdit
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.MusicTransferBlockEdit
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import java.util.Base64

internal object MusicEditConversion {
    fun key(songSet: Int, playIndex: Int): String = MusicTrackEdit.key(songSet, playIndex)

    fun toProjectEdit(
        track: SpcData.TrackInfo,
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>,
        nativePayload: MusicNativePayloadEdit? = null
    ): MusicTrackEdit {
        val channels = MutableList(8) { ch ->
            val source = song.channels.getOrNull(ch)
            MusicChannelEdit(
                notes = source?.notes.orEmpty().map {
                    MusicNoteEdit(
                        tick = it.tick,
                        duration = it.duration,
                        noteValue = it.noteValue,
                        velocity = it.velocity,
                        quantize = it.quantize,
                        instrument = it.instrument
                    )
                }.toMutableList(),
                commands = source?.commands.orEmpty().map {
                    MusicCommandEdit(
                        tick = it.tick,
                        command = it.command,
                        params = it.params.toList()
                    )
                }.toMutableList()
            )
        }

        return MusicTrackEdit(
            songSet = track.songSet,
            playIndex = track.playIndex,
            trackName = track.name,
            tempo = song.tempo,
            channels = channels.toMutableList(),
            instruments = instruments.map {
                MusicInstrumentEdit(
                    index = it.index,
                    tableAddr = it.tableAddr,
                    srcn = it.srcn,
                    adsr1 = it.adsr1,
                    adsr2 = it.adsr2,
                    gain = it.gain,
                    pitchAdj = it.pitchAdj
                )
            }.toMutableList(),
            nativePayload = nativePayload
        )
    }

    fun toSong(edit: MusicTrackEdit): NspcSequence.Song {
        val song = NspcSequence.Song(tempo = edit.tempo, title = edit.trackName, isModified = true)
        for (ch in 0 until 8) {
            val source = edit.channels.getOrNull(ch) ?: continue
            song.channels[ch].notes.addAll(source.notes.map {
                NspcSequence.Note(
                    tick = it.tick,
                    duration = it.duration,
                    noteValue = it.noteValue,
                    velocity = it.velocity,
                    quantize = it.quantize,
                    instrument = it.instrument
                )
            })
            song.channels[ch].commands.addAll(source.commands.map {
                NspcSequence.ControlCommand(
                    tick = it.tick,
                    command = it.command,
                    params = it.params.toIntArray()
                )
            })
        }
        return song
    }

    fun toInstrumentEntries(edit: MusicTrackEdit): List<NspcRenderer.InstrumentEntry> =
        edit.instruments.map {
            NspcRenderer.InstrumentEntry(
                srcn = it.srcn,
                adsr1 = it.adsr1,
                adsr2 = it.adsr2,
                gain = it.gain,
                pitchAdj = it.pitchAdj,
                index = it.index,
                tableAddr = it.tableAddr
            )
        }

    fun toProjectNativePayload(payload: MusicTrackInterchange.NativePayload): MusicNativePayloadEdit {
        val encoder = Base64.getEncoder()
        return MusicNativePayloadEdit(
            formatLabel = payload.formatLabel,
            sourceFileName = payload.sourceFileName,
            sourcePlayIndex = payload.sourcePlayIndex,
            blocks = payload.blocks.map {
                MusicTransferBlockEdit(
                    destAddr = it.destAddr,
                    dataBase64 = encoder.encodeToString(it.data)
                )
            }.toMutableList()
        )
    }

    fun toTransferBlocks(payload: MusicNativePayloadEdit): List<SpcData.TransferBlock> {
        val decoder = Base64.getDecoder()
        return payload.blocks.map {
            SpcData.TransferBlock(
                destAddr = it.destAddr and 0xFFFF,
                data = decoder.decode(it.dataBase64)
            )
        }
    }
}
