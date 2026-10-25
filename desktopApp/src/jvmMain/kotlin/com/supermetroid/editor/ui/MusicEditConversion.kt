package com.supermetroid.editor.ui

import com.supermetroid.editor.data.MusicChannelEdit
import com.supermetroid.editor.data.MusicCommandEdit
import com.supermetroid.editor.data.MusicNativeBaselineEdit
import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicNoteEdit
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.data.MusicTransferBlockEdit
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
import java.util.Base64

internal object MusicEditConversion {
    fun key(songSet: Int, playIndex: Int): String = MusicTrackEdit.key(songSet, playIndex)

    fun toProjectEdit(
        track: SpcData.TrackInfo,
        song: NspcSequence.Song,
        nativePayload: MusicNativePayloadEdit? = null
    ): MusicTrackEdit {
        return MusicTrackEdit(
            songSet = track.songSet,
            playIndex = track.playIndex,
            trackName = track.name,
            tempo = song.tempo,
            channels = channelsOf(song),
            nativePayload = copyNativePayload(nativePayload)
        )
    }

    private fun channelsOf(song: NspcSequence.Song) = MutableList(8) { ch ->
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

    fun nativeBaselineSong(edit: MusicTrackEdit): NspcSequence.Song {
        val baseline = requireNotNull(edit.nativePayload) { "No retained native import" }.baseline
        return toSong(edit.copy(tempo = baseline.tempo, channels = baseline.channels))
    }

    fun copyNativePayload(payload: MusicNativePayloadEdit?): MusicNativePayloadEdit? = payload?.copy(
        blocks = payload.blocks.toMutableList(),
        baseline = payload.baseline.copy(channels = copyChannels(payload.baseline.channels))
    )

    private fun copyChannels(channels: List<MusicChannelEdit>) = channels.map { channel ->
        channel.copy(notes = channel.notes.toMutableList(), commands = channel.commands.map {
            it.copy(params = it.params.toList())
        }.toMutableList())
    }.toMutableList()

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

    /** Capture the editable baseline once, when the user explicitly imports a native source. */
    fun toProjectNativePayload(payload: MusicTrackInterchange.NativePayload, song: NspcSequence.Song): MusicNativePayloadEdit {
        val encoder = Base64.getEncoder()
        return MusicNativePayloadEdit(
            formatLabel = payload.formatLabel,
            sourceFileName = payload.sourceFileName,
            sourcePlayIndex = payload.sourcePlayIndex,
            baseline = MusicNativeBaselineEdit(tempo = song.tempo, channels = channelsOf(song)),
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
            val data = decoder.decode(it.dataBase64)
            require(it.destAddr in 0 until 0x10000 && data.size <= 0x10000 - it.destAddr) { "Native music block exceeds SPC RAM" }
            SpcData.TransferBlock(
                destAddr = it.destAddr,
                data = data
            )
        }
    }
}
