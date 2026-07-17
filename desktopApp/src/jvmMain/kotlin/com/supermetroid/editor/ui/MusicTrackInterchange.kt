package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomConstants
import com.supermetroid.editor.rom.SpcData
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.midi.MetaMessage
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage
import kotlin.math.roundToInt

internal object MusicTrackInterchange {
    private const val MIDI_PPQ = 96
    private const val NSPC_VERSION = 1
    private const val SMEDIT_MIDI_INSTRUMENT_PREFIX = "SMEDIT:INST"
    private val NSPC_MAGIC = byteArrayOf(
        'S'.code.toByte(), 'M'.code.toByte(), 'E'.code.toByte(), 'D'.code.toByte(),
        'I'.code.toByte(), 'T'.code.toByte(), '_'.code.toByte(), 'N'.code.toByte(),
        'S'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte(), '_'.code.toByte(),
        'T'.code.toByte(), 'R'.code.toByte(), 'K'.code.toByte(), 0x1A
    )

    data class NativeTrack(
        val song: NspcSequence.Song,
        val instruments: List<NspcRenderer.InstrumentEntry> = emptyList(),
        val sourcePlayIndex: Int = -1,
        val formatLabel: String = "N-SPC",
        val report: InterchangeReport = reportForSong("N-SPC", "", song),
        val nativePayload: NativePayload? = null
    )

    data class NativePayload(
        val blocks: List<SpcData.TransferBlock>,
        val sourcePlayIndex: Int,
        val sourceFileName: String,
        val formatLabel: String
    )

    data class MidiReadResult(
        val song: NspcSequence.Song,
        val report: InterchangeReport
    )

    data class InterchangeReport(
        val formatLabel: String,
        val fileName: String,
        val noteCount: Int,
        val activeChannels: Int,
        val totalTicks: Int,
        val tempo: Int,
        val encodedBytes: Int,
        val warnings: List<String> = emptyList()
    ) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()

