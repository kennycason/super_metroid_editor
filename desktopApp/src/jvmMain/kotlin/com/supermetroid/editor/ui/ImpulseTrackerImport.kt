package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import java.io.File
import kotlin.math.roundToInt

internal object ImpulseTrackerImport {
    private const val FORMAT_LABEL = "Impulse Tracker"
    private const val IT_NOTE_OFF = 255
    private const val IT_NOTE_CUT = 254
    private const val IT_NOTE_FADE = 253
    private const val ROW_CHANNEL_LIMIT = 64
    private const val EDITOR_CHANNEL_LIMIT = 8
    private const val IT_FLAG_USE_INSTRUMENTS = 0x04
    private const val SAMPLE_FLAG_ASSOCIATED = 0x01
    private const val SAMPLE_FLAG_16_BIT = 0x02
    private const val SAMPLE_FLAG_STEREO = 0x04
    private const val SAMPLE_FLAG_COMPRESSED = 0x08
    private const val SAMPLE_FLAG_LOOP = 0x10

    data class Result(
        val song: NspcSequence.Song,
        val instruments: List<NspcRenderer.InstrumentEntry> = emptyList(),
        val report: MusicTrackInterchange.InterchangeReport
    )

    data class Module(
        val name: String,
        val orders: List<Int>,
        val initialSpeed: Int,
        val initialTempo: Int,
        val globalVolume: Int,
        val flags: Int,
        val channelVolumes: List<Int>,
        val instrumentCount: Int,
        val sampleCount: Int,
        val samples: List<Sample>,
        val patterns: List<Pattern>,
        val warnings: List<String>
    ) {
        val usesInstruments: Boolean get() = (flags and IT_FLAG_USE_INSTRUMENTS) != 0
    }

    data class Sample(
        val index: Int,
        val name: String,
        val flags: Int,
        val convertFlags: Int,
        val defaultVolume: Int,
        val globalVolume: Int,
        val length: Int,
        val loopStart: Int,
        val loopEnd: Int,
        val c5Speed: Int,
        val samplePointer: Int
    ) {
        val associated: Boolean get() = (flags and SAMPLE_FLAG_ASSOCIATED) != 0 && length > 0
        val is16Bit: Boolean get() = (flags and SAMPLE_FLAG_16_BIT) != 0
        val isStereo: Boolean get() = (flags and SAMPLE_FLAG_STEREO) != 0
        val isCompressed: Boolean get() = (flags and SAMPLE_FLAG_COMPRESSED) != 0
        val isLooped: Boolean get() = (flags and SAMPLE_FLAG_LOOP) != 0 && loopEnd > loopStart
    }

    data class Pattern(
        val rows: Int,
        val channels: List<List<Row>>
    )

    data class Row(
        val row: Int,
        val channel: Int,
        val note: Int = -1,
        val instrument: Int = -1,
        val volume: Int = -1,
        val command: Int = -1,
        val value: Int = -1
    )

    fun read(file: File): Result {
        val module = parse(file.readBytes())
        val warnings = module.warnings.toMutableList()
        val song = convertToSong(module, file.nameWithoutExtension, warnings)
        require(song.channels.any { it.notes.isNotEmpty() }) { "IT file contains no importable note events" }
        return Result(
            song = song,
            report = MusicTrackInterchange.reportForSong(
                formatLabel = FORMAT_LABEL,
                fileName = file.name,
                song = song,
                warnings = warnings.distinct()
            )
        )
    }

