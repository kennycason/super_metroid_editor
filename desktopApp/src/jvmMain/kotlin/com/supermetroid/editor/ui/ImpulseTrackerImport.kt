package com.supermetroid.editor.ui

import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.SpcData
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
    private const val SAMPLE_CVT_SIGNED = 0x01
    private const val SAMPLE_CVT_BIG_ENDIAN = 0x02
    private const val SAMPLE_CVT_DELTA = 0x04
    private const val SEQUENCE_ADDR = 0x582C
    private const val INSTRUMENT_TABLE_ADDR = 0x6C00
    private const val SAMPLE_DIRECTORY_ADDR = 0x6D00
    private const val SAMPLE_DATA_ADDR = 0x6E00
    private const val MAX_CUSTOM_SAMPLES = 40
    private const val MAX_INSTRUMENTS = 42
    private const val MITROID_C5_DIVISOR = 4186

    private val ATTACK_TABLE_MS = intArrayOf(4100, 2500, 1500, 1000, 640, 380, 260, 160, 96, 64, 40, 24, 16, 10, 6, 0)
    private val DECAY_TABLE_MS = intArrayOf(1200, 740, 440, 290, 180, 110, 74, 37)
    private val SUSTAIN_TABLE_MS = intArrayOf(
        65535, 38000, 28000, 24000, 19000, 14000, 12000, 9400,
        7100, 5900, 4700, 3500, 2900, 2400, 1800, 1500,
        1200, 880, 740, 590, 440, 370, 290, 220,
        180, 150, 110, 92, 74, 55, 37, 18
    )
    private val DECAY_MULTIPLIER = doubleArrayOf(0.724, 0.518, 0.378, 0.263, 0.181, 0.110, 0.052, 0.0)
    private val SUSTAIN_MULTIPLIER = doubleArrayOf(0.407, 0.623, 0.768, 0.876, 0.962, 1.032, 1.095, 1.149)

    data class Result(
        val song: NspcSequence.Song,
        val instruments: List<NspcRenderer.InstrumentEntry> = emptyList(),
        val report: MusicTrackInterchange.InterchangeReport,
        val nativePayload: MusicTrackInterchange.NativePayload? = null
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
        val instruments: List<Instrument>,
        val samples: List<Sample>,
        val patterns: List<Pattern>,
        val warnings: List<String>
    ) {
        val usesInstruments: Boolean get() = (flags and IT_FLAG_USE_INSTRUMENTS) != 0
    }

    data class Instrument(
        val index: Int,
        val name: String,
        val fileName: String,
        val fadeOut: Int,
        val globalVolume: Int,
        val defaultPan: Int,
        val useEnvelope: Boolean,
        val sustainLoop: Boolean,
        val envelopeNodes: List<EnvelopeNode>,
        val noteMap: IntArray,
        val sampleMap: IntArray
    ) {
        fun mappedNoteFor(note: Int): Int = noteMap.getOrElse(note.coerceIn(0, 119)) { note }.coerceIn(0, 119)
        fun sampleFor(note: Int): Int = sampleMap.getOrElse(note.coerceIn(0, 119)) { 0 }
    }

    data class EnvelopeNode(
        val volume: Int,
        val ticks: Int
    )

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
        val samplePointer: Int,
        val pcm: ShortArray? = null
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

    fun read(file: File, targetPlayIndex: Int? = null): Result {
        val module = parse(file.readBytes())
        val warnings = module.warnings.toMutableList()
        val sourceImport = convertToSourceImport(module, file.nameWithoutExtension, warnings)
        var customPlan = buildCustomSamplePlan(module, warnings, sourceImport.usedSampleIndexes)
        var song = remapSongInstruments(sourceImport.song, module, customPlan?.instrumentBySample)
        var instruments = customPlan?.instruments ?: emptyList()
        val nativeBuild = if (targetPlayIndex != null && customPlan != null) {
            buildNativePayload(song, customPlan, targetPlayIndex, file.name, warnings)
        } else {
            if (targetPlayIndex == null && customPlan != null) {
                warnings += "IT samples were decoded, but no target play index was supplied, so custom BRR native payload blocks were not built."
            }
            null
        }
        val nativePayload = nativeBuild?.payload
        if (nativeBuild != null) {
            song = nativeBuild.song
        }

        if (customPlan != null && nativePayload == null) {
            song = remapSongInstruments(sourceImport.song, module)
            instruments = emptyList()
        }

        require(song.channels.any { it.notes.isNotEmpty() }) { "IT file contains no importable note events" }
        return Result(
            song = song,
            instruments = instruments,
            report = MusicTrackInterchange.reportForSong(
                formatLabel = FORMAT_LABEL,
                fileName = file.name,
                song = song,
                warnings = warnings.distinct()
            ),
            nativePayload = nativePayload
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
        val instrumentOffsets = List(instrumentCount) { index -> input.u32At(instrumentTableStart + index * 4) }
        val sampleOffsets = List(sampleCount) { index -> input.u32At(sampleTableStart + index * 4) }
        val patternOffsets = List(patternCount) { index -> input.u32At(patternTableStart + index * 4) }
        val warnings = mutableListOf<String>()
        val instruments = instrumentOffsets.mapIndexedNotNull { index, offset ->
            parseInstrument(input, offset, index, warnings)
        }
        val samples = sampleOffsets.mapIndexedNotNull { index, offset ->
            parseSample(input, offset, index, warnings)
        }
        val patterns = patternOffsets.mapIndexed { index, offset ->
            parsePattern(input, offset, index)
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
                "IT has $activeSamples active sample(s) ($compressed compressed, $stereo stereo, $sixteenBit 16-bit, $looped looped)."
            } else {
                "IT has instrument/sample tables but no active embedded sample data."
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
            instruments = instruments,
            samples = samples,
            patterns = patterns,
            warnings = warnings
        )
    }

    private fun parseInstrument(
        input: LeReader,
        offset: Int,
        instrumentIndex: Int,
        warnings: MutableList<String>
    ): Instrument? {
        if (offset == 0) return null
        if (offset + 0x130 > input.size) {
            warnings += "Instrument ${instrumentIndex + 1} header overruns file data and was ignored."
            return null
        }
        if (input.stringAt(offset, 4) != "IMPI") {
            warnings += "Instrument ${instrumentIndex + 1} header is not an IT instrument header and was ignored."
            return null
        }

        val noteMap = IntArray(120)
        val sampleMap = IntArray(120)
        for (note in 0 until 120) {
            val tableOffset = offset + 0x40 + note * 2
            noteMap[note] = input.u8At(tableOffset).coerceIn(0, 119)
            sampleMap[note] = input.u8At(tableOffset + 1)
        }
        return Instrument(
            index = instrumentIndex + 1,
            name = input.stringAt(offset + 0x20, 26).ifBlank { "Instrument ${instrumentIndex + 1}" },
            fileName = input.stringAt(offset + 0x04, 12),
            fadeOut = input.u16At(offset + 0x14),
            globalVolume = input.u8At(offset + 0x18),
            defaultPan = input.u8At(offset + 0x19),
            useEnvelope = offset + 0x131 < input.size && (input.u8At(offset + 0x130) and 0x01) != 0,
            sustainLoop = offset + 0x131 < input.size && (input.u8At(offset + 0x130) and 0x04) != 0,
            envelopeNodes = parseEnvelopeNodes(input, offset),
            noteMap = noteMap,
            sampleMap = sampleMap
        )
    }

    private fun parseEnvelopeNodes(input: LeReader, instrumentOffset: Int): List<EnvelopeNode> {
        if (instrumentOffset + 0x136 > input.size) return emptyList()
        val nodeCount = input.u8At(instrumentOffset + 0x131).coerceIn(0, 25)
        val nodes = mutableListOf<EnvelopeNode>()
        for (nodeIndex in 0 until nodeCount) {
            val nodeOffset = instrumentOffset + 0x136 + nodeIndex * 3
            if (nodeOffset + 2 >= input.size) break
            nodes += EnvelopeNode(
                volume = input.u8At(nodeOffset),
                ticks = input.u16At(nodeOffset + 1)
            )
        }
        return nodes
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
        val associated = (flags and SAMPLE_FLAG_ASSOCIATED) != 0 && length > 0
        if (associated && samplePointer !in 0 until input.size) {
            warnings += "Sample ${sampleIndex + 1} points outside the IT file and cannot be converted."
        }
        val sampleName = input.stringAt(offset + 0x14, 26).ifBlank { "Sample ${sampleIndex + 1}" }
        val convertFlags = input.u8At(offset + 0x2E)
        val pcm = if (associated && samplePointer in 0 until input.size) {
            decodeSamplePcm(
                input = input,
                sampleIndex = sampleIndex + 1,
                sampleName = sampleName,
                flags = flags,
                convertFlags = convertFlags,
                length = length,
                samplePointer = samplePointer,
                warnings = warnings
            )
        } else {
            null
        }
        return Sample(
            index = sampleIndex + 1,
            name = sampleName,
            flags = flags,
            convertFlags = convertFlags,
            defaultVolume = input.u8At(offset + 0x13),
            globalVolume = input.u8At(offset + 0x11),
            length = length,
            loopStart = input.u32At(offset + 0x34).coerceAtLeast(0),
            loopEnd = input.u32At(offset + 0x38).coerceAtLeast(0),
            c5Speed = input.u32At(offset + 0x3C).coerceAtLeast(1),
            samplePointer = samplePointer,
            pcm = pcm
        )
    }

    private fun decodeSamplePcm(
        input: LeReader,
        sampleIndex: Int,
        sampleName: String,
        flags: Int,
        convertFlags: Int,
        length: Int,
        samplePointer: Int,
        warnings: MutableList<String>
    ): ShortArray? {
        val isCompressed = (flags and SAMPLE_FLAG_COMPRESSED) != 0
        val isStereo = (flags and SAMPLE_FLAG_STEREO) != 0
        val is16Bit = (flags and SAMPLE_FLAG_16_BIT) != 0
        if (isCompressed) {
            warnings += "Sample $sampleIndex '$sampleName' is IT-compressed; compressed IT sample decoding is not implemented yet, so custom BRR payload export was disabled."
            return null
        }
        if (isStereo) {
            warnings += "Sample $sampleIndex '$sampleName' is stereo; stereo IT sample conversion is not implemented yet, so custom BRR payload export was disabled."
            return null
        }

        val bytesPerSample = if (is16Bit) 2 else 1
        val byteLength = length * bytesPerSample
        if (samplePointer + byteLength > input.size) {
            warnings += "Sample $sampleIndex '$sampleName' data overruns the IT file and cannot be converted."
            return null
        }

        val signed = (convertFlags and SAMPLE_CVT_SIGNED) != 0
        val bigEndian = (convertFlags and SAMPLE_CVT_BIG_ENDIAN) != 0
        val delta = (convertFlags and SAMPLE_CVT_DELTA) != 0
        val out = ShortArray(length)
        var accumulator = 0
        for (i in 0 until length) {
            val offset = samplePointer + i * bytesPerSample
            var raw = if (is16Bit) {
                if (bigEndian) {
                    (input.u8At(offset) shl 8) or input.u8At(offset + 1)
                } else {
                    input.u8At(offset) or (input.u8At(offset + 1) shl 8)
                }
            } else {
                input.u8At(offset)
            }
            if (delta) {
                val mask = if (is16Bit) 0xFFFF else 0xFF
                accumulator = (accumulator + raw) and mask
                raw = accumulator
            }
            val sample = if (is16Bit) {
                if (signed) raw.toShort().toInt() else raw - 0x8000
            } else {
                val signed8 = if (signed) raw.toByte().toInt() else raw - 0x80
                signed8 shl 8
            }
            out[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun parsePattern(
        input: LeReader,
        offset: Int,
        patternIndex: Int
    ): Pattern {
        if (offset == 0) {
            return Pattern(rows = 64, channels = List(ROW_CHANNEL_LIMIT) { emptyList() })
        }
        require(offset + 8 <= input.size) { "IT pattern $patternIndex header overruns file data" }
        val packedLength = input.u16At(offset)
        val rows = input.u16At(offset + 2).coerceAtLeast(1)
        val dataStart = offset + 8
        val dataEnd = (dataStart + packedLength).coerceAtMost(input.size)
        require(dataStart <= dataEnd) { "IT pattern $patternIndex has invalid packed data length" }

        val channels = List(ROW_CHANNEL_LIMIT) { mutableListOf<Row>() }
        val masks = IntArray(ROW_CHANNEL_LIMIT) { 0 }
        val lastNote = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastInstrument = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastVolume = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastCommand = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        val lastValue = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        var pos = dataStart
        var rowIndex = 0

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

            channels[channel] += Row(
                row = rowIndex,
                channel = channel,
                note = note,
                instrument = instrument,
                volume = volume,
                command = command,
                value = value
            )
        }
        return Pattern(rows = rows, channels = channels)
    }

    private data class SourceImport(
        val song: NspcSequence.Song,
        val usedSampleIndexes: List<Int>,
        val sourceChannelCount: Int,
        val droppedDuplicateNotes: Int,
        val droppedOverlapNotes: Int
    )

    private fun convertToSourceImport(
        module: Module,
        fallbackTitle: String,
        warnings: MutableList<String>
    ): SourceImport {
        val sourceChannels = Array(ROW_CHANNEL_LIMIT) { mutableListOf<NspcSequence.Note>() }
        val tempoCommands = mutableListOf<NspcSequence.ControlCommand>()
        val active = arrayOfNulls<ActiveNote>(ROW_CHANNEL_LIMIT)
        val currentInstrument = IntArray(ROW_CHANNEL_LIMIT) { -1 }
        var tick = 0
        var skippedOrders = 0
        var currentSpeed = module.initialSpeed.coerceIn(1, 0x7F)
        val initialConvertedTempo = itTempoToNspc(module.initialTempo)
        var songTempo = initialConvertedTempo

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
            for (channelIndex in 0 until ROW_CHANNEL_LIMIT) {
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
                tempoCommands += NspcSequence.ControlCommand(tempoTick, 0xE7, intArrayOf(convertedTempo))
                if (tempoTick == 0 || songTempo == initialConvertedTempo) {
                    songTempo = convertedTempo
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

            for (channelIndex in 0 until ROW_CHANNEL_LIMIT) {
                for (row in pattern.channels[channelIndex]) {
                    if (row.row >= patternRows) continue
                    val rowTick = tick + rowTickOffsets[row.row.coerceIn(0, pattern.rows)]
                    if (row.instrument > 0) {
                        currentInstrument[channelIndex] = row.instrument
                    }
                    val sourceInstrument = if (row.instrument > 0) row.instrument else currentInstrument[channelIndex]
                    if (row.instrument > 0 && active[channelIndex] != null && row.note < 0) {
                        val activeNote = active[channelIndex] ?: continue
                        active[channelIndex] = active[channelIndex]?.copy(
                            instrument = resolveItPlayback(row.instrument, activeNote.sourceNote, module).sampleIndex
                        )
                    }
                    when {
                        row.note in 0..119 -> {
                            finishActive(sourceChannels, active, channelIndex, rowTick)
                            val playback = resolveItPlayback(sourceInstrument, row.note, module)
                            active[channelIndex] = ActiveNote(
                                startTick = rowTick,
                                sourceNote = row.note,
                                noteValue = itNoteToNspc(playback.note),
                                velocity = itVolumeToVelocity(
                                    row.volume,
                                    module.channelVolumes.getOrElse(channelIndex) { 64 },
                                    module.globalVolume
                                ),
                                instrument = playback.sampleIndex
                            )
                        }
                        row.note == IT_NOTE_OFF || row.note == IT_NOTE_CUT || row.note == IT_NOTE_FADE -> {
                            finishActive(sourceChannels, active, channelIndex, rowTick)
                        }
                    }
                }
            }
            tick += rowTickOffsets[patternRows]
            speedEvents.filter { it.first < patternRows }
                .maxByOrNull { it.first }
                ?.let { (_, speed) -> currentSpeed = speed.coerceIn(1, 0x7F) }
        }
        for (channelIndex in 0 until ROW_CHANNEL_LIMIT) {
            finishActive(sourceChannels, active, channelIndex, tick)
        }
        if (skippedOrders > 0) {
            warnings += "$skippedOrders IT order separator(s) were skipped."
        }

        val sourceChannelCount = sourceChannels.count { it.isNotEmpty() }
        val packed = packSourceChannels(
            sourceChannels = sourceChannels,
            tempo = songTempo,
            title = module.name.ifBlank { fallbackTitle },
            tempoCommands = tempoCommands
        )
        if (sourceChannelCount > EDITOR_CHANNEL_LIMIT) {
            warnings += "IT used $sourceChannelCount note channel(s); imported notes were voice-packed into ${EDITOR_CHANNEL_LIMIT} SNES channels."
        }
        if (packed.droppedDuplicateNotes > 0) {
            warnings += "Voice packing removed ${packed.droppedDuplicateNotes} simultaneous duplicate note(s)."
        }
        if (packed.droppedOverlapNotes > 0) {
            warnings += "Voice packing dropped ${packed.droppedOverlapNotes} overlapping note(s) that exceeded the 8-voice SNES limit."
        }
        return SourceImport(
            song = packed.song,
            usedSampleIndexes = packed.song.channels
                .flatMap { it.notes }
                .map { it.instrument }
                .filter { it > 0 }
                .distinct(),
            sourceChannelCount = sourceChannelCount,
            droppedDuplicateNotes = packed.droppedDuplicateNotes,
            droppedOverlapNotes = packed.droppedOverlapNotes
        )
    }

    private fun finishActive(
        sourceChannels: Array<MutableList<NspcSequence.Note>>,
        active: Array<ActiveNote?>,
        channelIndex: Int,
        endTick: Int
    ) {
        val note = active[channelIndex] ?: return
        val duration = (endTick - note.startTick).coerceAtLeast(1)
        sourceChannels[channelIndex] += NspcSequence.Note(
            tick = note.startTick,
            duration = duration,
            noteValue = note.noteValue,
            velocity = note.velocity,
            quantize = 7,
            instrument = note.instrument
        )
        active[channelIndex] = null
    }

    private data class PackedSong(
        val song: NspcSequence.Song,
        val droppedDuplicateNotes: Int,
        val droppedOverlapNotes: Int
    )

    private fun packSourceChannels(
        sourceChannels: Array<MutableList<NspcSequence.Note>>,
        tempo: Int,
        title: String,
        tempoCommands: List<NspcSequence.ControlCommand>
    ): PackedSong {
        val channelPriorities = sourceChannels.map { notes ->
            val uniquePitches = notes.mapTo(mutableSetOf()) { it.noteValue }.size
            val uniqueSamples = notes.mapTo(mutableSetOf()) { it.instrument }.size
            uniquePitches * 10_000 + uniqueSamples * 1_000 + notes.size.coerceAtMost(2_000)
        }
        val deduped = mutableListOf<Pair<NspcSequence.Note, Int>>()
        var droppedDuplicates = 0
        for ((_, notesAtTick) in sourceChannels
            .flatMapIndexed { channel, notes -> notes.map { it to channel } }
            .groupBy { it.first.tick }
            .toSortedMap()
        ) {
            val seen = mutableSetOf<String>()
            val sortedAtTick = notesAtTick.sortedWith(
                compareByDescending<Pair<NspcSequence.Note, Int>> { (note, channel) ->
                    channelPriorities[channel] + note.velocity * 100 + note.duration.coerceAtMost(127)
                }.thenBy { it.second }
            )
            for ((note, channel) in sortedAtTick) {
                val key = "${note.noteValue}:${note.instrument}:${note.duration}"
                if (!seen.add(key)) {
                    droppedDuplicates++
                    continue
                }
                deduped += note to channel
            }
        }

        val song = NspcSequence.Song(tempo = tempo, title = title, isModified = true)
        song.channels[0].commands += tempoCommands.map { it.copy(params = it.params.copyOf()) }
        val channelEndTick = IntArray(EDITOR_CHANNEL_LIMIT) { 0 }
        val channelLoad = IntArray(EDITOR_CHANNEL_LIMIT) { 0 }
        var droppedOverlaps = 0
        for ((note, _) in deduped.sortedWith(
            compareBy<Pair<NspcSequence.Note, Int>> { it.first.tick }
                .thenByDescending { (note, channel) ->
                    channelPriorities[channel] + note.velocity * 100 + note.duration.coerceAtMost(127)
                }
                .thenBy { it.second }
        )) {
            val freeChannels = (0 until EDITOR_CHANNEL_LIMIT).filter { channelEndTick[it] <= note.tick }
            if (freeChannels.isEmpty()) {
                droppedOverlaps++
                continue
            }
            val targetChannel = freeChannels.minWith(compareBy<Int> { channelLoad[it] }.thenBy { it })
            song.channels[targetChannel].notes += note.copy()
            channelEndTick[targetChannel] = note.endTick
            channelLoad[targetChannel]++
        }
        for (channel in song.channels) {
            channel.notes.sortWith(compareBy<NspcSequence.Note> { it.tick }.thenBy { it.noteValue })
            channel.commands.sortBy { it.tick }
        }
        return PackedSong(song, droppedDuplicates, droppedOverlaps)
    }

    private fun remapSongInstruments(
        sourceSong: NspcSequence.Song,
        module: Module,
        customInstrumentBySample: Map<Int, Int>? = null
    ): NspcSequence.Song {
        val out = NspcSequence.Song(
            tempo = sourceSong.tempo,
            title = sourceSong.title,
            isModified = sourceSong.isModified
        )
        for (channelIndex in 0 until EDITOR_CHANNEL_LIMIT) {
            out.channels[channelIndex].notes += sourceSong.channels[channelIndex].notes.map { note ->
                note.copy(instrument = mapItInstrument(note.instrument, module, customInstrumentBySample))
            }
            out.channels[channelIndex].commands += sourceSong.channels[channelIndex].commands.map {
                it.copy(params = it.params.copyOf())
            }
        }
        return out
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

    private data class CustomSamplePlan(
        val samples: List<Sample>,
        val instrumentBySample: Map<Int, Int>,
        val instruments: List<NspcRenderer.InstrumentEntry>,
        val brrBySample: Map<Int, ByteArray>,
        val loopBlockBySample: Map<Int, Int>
    )

    private fun buildCustomSamplePlan(
        module: Module,
        warnings: MutableList<String>,
        usedSamples: List<Int>
    ): CustomSamplePlan? {
        if (module.usesInstruments && module.instrumentCount > 0 && module.instruments.isEmpty()) {
            warnings += "Custom BRR payload was not built because IT instrument headers could not be decoded."
            return null
        }
        if (usedSamples.isEmpty()) return null
        val sampleByIndex = module.samples.associateBy { it.index }
        val missing = usedSamples.filter { sampleByIndex[it]?.pcm == null }
        if (missing.isNotEmpty()) {
            warnings += "Custom BRR payload was not built because used IT sample(s) ${missing.joinToString()} could not be decoded."
            return null
        }
        if (usedSamples.size > MAX_CUSTOM_SAMPLES) {
            warnings += "Custom BRR payload was not built because ${usedSamples.size} used samples exceed the SNES sample directory limit of $MAX_CUSTOM_SAMPLES."
            return null
        }

        val orderedSamples = usedSamples.mapNotNull { sampleByIndex[it] }
        val instrumentBySample = orderedSamples.mapIndexed { slot, sample -> sample.index to slot }.toMap()
        val brrBySample = linkedMapOf<Int, ByteArray>()
        val loopBlockBySample = linkedMapOf<Int, Int>()
        for (sample in orderedSamples) {
            val pcm = sample.pcm ?: continue
            val loopStart = if (sample.isLooped) sample.loopStart.coerceIn(0, pcm.size - 1) else -1
            val loopEnd = if (sample.isLooped) sample.loopEnd.coerceIn(loopStart + 1, pcm.size) else pcm.size
            val encodedPcm = if (sample.isLooped && loopStart >= 0 && loopEnd in 1..pcm.size) {
                pcm.copyOf(loopEnd)
            } else {
                pcm
            }
            val loopBlock = if (sample.isLooped && loopStart >= 0) loopStart / 16 else -1
            val brr = SpcData.encodeBrr(encodedPcm, loopBlock = loopBlock)
            if (brr.isEmpty()) {
                warnings += "Sample ${sample.index} '${sample.name}' encoded to empty BRR data and was ignored."
                return null
            }
            brrBySample[sample.index] = brr
            loopBlockBySample[sample.index] = loopBlock
        }

        val instruments = MutableList(MAX_INSTRUMENTS) { index ->
            NspcRenderer.InstrumentEntry(
                srcn = 0,
                adsr1 = 0x8F,
                adsr2 = 0xE0,
                gain = 0x7F,
                pitchAdj = 0x1000,
                index = index,
                tableAddr = INSTRUMENT_TABLE_ADDR + index * 6
            )
        }
        for ((sampleIndex, instrumentIndex) in instrumentBySample) {
            val sample = sampleByIndex.getValue(sampleIndex)
            val sourceInstrument = sourceInstrumentForSample(module, sample.index)
            val envelope = sourceInstrument?.let { buildMitroidEnvelope(it, module) }
            instruments[instrumentIndex] = instruments[instrumentIndex].copy(
                srcn = instrumentIndex,
                adsr1 = envelope?.first ?: 0,
                adsr2 = envelope?.second ?: 0,
                gain = if (envelope != null) 0 else 0x7F,
                pitchAdj = itC5SpeedToPitchAdjustment(sample.c5Speed)
            )
        }
        warnings += "Decoded ${orderedSamples.size} IT sample(s) for custom BRR payload export."
        if (module.usesInstruments) {
            warnings += "IT instrument note/sample maps were imported; envelopes, NNAs, and duplicate-note behavior are approximated."
        }
        warnings += "IT sample tuning/envelopes are approximated; exact tracker envelopes and vibrato are not converted yet."
        return CustomSamplePlan(orderedSamples, instrumentBySample, instruments, brrBySample, loopBlockBySample)
    }

    private fun sourceInstrumentForSample(module: Module, sampleIndex: Int): Instrument? =
        module.instruments.firstOrNull { it.sampleFor(60) == sampleIndex }
            ?: module.instruments.firstOrNull { instrument ->
                instrument.sampleMap.any { it == sampleIndex }
            }

    private fun buildMitroidEnvelope(instrument: Instrument, module: Module): Pair<Int, Int>? {
        val nodes = instrument.envelopeNodes
        if (!instrument.useEnvelope || nodes.size <= 3) return null
        val attackTicks = nodes[1].ticks - nodes[0].ticks
        val decayTicks = nodes[2].ticks - nodes[1].ticks
        var sustainTicks = nodes[3].ticks - nodes[2].ticks
        val sustainLevel = (nodes[2].volume / 8.0).roundToInt() - 1
        if (sustainLevel !in 0..7) return null

        val itTempo = itTempoToNspc(module.initialTempo) * 4.8
        val millisPerTick = 2500.0 / itTempo.coerceAtLeast(1.0)
        val attack = if (attackTicks == 1) {
            0x0F
        } else {
            findClosest(ATTACK_TABLE_MS, attackTicks * millisPerTick, 1.0)
        }
        val decay = findClosest(DECAY_TABLE_MS, decayTicks * millisPerTick, DECAY_MULTIPLIER[sustainLevel])
        val actualDecayTicks = ((DECAY_TABLE_MS[decay] * DECAY_MULTIPLIER[sustainLevel]) / millisPerTick).roundToInt()
        sustainTicks += decayTicks - actualDecayTicks
        val sustainRate = if (instrument.sustainLoop) {
            0
        } else {
            findClosest(SUSTAIN_TABLE_MS, sustainTicks * millisPerTick, SUSTAIN_MULTIPLIER[sustainLevel])
        }
        val adsr1 = 0x80 or (decay shl 4) or attack
        val adsr2 = (sustainLevel shl 5) or sustainRate
        return adsr1.coerceIn(0, 255) to adsr2.coerceIn(0, 255)
    }

    private fun findClosest(table: IntArray, value: Double, multiplier: Double): Int {
        var diff = Double.MAX_VALUE
        for (index in table.indices) {
            val newDiff = kotlin.math.abs(table[index] * multiplier - value)
            if (newDiff < diff) {
                diff = newDiff
            } else {
                return index - 1
            }
        }
        return table.lastIndex
    }

    private fun itC5SpeedToPitchAdjustment(c5Speed: Int): Int {
        if (c5Speed <= 0) return 0
        val mult = c5Speed / MITROID_C5_DIVISOR
        val mod = c5Speed % MITROID_C5_DIVISOR
        val sub = (255.0 * (mod.toDouble() / MITROID_C5_DIVISOR.toDouble())).roundToInt()
        return ((sub.coerceIn(0, 255) shl 8) or mult.coerceIn(0, 255)) and 0xFFFF
    }

    private data class NativePayloadBuild(
        val song: NspcSequence.Song,
        val payload: MusicTrackInterchange.NativePayload
    )

    private fun buildNativePayload(
        song: NspcSequence.Song,
        plan: CustomSamplePlan,
        targetPlayIndex: Int,
        sourceFileName: String,
        warnings: MutableList<String>
    ): NativePayloadBuild? {
        val sequenceBudget = INSTRUMENT_TABLE_ADDR - SEQUENCE_ADDR
        val fit = try {
            MusicSequenceBudget.fitSongToEncodedBudget(song, sequenceBudget)
        } catch (e: Exception) {
            warnings += "Custom BRR payload was not built because the imported sequence could not fit before the instrument table: ${e.message}"
            return null
        }
        if (fit.trimmed) {
            warnings += "Custom IT native payload tail-trimmed ${fit.removedNotes} notes and ${fit.removedCommands} commands after tick ${fit.cutoffTick} to fit ${fit.encodedBytes}/${fit.budgetBytes} sequence bytes."
        }

        val sequenceWrites = try {
            NspcSequence.encode(
                song = fit.song,
                playIndex = targetPlayIndex,
                spcRam = ByteArray(0x10000),
                failOnOverflow = true,
                conductorAddrOverride = SEQUENCE_ADDR,
                sequenceEndAddr = INSTRUMENT_TABLE_ADDR
            )
        } catch (e: Exception) {
            warnings += "Custom BRR payload was not built because N-SPC sequence encoding failed: ${e.message}"
            return null
        }

        val instrumentTable = ByteArray(MAX_INSTRUMENTS * 6)
        for (entry in plan.instruments) {
            val offset = entry.index * 6
            if (offset + 5 >= instrumentTable.size) continue
            val bytes = PianoRollPreviewLogic.instrumentBytes(entry)
            bytes.copyInto(instrumentTable, offset)
        }

        val sampleDirectory = ByteArray(MAX_CUSTOM_SAMPLES * 4)
        val sampleDataBlocks = mutableListOf<SpcData.TransferBlock>()
        var sampleAddr = SAMPLE_DATA_ADDR
        for ((slot, sample) in plan.samples.withIndex()) {
            val brr = plan.brrBySample.getValue(sample.index)
            if (sampleAddr + brr.size > 0x10000) {
                warnings += "Custom BRR payload was not built because converted sample data exceeds SPC RAM."
                return null
            }
            val loopBlock = plan.loopBlockBySample[sample.index] ?: -1
            val loopAddr = if (loopBlock >= 0) sampleAddr + loopBlock * 9 else sampleAddr
            writeLe16(sampleDirectory, slot * 4, sampleAddr)
            writeLe16(sampleDirectory, slot * 4 + 2, loopAddr.coerceIn(sampleAddr, sampleAddr + brr.size - 1))
            sampleDataBlocks += SpcData.TransferBlock(sampleAddr, brr)
            sampleAddr += brr.size
        }

        val blocks = mutableListOf<SpcData.TransferBlock>()
        for ((addr, data) in sequenceWrites.toSortedMap()) {
            blocks += SpcData.TransferBlock(addr, data)
        }
        blocks += SpcData.TransferBlock(INSTRUMENT_TABLE_ADDR, instrumentTable)
        blocks += SpcData.TransferBlock(SAMPLE_DIRECTORY_ADDR, sampleDirectory)
        blocks += sampleDataBlocks

        warnings += "Built custom IT native payload: ${plan.samples.size} BRR sample(s), ${blocks.size} transfer block(s), ${blocks.sumOf { it.data.size }} bytes."
        return NativePayloadBuild(
            song = fit.song,
            payload = MusicTrackInterchange.NativePayload(
                blocks = blocks,
                sourcePlayIndex = targetPlayIndex,
                sourceFileName = sourceFileName,
                formatLabel = "Impulse Tracker"
            )
        )
    }

    private data class ItPlayback(
        val note: Int,
        val sampleIndex: Int
    )

    private fun resolveItPlayback(instrument: Int, note: Int, module: Module): ItPlayback {
        if (module.usesInstruments) {
            val mappedInstrument = module.instruments.firstOrNull { it.index == instrument }
            if (mappedInstrument != null) {
                val mappedSample = mappedInstrument.sampleFor(note)
                if (mappedSample > 0) {
                    return ItPlayback(
                        note = mappedInstrument.mappedNoteFor(note),
                        sampleIndex = mappedSample
                    )
                }
            }
            val fallbackSample = module.samples.firstOrNull { it.associated }?.index ?: 1
            return ItPlayback(note = note, sampleIndex = fallbackSample)
        }

        val sampleIndex = when {
            instrument > 0 -> instrument
            module.samples.firstOrNull { it.associated } != null -> module.samples.first { it.associated }.index
            else -> 1
        }
        return ItPlayback(note = note, sampleIndex = sampleIndex)
    }

    private fun mapItInstrument(
        sampleIndex: Int,
        module: Module,
        customInstrumentBySample: Map<Int, Int>? = null
    ): Int {
        val sourceIndex = when {
            sampleIndex > 0 -> sampleIndex
            module.samples.firstOrNull { it.associated } != null -> module.samples.first { it.associated }.index
            else -> 1
        }
        customInstrumentBySample?.get(sourceIndex)?.let { return it }
        return 0x18 + ((sourceIndex - 1) % 0x0E)
    }

    private fun writeLe16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
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
        val sourceNote: Int,
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