        fun compactSummary(): String {
            val warningSuffix = if (warnings.isEmpty()) "" else ", ${warnings.size} warning(s)"
            return "$formatLabel: $noteCount notes, $activeChannels ch, $totalTicks ticks, " +
                "$encodedBytes encoded bytes$warningSuffix"
        }
    }

    fun writeMidi(
        song: NspcSequence.Song,
        file: File,
        instruments: List<NspcRenderer.InstrumentEntry> = emptyList()
    ) {
        val sequence = Sequence(Sequence.PPQ, MIDI_PPQ)
        val metaTrack = sequence.createTrack()
        metaTrack.add(MidiEvent(trackNameMessage(song.title.ifBlank { file.nameWithoutExtension }), 0))
        metaTrack.add(MidiEvent(tempoMessage(song.tempo), 0))
        val programMap = buildMidiProgramMap(song, instruments)

        for (channelIndex in 0 until 8) {
            val channel = song.channels[channelIndex]
            if (channel.notes.isEmpty()) continue
            val track = sequence.createTrack()
            track.add(MidiEvent(trackNameMessage("Channel ${channelIndex + 1}"), 0))
            var lastInstrument = -1
            for (note in channel.notes.sortedBy { it.tick }) {
                val mapping = programMap[note.instrument] ?: midiProgramForInstrument(note.instrument, null, null)
                if (note.instrument != lastInstrument) {
                    track.add(MidiEvent(
                        smeditInstrumentMessage(
                            midiChannel = channelIndex,
                            smInstrument = note.instrument,
                            gmProgram = mapping.gmProgram,
                            srcn = instruments.getOrNull(note.instrument)?.srcn
                        ),
                        note.tick.toLong()
                    ))
                    track.add(MidiEvent(short(ShortMessage.PROGRAM_CHANGE, channelIndex, mapping.gmProgram, 0), note.tick.toLong()))
                    lastInstrument = note.instrument
                }
                val midiNote = nspcToMidi(note.noteValue)
                val velocity = ((note.velocity.coerceIn(1, 15) * 127) / 15).coerceIn(1, 127)
                track.add(MidiEvent(short(ShortMessage.NOTE_ON, channelIndex, midiNote, velocity), note.tick.toLong()))
                track.add(MidiEvent(short(ShortMessage.NOTE_OFF, channelIndex, midiNote, 0), note.endTick.toLong()))
            }
        }

        MidiSystem.write(sequence, 1, file)
    }

    fun readMidi(file: File): NspcSequence.Song =
        readMidiWithReport(file).song

    fun readMidiWithReport(file: File): MidiReadResult {
        val sequence = MidiSystem.getSequence(file)
        val song = NspcSequence.Song(
            tempo = readNspcTempo(sequence) ?: 36,
            title = file.nameWithoutExtension,
            isModified = true
        )
        val tickScale = if (sequence.divisionType == Sequence.PPQ && sequence.resolution > 0) {
            MIDI_PPQ.toDouble() / sequence.resolution.toDouble()
        } else {
            1.0
        }

        val channelPrograms = IntArray(16) { 0 }
        val smeditProgramEvents = readSmeditInstrumentEvents(sequence)
        val active = mutableMapOf<Pair<Int, Int>, MutableList<ActiveNote>>()
        val foldedChannels = mutableSetOf<Int>()
        var clampedPitches = 0
        var ignoredShortEvents = 0
        var ignoredMetaEvents = 0
        val events = sequence.tracks.flatMap { track ->
            (0 until track.size()).map { track.get(it) }
        }.sortedBy { it.tick }

        for (event in events) {
            val meta = event.message as? MetaMessage
            if (meta != null) {
                if (meta.type != 0x03 && meta.type != 0x2F && meta.type != 0x51 && parseSmeditInstrumentMeta(meta) == null) {
                    ignoredMetaEvents++
                }
                continue
            }
            val message = event.message as? ShortMessage ?: continue
            val midiChannel = message.channel
            val editorChannel = midiChannel % 8
            when (message.command) {
                ShortMessage.PROGRAM_CHANGE -> {
                    if (midiChannel >= 8) foldedChannels += midiChannel
                    channelPrograms[midiChannel] = smeditProgramEvents[event.tick to midiChannel]
                        ?: message.data1.coerceIn(0, 127)
                }
                ShortMessage.NOTE_ON -> {
                    if (midiChannel >= 8) foldedChannels += midiChannel
                    val note = midiToNspc(message.data1)
                    if (note != midiToNspcUnchecked(message.data1)) clampedPitches++
                    val key = editorChannel to note
                    val tick = midiTickToNspc(event.tick, tickScale)
                    if (message.data2 > 0) {
                        active.getOrPut(key) { mutableListOf() } += ActiveNote(
                            startTick = tick,
                            velocity = ((message.data2.coerceIn(1, 127) * 15) / 127).coerceIn(1, 15),
                            instrument = channelPrograms[midiChannel]
                        )
                    } else {
                        finishMidiNote(song, active, key, tick)
                    }
                }
                ShortMessage.NOTE_OFF -> {
                    if (midiChannel >= 8) foldedChannels += midiChannel
                    val note = midiToNspc(message.data1)
                    if (note != midiToNspcUnchecked(message.data1)) clampedPitches++
                    val key = editorChannel to note
                    finishMidiNote(song, active, key, midiTickToNspc(event.tick, tickScale))
                }
                else -> ignoredShortEvents++
            }
        }

        for ((key, notes) in active.toList()) {
            val endTick = notes.maxOfOrNull { it.startTick + MIDI_PPQ } ?: continue
            while (notes.isNotEmpty()) finishMidiNote(song, active, key, endTick)
        }

        sortSong(song)
        require(song.channels.any { it.notes.isNotEmpty() }) { "MIDI file contains no note events" }
        val warnings = mutableListOf<String>()
        if (sequence.divisionType != Sequence.PPQ) {
            warnings += "MIDI file is not PPQ-based; tick timing was imported as-is."
        }
        if (foldedChannels.isNotEmpty()) {
            warnings += "MIDI channels ${foldedChannels.sorted().joinToString()} were folded into the 8 SNES voices."
        }
        if (clampedPitches > 0) {
            warnings += "$clampedPitches note event(s) were clamped to Super Metroid's C1-B6 range."
        }
        if (ignoredShortEvents > 0) {
            warnings += "$ignoredShortEvents unsupported MIDI event(s) were ignored."
        }
        if (ignoredMetaEvents > 0) {
            warnings += "$ignoredMetaEvents unsupported MIDI metadata event(s) were ignored."
        }
        return MidiReadResult(song, reportForSong("MIDI", file.name, song, warnings))
    }

    fun writeNspcTrack(
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>,
        songSet: Int,
        playIndex: Int,
        file: File
    ) {
        val out = ByteArrayOutputStream()
        out.write(NSPC_MAGIC)
        out.writeU16(NSPC_VERSION)
        out.writeU16(songSet)
        out.writeU16(playIndex)
        out.writeU16(song.tempo)
        out.writeString(song.title)
        out.writeU16(instruments.size)
        for (entry in instruments) {
            out.writeU16(entry.index)
            out.writeU16(entry.tableAddr)
            out.writeU8(entry.srcn)
            out.writeU8(entry.adsr1)
            out.writeU8(entry.adsr2)
            out.writeU8(entry.gain)
            out.writeU16(entry.pitchAdj)
        }
        for (channel in song.channels) {
            val notes = channel.notes.sortedBy { it.tick }
            val commands = channel.commands.sortedBy { it.tick }
            out.writeU32(notes.size)
            for (note in notes) {
                out.writeU32(note.tick)
                out.writeU16(note.duration)
                out.writeU16(note.noteValue)
                out.writeU8(note.velocity)
                out.writeU8(note.quantize)
                out.writeU16(note.instrument)
            }
            out.writeU32(commands.size)
            for (command in commands) {
                out.writeU32(command.tick)
                out.writeU8(command.command)
                out.writeU8(command.params.size)
                for (param in command.params) out.writeU8(param)
            }
        }
        file.writeBytes(out.toByteArray())
    }

    fun readNative(file: File, selectedPlayIndex: Int): NativeTrack {
        val data = file.readBytes()
        return when {
            data.size >= NSPC_MAGIC.size && data.copyOfRange(0, NSPC_MAGIC.size).contentEquals(NSPC_MAGIC) ->
                readNspcTrack(data, file)
            file.extension.equals("spc", ignoreCase = true) ->
                readSpcTrack(data, selectedPlayIndex, file)
            file.extension.equals("nspc", ignoreCase = true) ->
                readRawNspcTransferTrack(data, selectedPlayIndex, file)
            else ->
                throw IllegalArgumentException("Unsupported native track file: ${file.name}")
        }
    }

    fun readSpcRam(file: File): ByteArray =
        NativeSpcEmulator.readSpcRam(file.readBytes())

    private fun readNspcTrack(data: ByteArray, file: File): NativeTrack {
        val input = LeReader(data, NSPC_MAGIC.size)
        val version = input.u16()
        require(version == NSPC_VERSION) { "Unsupported N-SPC track version: $version" }
        input.u16() // song set is advisory; selected track supplies the project key.
        val playIndex = input.u16()
        val song = NspcSequence.Song(
            tempo = input.u16().coerceIn(1, 255),
            title = input.string().ifBlank { file.nameWithoutExtension },
            isModified = true
        )
        val instruments = MutableList(input.u16()) {
            val index = input.u16()
            val tableAddr = input.u16()
            NspcRenderer.InstrumentEntry(
                index = index,
                tableAddr = tableAddr,
                srcn = input.u8(),
                adsr1 = input.u8(),
                adsr2 = input.u8(),
                gain = input.u8(),
                pitchAdj = input.u16()
            )
        }
        for (channelIndex in 0 until 8) {
            val channel = song.channels[channelIndex]
            repeat(input.u32()) {
                channel.notes += NspcSequence.Note(
                    tick = input.u32(),
                    duration = input.u16().coerceAtLeast(1),
                    noteValue = input.u16().coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX),
                    velocity = input.u8().coerceIn(0, 15),
                    quantize = input.u8().coerceIn(0, 7),
                    instrument = input.u16().coerceIn(0, 255)
                )
            }
            repeat(input.u32()) {
                val tick = input.u32()
                val command = input.u8()
                val params = IntArray(input.u8()) { input.u8() }
                channel.commands += NspcSequence.ControlCommand(tick, command, params)
            }
        }
        sortSong(song)
        return NativeTrack(
            song = song,
            instruments = instruments,
            sourcePlayIndex = playIndex,
            formatLabel = "N-SPC",
            report = reportForSong("N-SPC", file.name, song)
        )
    }

    private fun readSpcTrack(data: ByteArray, selectedPlayIndex: Int, file: File): NativeTrack {
        val ram = NativeSpcEmulator.readSpcRam(data)
        val candidates = (listOf(selectedPlayIndex) + (0..15)).filter { it >= 0 }.distinct()
        for (playIndex in candidates) {
            val song = NspcSequence.parse(ram, playIndex)
            val noteCount = song.channels.sumOf { it.notes.size }
            if (noteCount > 0) {
                song.title = file.nameWithoutExtension
                song.isModified = true
                return NativeTrack(
                    song = song,
                    instruments = NspcRenderer.readInstrumentTable(ram),
                    sourcePlayIndex = playIndex,
                    formatLabel = "SPC",
                    report = reportForSong(
                        formatLabel = "SPC",
                        fileName = file.name,
                        song = song,
                        warnings = listOf("SPC import reads compatible N-SPC sequence data from SPC RAM; arbitrary SPC dumps may not be editable.")
                    )
                )
            }
        }
        throw IllegalArgumentException("No compatible N-SPC song was found in ${file.name}")
    }

    private fun readRawNspcTransferTrack(data: ByteArray, selectedPlayIndex: Int, file: File): NativeTrack {
        val blocks = parseRawTransferBlocks(data, file.name)
        val ram = ByteArray(RomConstants.SPC_RAM_SIZE)
        SpcData.applyTransferBlocks(ram, blocks)
        val candidates = (listOf(selectedPlayIndex, 5) + (0..31)).filter { it >= 0 }.distinct()
        for (playIndex in candidates) {
            val song = NspcSequence.parse(ram, playIndex)
            val noteCount = song.channels.sumOf { it.notes.size }
            if (noteCount > 0) {
                song.title = file.nameWithoutExtension
                song.isModified = true
                val warnings = listOf(
                    "Raw N-SPC transfer data was imported as a native payload; ROM export will preserve its transfer blocks.",
                    "If you edit this song in the piano roll after applying it, SMEDIT will switch back to editable sequence export."
                )
                return NativeTrack(
                    song = song,
                    instruments = NspcRenderer.readInstrumentTable(ram),
                    sourcePlayIndex = playIndex,
                    formatLabel = "Raw N-SPC",
                    report = reportForSong("Raw N-SPC", file.name, song, warnings),
                    nativePayload = NativePayload(
                        blocks = blocks,
                        sourcePlayIndex = playIndex,
                        sourceFileName = file.name,
                        formatLabel = "Raw N-SPC"
                    )
                )
            }
        }
        throw IllegalArgumentException("No compatible N-SPC song table entry was found in raw transfer data: ${file.name}")
    }

    internal fun parseRawTransferBlocks(data: ByteArray, fileName: String = "raw.nspc"): List<SpcData.TransferBlock> {
        val blocks = mutableListOf<SpcData.TransferBlock>()
        var pos = 0
        while (true) {
            require(pos + 2 <= data.size) { "Raw N-SPC transfer chain has no terminator: $fileName" }
            val size = readU16(data, pos)
            pos += 2
            if (size == 0) break
            require(pos + 2 <= data.size) { "Raw N-SPC block is missing a destination address: $fileName" }
            val dest = readU16(data, pos)
            pos += 2
            require(dest + size <= RomConstants.SPC_RAM_SIZE) {
                "Raw N-SPC block exceeds SPC RAM at 0x${dest.toString(16).padStart(4, '0')}: $fileName"
            }
            require(pos + size <= data.size) { "Raw N-SPC block overruns file data: $fileName" }
            blocks += SpcData.TransferBlock(dest, data.copyOfRange(pos, pos + size))
            pos += size
        }
        require(blocks.isNotEmpty()) { "Raw N-SPC transfer chain contains no blocks: $fileName" }
        return blocks
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private data class MidiProgramMapping(
        val gmProgram: Int
    )

    private data class MidiInstrumentStats(
        val noteCount: Int,
        val minMidiNote: Int,
        val maxMidiNote: Int,
        val averageMidiNote: Double,
        val averageDuration: Double,
        val maxDuration: Int
    )

    private fun buildMidiProgramMap(
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>
    ): Map<Int, MidiProgramMapping> {
        val stats = song.channels
            .flatMap { it.notes }
            .groupBy { it.instrument }
            .mapValues { (_, notes) ->
                val midiNotes = notes.map { nspcToMidi(it.noteValue) }
                MidiInstrumentStats(
                    noteCount = notes.size,
                    minMidiNote = midiNotes.minOrNull() ?: 60,
                    maxMidiNote = midiNotes.maxOrNull() ?: 60,
                    averageMidiNote = midiNotes.average(),
                    averageDuration = notes.map { it.duration }.average(),
                    maxDuration = notes.maxOfOrNull { it.duration } ?: 0
                )
            }
        return stats.keys.associateWith { instrument ->
            midiProgramForInstrument(instrument, instruments.getOrNull(instrument), stats[instrument])
        }
    }

    private fun midiProgramForInstrument(
        smInstrument: Int,
        entry: NspcRenderer.InstrumentEntry?,
        stats: MidiInstrumentStats?
    ): MidiProgramMapping {
        val averageMidi = stats?.averageMidiNote ?: 60.0
        val averageDuration = stats?.averageDuration ?: 48.0
        val maxDuration = stats?.maxDuration ?: 48
        val sustained = averageDuration >= 42.0 || maxDuration >= 96
        val short = averageDuration <= 18.0
        val wideRange = stats?.let { it.maxMidiNote - it.minMidiNote >= 24 } ?: false
        val srcn = entry?.srcn ?: -1

        val program = when {
            averageMidi < 40.0 -> if (short) 39 else 38       // synth bass 2 / synth bass 1
            averageMidi < 48.0 -> if (sustained) 38 else 33   // synth bass 1 / fingered bass
            short && averageMidi > 72.0 -> 11                 // vibraphone-like bright plucks
            short -> 45                                      // pizzicato strings
            sustained && averageMidi > 72.0 -> 88             // new age pad
            sustained && !wideRange && srcn >= 29 -> 52       // choir aahs
            sustained && srcn >= 32 -> 90                     // polysynth pad
            sustained -> 48                                  // string ensemble 1
            averageMidi > 72.0 -> 80                          // square lead
            smInstrument in 0x18..0x25 -> DEFAULT_SM_GM_PROGRAMS[smInstrument] ?: 81
            else -> 81                                       // saw lead
        }.coerceIn(0, 127)

        return MidiProgramMapping(program)
    }

    private fun smeditInstrumentMessage(
        midiChannel: Int,
        smInstrument: Int,
        gmProgram: Int,
        srcn: Int?
    ): MetaMessage {
        val text = "$SMEDIT_MIDI_INSTRUMENT_PREFIX ch=$midiChannel sm=$smInstrument gm=$gmProgram srcn=${srcn ?: -1}"
        return textMessage(text)
    }

    private fun readSmeditInstrumentEvents(sequence: Sequence): Map<Pair<Long, Int>, Int> {
        val events = mutableMapOf<Pair<Long, Int>, Int>()
        for (track in sequence.tracks) {
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val meta = event.message as? MetaMessage ?: continue
                val parsed = parseSmeditInstrumentMeta(meta) ?: continue
                events[event.tick to parsed.first] = parsed.second
            }
        }
        return events
    }

    private fun parseSmeditInstrumentMeta(meta: MetaMessage): Pair<Int, Int>? {
        if (meta.type != 0x01) return null
        val text = meta.data.decodeToString()
        if (!text.startsWith(SMEDIT_MIDI_INSTRUMENT_PREFIX)) return null
        val channel = Regex("""\bch=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val instrument = Regex("""\bsm=(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return channel.coerceIn(0, 15) to instrument.coerceIn(0, 255)
    }

    private val DEFAULT_SM_GM_PROGRAMS = mapOf(
        0x18 to 48, // strings / pad bed
        0x19 to 52, // voice-like sustain
        0x1A to 80, // bright lead
        0x1B to 38, // synth bass
        0x1C to 39, // synth bass 2
        0x1D to 88, // soft pad
        0x1E to 81, // saw lead
        0x1F to 32, // acoustic bass
        0x20 to 90, // polysynth pad
        0x21 to 87, // bass + lead
        0x22 to 48, // strings
        0x23 to 45, // pizzicato
        0x24 to 46, // orchestral harp
        0x25 to 80  // square lead
    )

    private fun readNspcTempo(sequence: Sequence): Int? {
        if (sequence.divisionType != Sequence.PPQ || sequence.resolution <= 0) return null
        for (track in sequence.tracks) {
            for (i in 0 until track.size()) {
                val meta = track.get(i).message as? MetaMessage ?: continue
                if (meta.type == 0x51 && meta.data.size >= 3) {
                    val usPerQuarter = ((meta.data[0].toInt() and 0xFF) shl 16) or
                        ((meta.data[1].toInt() and 0xFF) shl 8) or
                        (meta.data[2].toInt() and 0xFF)
                    if (usPerQuarter > 0) {
                        return (512000.0 * MIDI_PPQ / usPerQuarter).roundToInt().coerceIn(1, 255)
                    }
                }
            }
        }
        return null
    }

    private fun finishMidiNote(
        song: NspcSequence.Song,
        active: MutableMap<Pair<Int, Int>, MutableList<ActiveNote>>,
        key: Pair<Int, Int>,
        endTick: Int
    ) {
        val notes = active[key] ?: return
        val started = notes.removeFirstOrNull() ?: return
        val duration = (endTick - started.startTick).coerceAtLeast(1)
        song.channels[key.first].notes += NspcSequence.Note(
            tick = started.startTick,
            duration = duration,
            noteValue = key.second,
            velocity = started.velocity,
            quantize = 7,
            instrument = started.instrument
        )
        if (notes.isEmpty()) active.remove(key)
    }

    private data class ActiveNote(
        val startTick: Int,
        val velocity: Int,
        val instrument: Int
    )

    private fun midiTickToNspc(tick: Long, scale: Double): Int =
        (tick * scale).roundToInt().coerceAtLeast(0)

    private fun nspcToMidi(noteValue: Int): Int =
        (noteValue - NspcSequence.NOTE_MIN + 24).coerceIn(0, 127)

    private fun midiToNspc(midiNote: Int): Int =
        (midiNote - 24 + NspcSequence.NOTE_MIN).coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX)

    private fun midiToNspcUnchecked(midiNote: Int): Int =
        midiNote - 24 + NspcSequence.NOTE_MIN

    private fun reportForSong(
        formatLabel: String,
        fileName: String,
        song: NspcSequence.Song,
        warnings: List<String> = emptyList()
    ): InterchangeReport =
        InterchangeReport(
            formatLabel = formatLabel,
            fileName = fileName,
            noteCount = song.channels.sumOf { it.notes.size },
            activeChannels = song.channels.count { it.notes.isNotEmpty() },
            totalTicks = song.totalTicks,
            tempo = song.tempo,
            encodedBytes = NspcSequence.encodedSequenceSize(song),
            warnings = warnings
        )

    private fun tempoMessage(nspcTempo: Int): MetaMessage {
        val usPerQuarter = (512000.0 * MIDI_PPQ / nspcTempo.coerceIn(1, 255)).roundToInt().coerceAtLeast(1)
        val data = byteArrayOf(
            ((usPerQuarter shr 16) and 0xFF).toByte(),
            ((usPerQuarter shr 8) and 0xFF).toByte(),
            (usPerQuarter and 0xFF).toByte()
        )
        return MetaMessage().also { it.setMessage(0x51, data, data.size) }
    }

    private fun trackNameMessage(name: String): MetaMessage {
        val data = name.encodeToByteArray()
        return MetaMessage().also { it.setMessage(0x03, data, data.size) }
    }

    private fun textMessage(text: String): MetaMessage {
        val data = text.encodeToByteArray()
        return MetaMessage().also { it.setMessage(0x01, data, data.size) }
    }

    private fun short(command: Int, channel: Int, data1: Int, data2: Int): ShortMessage =
        ShortMessage().also { it.setMessage(command, channel.coerceIn(0, 15), data1.coerceIn(0, 127), data2.coerceIn(0, 127)) }

    private fun sortSong(song: NspcSequence.Song) {
        for (channel in song.channels) {
            channel.notes.sortWith(compareBy<NspcSequence.Note> { it.tick }.thenBy { it.noteValue })
            channel.commands.sortBy { it.tick }
        }
    }

    private fun ByteArrayOutputStream.writeU8(value: Int) {
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.encodeToByteArray()
        writeU16(bytes.size)
        write(bytes)
    }

    private class LeReader(
        private val data: ByteArray,
        private var pos: Int = 0
    ) {
        fun u8(): Int {
            require(pos < data.size) { "Unexpected end of N-SPC track data" }
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int {
            val lo = u8()
            val hi = u8()
            return lo or (hi shl 8)
        }

        fun u32(): Int {
            val b0 = u8()
            val b1 = u8()
            val b2 = u8()
            val b3 = u8()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        fun string(): String {
            val len = u16()
            require(pos + len <= data.size) { "String overruns N-SPC track data" }
            val text = data.copyOfRange(pos, pos + len).decodeToString()
            pos += len
            return text
        }
    }
}