    fun parse(data: ByteArray): Module {
        val input = LeReader(data)
        require(input.stringAt(0, 4) == "IMPM") { "The selected file is not an Impulse Tracker module" }
        val name = input.stringAt(4, 26).ifBlank { "Impulse Tracker Module" }
        val orderCount = input.u16At(0x20)
        val instrumentCount = input.u16At(0x22)
        val sampleCount = input.u16At(0x24)
        val patternCount = input.u16At(0x26)
        val flags = input.u16At(0x2C)
        val globalVolume = input.u8At(0x30)
        val initialSpeed = input.u8At(0x32).coerceAtLeast(1)
        val initialTempo = input.u8At(0x33).coerceAtLeast(1)
        val channelVolumes = input.bytesAt(0x80, ROW_CHANNEL_LIMIT).map { it.toInt() and 0xFF }
        val ordersStart = 0xC0
        val instrumentTableStart = ordersStart + orderCount
        val sampleTableStart = instrumentTableStart + instrumentCount * 4
        val patternTableStart = sampleTableStart + sampleCount * 4
        require(patternTableStart + patternCount * 4 <= data.size) { "IT header tables overrun file data" }

        val orders = input.bytesAt(ordersStart, orderCount).map { it.toInt() and 0xFF }
        val sampleOffsets = List(sampleCount) { index -> input.u32At(sampleTableStart + index * 4) }
        val patternOffsets = List(patternCount) { index -> input.u32At(patternTableStart + index * 4) }
        val warnings = mutableListOf<String>()
        val samples = sampleOffsets.mapIndexedNotNull { index, offset ->
            parseSample(input, offset, index, warnings)
        }
        val patterns = patternOffsets.mapIndexed { index, offset ->
            parsePattern(input, offset, index, warnings)
        }

        if (orders.count { it != 0xFF && it != 0xFE } == 0) {
            warnings += "IT order list contains no playable patterns."
        }
        if (instrumentCount > 0 || sampleCount > 0) {
            val activeSamples = samples.count { it.associated }
            val compressed = samples.count { it.associated && it.isCompressed }
            val stereo = samples.count { it.associated && it.isStereo }
            val sixteenBit = samples.count { it.associated && it.is16Bit }
            val looped = samples.count { it.associated && it.isLooped }
            warnings += if (activeSamples > 0) {
                "IT has $activeSamples active sample(s) ($compressed compressed, $stereo stereo, $sixteenBit 16-bit, $looped looped); custom BRR sample import is not wired yet, so notes were mapped to Super Metroid instrument slots."
            } else {
                "IT instruments/samples were not imported as custom BRR data yet; notes were mapped to Super Metroid instrument slots."
            }
        }
        return Module(
            name = name,
            orders = orders,
            initialSpeed = initialSpeed,
            initialTempo = initialTempo,
            globalVolume = globalVolume,
            flags = flags,
            channelVolumes = channelVolumes,
            instrumentCount = instrumentCount,
            sampleCount = sampleCount,
            samples = samples,
            patterns = patterns,
            warnings = warnings
        )
    }

    private fun parseSample(
        input: LeReader,
        offset: Int,
        sampleIndex: Int,
        warnings: MutableList<String>
    ): Sample? {
        if (offset == 0) return null
        if (offset + 0x50 > input.size) {
            warnings += "Sample ${sampleIndex + 1} header overruns file data and was ignored."
            return null
        }
        if (input.stringAt(offset, 4) != "IMPS") {
            warnings += "Sample ${sampleIndex + 1} header is not an IT sample header and was ignored."
            return null
        }
        val flags = input.u8At(offset + 0x12)
        val length = input.u32At(offset + 0x30).coerceAtLeast(0)
        val samplePointer = input.u32At(offset + 0x48).coerceAtLeast(0)
        if ((flags and SAMPLE_FLAG_ASSOCIATED) != 0 && length > 0 && samplePointer !in 0 until input.size) {
            warnings += "Sample ${sampleIndex + 1} points outside the IT file and cannot be converted."
        }
        return Sample(
            index = sampleIndex + 1,
            name = input.stringAt(offset + 0x14, 26).ifBlank { "Sample ${sampleIndex + 1}" },
            flags = flags,
            convertFlags = input.u8At(offset + 0x2E),
            defaultVolume = input.u8At(offset + 0x13),
            globalVolume = input.u8At(offset + 0x11),
            length = length,
            loopStart = input.u32At(offset + 0x34).coerceAtLeast(0),
            loopEnd = input.u32At(offset + 0x38).coerceAtLeast(0),
            c5Speed = input.u32At(offset + 0x3C).coerceAtLeast(1),
            samplePointer = samplePointer
        )
    }

    private fun parsePattern(
        input: LeReader,
        offset: Int,
        patternIndex: Int,
        warnings: MutableList<String>
    ): Pattern {
        if (offset == 0) {
            return Pattern(rows = 64, channels = List(EDITOR_CHANNEL_LIMIT) { emptyList() })
        }
        require(offset + 8 <= input.size) { "IT pattern $patternIndex header overruns file data" }
        val packedLength = input.u16At(offset)
        val rows = input.u16At(offset + 2).coerceAtLeast(1)
        val dataStart = offset + 8
        val dataEnd = (dataStart + packedLength).coerceAtMost(input.size)
        require(dataStart <= dataEnd) { "IT pattern $patternIndex has invalid packed data length" }

        val channels = List(EDITOR_CHANNEL_LIMIT) { mutableListOf<Row>() }
        val masks = IntArray(ROW_CHANNEL_LIMIT) { 0 }
        val lastNote = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastInstrument = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastVolume = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastCommand = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastValue = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        var pos = dataStart
        var rowIndex = 0
        var ignoredHighChannelEvents = 0

        while (pos < dataEnd && rowIndex < rows) {
            val channelVariable = input.u8At(pos++)
            if (channelVariable == 0) {
                rowIndex++
                continue
            }
            val channel = (channelVariable - 1) and 63
            val mask = if ((channelVariable and 0x80) != 0) {
                require(pos < dataEnd) { "IT pattern $patternIndex mask overruns packed data" }
                input.u8At(pos++).also { masks[channel] = it }
            } else {
                masks[channel]
            }

            var note = -1
            var instrument = -1
            var volume = -1
            var command = -1
            var value = -1
            if ((mask and 0x01) != 0) {
                require(pos < dataEnd) { "IT pattern $patternIndex note overruns packed data" }
                note = input.u8At(pos++)
                lastNote[channel] = note
            }
            if ((mask and 0x02) != 0) {
                require(pos < dataEnd) { "IT pattern $patternIndex instrument overruns packed data" }
                instrument = input.u8At(pos++)
                lastInstrument[channel] = instrument
            }
            if ((mask and 0x04) != 0) {
                require(pos < dataEnd) { "IT pattern $patternIndex volume overruns packed data" }
                volume = input.u8At(pos++)
                lastVolume[channel] = volume
            }
            if ((mask and 0x08) != 0) {
                require(pos + 1 < dataEnd) { "IT pattern $patternIndex command overruns packed data" }
                command = input.u8At(pos++)
                value = input.u8At(pos++)
                lastCommand[channel] = command
                lastValue[channel] = value
            }
            if ((mask and 0x10) != 0) note = lastNote[channel]
            if ((mask and 0x20) != 0) instrument = lastInstrument[channel]
            if ((mask and 0x40) != 0) volume = lastVolume[channel]
            if ((mask and 0x80) != 0) {
                command = lastCommand[channel]
                value = lastValue[channel]
            }

            if (channel < EDITOR_CHANNEL_LIMIT) {
                channels[channel] += Row(
                    row = rowIndex,
                    channel = channel,
                    note = note,
                    instrument = instrument,
                    volume = volume,
                    command = command,
                    value = value
                )
            } else {
                ignoredHighChannelEvents++
            }
        }
        if (ignoredHighChannelEvents > 0) {
            warnings += "Pattern $patternIndex used channels above 8; $ignoredHighChannelEvents event(s) were ignored."
        }
        return Pattern(rows = rows, channels = channels)
    }

    private fun convertToSong(
        module: Module,
        fallbackTitle: String,
        warnings: MutableList<String>
    ): NspcSequence.Song {
        val song = NspcSequence.Song(
            tempo = itTempoToNspc(module.initialTempo),
            title = module.name.ifBlank { fallbackTitle },
            isModified = true
        )
        val active = arrayOfNulls<ActiveNote>(EDITOR_CHANNEL_LIMIT)
        var tick = 0
        var skippedOrders = 0
        var currentSpeed = module.initialSpeed.coerceIn(1, 0x7F)

        for (order in module.orders) {
            when (order) {
                0xFF -> break
                0xFE -> {
                    skippedOrders++
                    continue
                }
            }
            val pattern = module.patterns.getOrNull(order)
            if (pattern == null) {
                warnings += "Order references missing pattern $order; it was skipped."
                continue
            }
            val tempoEvents = mutableListOf<Pair<Int, Int>>()
            val speedEvents = mutableListOf<Pair<Int, Int>>()
            val patternBreakRows = mutableListOf<Int>()
            val unsupportedEffects = mutableMapOf<Char, Int>()
            for (channelIndex in 0 until EDITOR_CHANNEL_LIMIT) {
                for (row in pattern.channels[channelIndex]) {
                    if (row.command > 0) {
                        val commandLetter = 'A'.code + row.command - 1
                        when (commandLetter.toChar()) {
                            'T' -> if (row.value > 0) tempoEvents += row.row to row.value
                            'A' -> if (row.value > 0) speedEvents += row.row to row.value
                            'C' -> patternBreakRows += row.row + 1
                            'B' -> unsupportedEffects.merge('B', 1, Int::plus)
                            else -> unsupportedEffects.merge(commandLetter.toChar(), 1, Int::plus)
                        }
                    }
                }
            }

            val rowTickOffsets = buildRowTickOffsets(pattern.rows, currentSpeed, speedEvents)
            val patternRows = patternBreakRows.minOrNull()?.coerceIn(0, pattern.rows) ?: pattern.rows

            for ((rowIndex, tempo) in tempoEvents.sortedBy { it.first }) {
                if (rowIndex >= patternRows) continue
                val convertedTempo = itTempoToNspc(tempo)
                val tempoTick = tick + rowTickOffsets[rowIndex.coerceIn(0, pattern.rows)]
                song.channels[0].commands += NspcSequence.ControlCommand(tempoTick, 0xE7, intArrayOf(convertedTempo))
                if (tempoTick == 0 || song.tempo == itTempoToNspc(module.initialTempo)) {
                    song.tempo = convertedTempo
                }
            }
            if (tempoEvents.isNotEmpty()) {
                warnings += "IT tempo commands were imported as Super Metroid tempo commands; playback may still differ for effect-heavy tracks."
            }
            if (speedEvents.isNotEmpty()) {
                warnings += "IT speed commands were used to place editable notes, but Super Metroid playback does not preserve speed changes directly."
            }
            if (unsupportedEffects.isNotEmpty()) {
                val summary = unsupportedEffects.entries.sortedBy { it.key }.joinToString { "${it.key}xx=${it.value}" }
                warnings += "Unsupported IT effects were ignored: $summary."
            }

            for (channelIndex in 0 until EDITOR_CHANNEL_LIMIT) {
                for (row in pattern.channels[channelIndex]) {
                    if (row.row >= patternRows) continue
                    val rowTick = tick + rowTickOffsets[row.row.coerceIn(0, pattern.rows)]
                    if (row.instrument > 0 && active[channelIndex] != null && row.note < 0) {
                        active[channelIndex] = active[channelIndex]?.copy(instrument = mapItInstrument(row.instrument, module))
                    }
                    when {
                        row.note in 1..119 -> {
                            finishActive(song, active, channelIndex, rowTick)
                            active[channelIndex] = ActiveNote(
                                startTick = rowTick,
                                noteValue = itNoteToNspc(row.note),
                                velocity = itVolumeToVelocity(
                                    row.volume,
                                    module.channelVolumes.getOrElse(channelIndex) { 64 },
                                    module.globalVolume
                                ),
                                instrument = mapItInstrument(row.instrument, module)
                            )
                        }
                        row.note == IT_NOTE_OFF || row.note == IT_NOTE_CUT || row.note == IT_NOTE_FADE -> {
                            finishActive(song, active, channelIndex, rowTick)
                        }
                    }
                }
            }
            tick += rowTickOffsets[patternRows]
            speedEvents.filter { it.first < patternRows }
                .maxByOrNull { it.first }
                ?.let { (_, speed) -> currentSpeed = speed.coerceIn(1, 0x7F) }
        }
        for (channelIndex in 0 until EDITOR_CHANNEL_LIMIT) {
            finishActive(song, active, channelIndex, tick)
        }
        if (skippedOrders > 0) {
            warnings += "$skippedOrders IT order separator(s) were skipped."
        }
        for (channel in song.channels) {
            channel.notes.sortWith(compareBy<NspcSequence.Note> { it.tick }.thenBy { it.noteValue })
            channel.commands.sortBy { it.tick }
        }
        return song
    }

    private fun finishActive(
        song: NspcSequence.Song,
        active: Array<ActiveNote?>,
        channelIndex: Int,
        endTick: Int
    ) {
        val note = active[channelIndex] ?: return
        val duration = (endTick - note.startTick).coerceAtLeast(1)
        song.channels[channelIndex].notes += NspcSequence.Note(
            tick = note.startTick,
            duration = duration,
            noteValue = note.noteValue,
            velocity = note.velocity,
            quantize = 7,
            instrument = note.instrument
        )
        active[channelIndex] = null
    }

    private fun itNoteToNspc(note: Int): Int =
        (note + 104).coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX)

    private fun itTempoToNspc(tempo: Int): Int =
        (tempo.coerceAtLeast(1) / 4.85).roundToInt().coerceIn(1, 255)

    private fun itVolumeToVelocity(volume: Int, channelVolume: Int, globalVolume: Int): Int {
        val rowVolume = if (volume in 0..64) volume else 64
        val scaled = rowVolume.coerceIn(0, 64) *
            channelVolume.coerceIn(0, 64) *
            globalVolume.coerceIn(0, 128)
        return ((scaled * 15) / (64 * 64 * 128)).coerceIn(1, 15)
    }

    private fun mapItInstrument(instrument: Int, module: Module): Int {
        val sourceIndex = when {
            instrument > 0 -> instrument
            module.samples.firstOrNull { it.associated } != null -> module.samples.first { it.associated }.index
            else -> 1
        }
        return 0x18 + ((sourceIndex - 1) % 0x0E)
    }

    private fun buildRowTickOffsets(
        rows: Int,
        initialSpeed: Int,
        speedEvents: List<Pair<Int, Int>>
    ): IntArray {
        val offsets = IntArray(rows + 1)
        val speedByRow = speedEvents
            .filter { (row, speed) -> row in 0 until rows && speed > 0 }
            .groupBy({ it.first }, { it.second })
        var speed = initialSpeed.coerceIn(1, 0x7F)
        var tick = 0
        for (row in 0 until rows) {
            offsets[row] = tick
            speedByRow[row]?.lastOrNull()?.let { speed = it.coerceIn(1, 0x7F) }
            tick += speed
        }
        offsets[rows] = tick
        return offsets
    }

    private data class ActiveNote(
        val startTick: Int,
        val noteValue: Int,
        val velocity: Int,
        val instrument: Int
    )

    private class LeReader(private val data: ByteArray) {
        val size: Int get() = data.size

        fun u8At(offset: Int): Int {
            require(offset in data.indices) { "Unexpected end of IT data" }
            return data[offset].toInt() and 0xFF
        }

        fun u16At(offset: Int): Int {
            require(offset + 1 < data.size) { "Unexpected end of IT data" }
            return u8At(offset) or (u8At(offset + 1) shl 8)
        }

        fun u32At(offset: Int): Int {
            require(offset + 3 < data.size) { "Unexpected end of IT data" }
            return u8At(offset) or (u8At(offset + 1) shl 8) or
                (u8At(offset + 2) shl 16) or (u8At(offset + 3) shl 24)
        }

        fun bytesAt(offset: Int, length: Int): ByteArray {
            require(length >= 0 && offset >= 0 && offset + length <= data.size) { "IT byte range overruns file data" }
            return data.copyOfRange(offset, offset + length)
        }

        fun stringAt(offset: Int, length: Int): String =
            bytesAt(offset, length).decodeToString().trim('\u0000', ' ')
    }
}
