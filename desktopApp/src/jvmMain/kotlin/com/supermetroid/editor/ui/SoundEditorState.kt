package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

class SoundEditorState {
    private val player = SoundPlayer()

    var selectedTrackId by mutableStateOf(-1)
        private set
    var selectedTrack by mutableStateOf<SpcData.TrackInfo?>(null)
        private set
    var selectedSampleIndex by mutableStateOf(-1)
        private set
    var selectedSample by mutableStateOf<DecodedSample?>(null)
        private set
    var currentWaveform by mutableStateOf<ShortArray?>(null)
        private set
    var currentLoopStart by mutableStateOf(-1)
        private set
    var sampleRate by mutableStateOf(32000)
    var loopEnabled by mutableStateOf(false)
    var autoPlayEnabled by mutableStateOf(false)
    var playAllEnabled by mutableStateOf(false)
    var statusMessage by mutableStateOf("")
        private set
    var isLoadingSamples by mutableStateOf(false)
        private set
    var isLoadingTrack by mutableStateOf(false)
        private set
    var samples by mutableStateOf<List<DecodedSample>>(emptyList())
        private set
    var trackSamples by mutableStateOf<List<DecodedSample>>(emptyList())
        private set
    var trackUniqueSamples by mutableStateOf<List<DecodedSample>>(emptyList())
        private set

    var playbackPosition by mutableStateOf(0f)
    var isPlaying by mutableStateOf(false)
        private set

    /** Song set of the last selected track (persists when drilling into a sample). */
    var currentSongSet by mutableStateOf(-1)
        private set

    // ─── Piano roll / track editing state ───────────────────────────
    var editingSong by mutableStateOf<NspcSequence.Song?>(null)
        private set
    var pianoRollChannel by mutableStateOf(0)
    var isPianoRollOpen by mutableStateOf(false)
        private set
    var pianoRollPlaybackTick by mutableStateOf(-1)
    private var pianoRollPlayStartMs = 0L
    private var pianoRollTickMs = 0.0

    /** Open the piano roll editor for the current track. */
    fun openPianoRoll(romParser: RomParser) {
        val track = selectedTrack ?: run {
            System.err.println("[SPC-PIANO] openPianoRoll: no track selected")
            return
        }
        System.err.println("[SPC-PIANO] Opening piano roll for '${track.name}' " +
            "songSet=0x${track.songSet.toString(16)} playIndex=${track.playIndex}")
        val ram = buildSpcRamForTrack(romParser, track.songSet)
        val song = NspcSequence.parse(ram, track.playIndex)
        song.title = track.name
        editingSong = song
        pianoRollChannel = 0
        pianoRollPlaybackTick = -1
        isPianoRollOpen = true

        val totalNotes = song.channels.sumOf { it.notes.size }
        val totalCmds = song.channels.sumOf { it.commands.size }
        val activeChannels = song.channels.count { it.notes.isNotEmpty() }
        System.err.println("[SPC-PIANO] Parsed: $totalNotes notes, $totalCmds commands, " +
            "$activeChannels active channels, ${song.totalTicks} ticks, tempo=${song.tempo}")
        for (ch in 0 until 8) {
            val notes = song.channels[ch].notes
            val cmds = song.channels[ch].commands
            if (notes.isNotEmpty() || cmds.isNotEmpty()) {
                val instrSet = notes.map { it.instrument }.toSet()
                val range = if (notes.isNotEmpty()) ", range ${NspcSequence.noteToName(notes.minOf { it.noteValue })}-${NspcSequence.noteToName(notes.maxOf { it.noteValue })}" else ""
                System.err.println("[SPC-PIANO]   Ch $ch: ${notes.size} notes, ${cmds.size} cmds, instruments=$instrSet$range")
            }
        }
        statusMessage = "Editing: ${track.name} ($totalNotes notes, ${song.totalTicks} ticks)"
    }

    /** Close piano roll, return to waveform view. */
    fun closePianoRoll() {
        System.err.println("[SPC-PIANO] Closing piano roll")
        isPianoRollOpen = false
        pianoRollPlaybackTick = -1
    }

    /** Reset piano roll to original track data. */
    fun resetPianoRoll(romParser: RomParser) {
        val track = selectedTrack ?: return
        System.err.println("[SPC-PIANO] Resetting to original track data")
        val ram = buildSpcRamForTrack(romParser, track.songSet)
        val song = NspcSequence.parse(ram, track.playIndex)
        song.title = track.name
        editingSong = song
        pianoRollPlaybackTick = -1
        statusMessage = "Reset to original"
    }

    /** Start piano roll playback with tick tracking. */
    fun startPianoRollPlayback(wav: ShortArray) {
        val song = editingSong ?: return
        pianoRollTickMs = 512.0 / song.tempo.coerceAtLeast(1)
        pianoRollPlayStartMs = System.currentTimeMillis()
        pianoRollPlaybackTick = 0
        val peak = if (wav.isNotEmpty()) wav.maxOf { kotlin.math.abs(it.toInt()) } else 0
        System.err.println("[SPC-PIANO] Starting playback: ${wav.size} samples (${wav.size / 32000}s), " +
            "tempo=${song.tempo}, tickMs=${"%.1f".format(pianoRollTickMs)}, peak=$peak")
        playWaveform(wav)
    }

    /** Update piano roll playback tick based on elapsed time. */
    fun updatePianoRollTick() {
        if (!isPlaying || pianoRollPlaybackTick < 0) return
        val elapsedMs = System.currentTimeMillis() - pianoRollPlayStartMs
        val tick = (elapsedMs / pianoRollTickMs).toInt()
        val maxTick = editingSong?.totalTicks ?: 0
        pianoRollPlaybackTick = if (tick > maxTick) {
            stopPlayback()
            -1
        } else {
            tick
        }
    }

    /** Notify that the song was modified (triggers recompose). */
    fun notifySongChanged(song: NspcSequence.Song) {
        val prevNotes = editingSong?.channels?.sumOf { it.notes.size } ?: 0
        val newNotes = song.channels.sumOf { it.notes.size }
        System.err.println("[SPC-PIANO] Song modified: $prevNotes -> $newNotes notes")
        song.isModified = true
        editingSong = song.copy() // force recompose
    }

    /**
     * Render a song through the native SPC emulator.
     * - If unmodified: renders the original track directly (perfect sound)
     * - If modified: encodes edited notes to N-SPC binary, patches into SPC RAM, then renders
     */
    suspend fun renderEditedSong(romParser: RomParser, song: NspcSequence.Song): ShortArray? {
        val track = selectedTrack ?: run {
            System.err.println("[SPC-PIANO] renderEditedSong: no track selected")
            return null
        }
        if (!NativeSpcEmulator.isAvailable()) {
            System.err.println("[SPC-PIANO] Native emulator not available, will fall back to software renderer")
            return null
        }

        System.err.println("[SPC-PIANO] renderEditedSong: track='${track.name}', songSet=0x${track.songSet.toString(16)}, " +
            "playIndex=${track.playIndex}, modified=${song.isModified}, " +
            "notes=${song.channels.sumOf { it.notes.size }}, ticks=${song.totalTicks}")

        return withContext(Dispatchers.Default) {
            try {
                val baseRam = SpcData.buildInitialSpcRam(romParser)
                val songBlocks = SpcData.findSongSetTransferData(romParser, track.songSet)
                System.err.println("[SPC-PIANO] SPC RAM built: ${songBlocks.size} song transfer blocks")

                if (!song.isModified) {
                    System.err.println("[SPC-PIANO] Rendering ORIGINAL track (unmodified) via native SPC emulator")
                    NativeSpcEmulator().use { emu ->
                        emu.loadFromRam(baseRam, songBlocks, track.playIndex)
                        val mono = emu.renderMono(60)
                        val peak = if (mono.isNotEmpty()) mono.maxOf { kotlin.math.abs(it.toInt()) } else 0
                        System.err.println("[SPC-PIANO] Native render OK: ${mono.size} samples (${mono.size / 32000}s), peak=$peak")
                        mono
                    }
                } else {
                    System.err.println("[SPC-PIANO] Rendering MODIFIED track via native SPC emulator")
                    val spcRam = baseRam.copyOf()
                    SpcData.applyTransferBlocks(spcRam, songBlocks)

                    // Log original conductor location
                    val origConductor = (spcRam[0x581E + track.playIndex * 2].toInt() and 0xFF) or
                        ((spcRam[0x581E + track.playIndex * 2 + 1].toInt() and 0xFF) shl 8)
                    System.err.println("[SPC-PIANO] Original conductor for playIndex ${track.playIndex} at 0x${origConductor.toString(16)}")

                    val writes = NspcSequence.encode(song, track.playIndex, spcRam)
                    val totalBytes = writes.values.sumOf { it.size }
                    System.err.println("[SPC-PIANO] Encoded ${writes.size} writes, $totalBytes bytes total:")
                    for ((addr, data) in writes) {
                        val preview = if (data.size <= 24) " = [${data.joinToString(",") { "%02x".format(it.toInt() and 0xFF) }}]" else ""
                        System.err.println("[SPC-PIANO]   0x${addr.toString(16).padStart(4, '0')}: ${data.size} bytes$preview")
                    }

                    val patchBlocks = writes.map { (addr, data) ->
                        SpcData.TransferBlock(addr, data)
                    }

                    System.err.println("[SPC-PIANO] Loading native SPC: ${songBlocks.size} song blocks + ${patchBlocks.size} patch blocks")
                    NativeSpcEmulator().use { emu ->
                        emu.loadFromRam(baseRam, songBlocks + patchBlocks, track.playIndex)
                        val mono = emu.renderMono(60)
                        val peak = if (mono.isNotEmpty()) mono.maxOf { kotlin.math.abs(it.toInt()) } else 0
                        System.err.println("[SPC-PIANO] Native render OK: ${mono.size} samples (${mono.size / 32000}s), peak=$peak")
                        mono
                    }
                }
            } catch (e: Exception) {
                System.err.println("[SPC-PIANO] Native render FAILED: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    fun buildSpcRamForTrack(romParser: RomParser, songSet: Int): ByteArray {
        val baseRam = SpcData.buildInitialSpcRam(romParser)
        val blocks = SpcData.findSongSetTransferData(romParser, songSet)
        SpcData.applyTransferBlocks(baseRam, blocks)
        return baseRam
    }

    private var spcRam: ByteArray? = null
    private var romParserRef: RomParser? = null
    private var lastLoadedSongSet = -1
    private var loadVersion = 0

    data class DecodedSample(
        val dirEntry: SpcData.SampleDirEntry,
        val pcmData: ShortArray,
        val loopStart: Int
    )

    fun selectTrack(track: SpcData.TrackInfo) {
        val wasPlaying = player.isActive()
        player.stop()
        playbackPosition = 0f

        val clearSamples = track.songSet != lastLoadedSongSet
        selectedTrackId = track.id
        selectedTrack = track
        currentSongSet = track.songSet
        selectedSample = null
        selectedSampleIndex = -1
        statusMessage = "Track: ${track.name}"
        if (wasPlaying) autoPlayEnabled = true
        if (clearSamples) {
            trackSamples = emptyList()
            trackUniqueSamples = emptyList()
            currentWaveform = null
            currentLoopStart = -1
        }
    }

    suspend fun selectSample(sample: DecodedSample) {
        val extended = withContext(Dispatchers.Default) {
            extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        }
        selectedSampleIndex = sample.dirEntry.index
        selectedSample = sample
        selectedTrack = null
        selectedTrackId = -1
        currentWaveform = extended
        currentLoopStart = sample.loopStart
        statusMessage = "Sample #${sample.dirEntry.index}: ${sample.pcmData.size} pcm" +
            if (sample.loopStart >= 0) " (extended to ${extended.size})" else ""
    }

    suspend fun loadSamples(romParser: RomParser) {
        isLoadingSamples = true
        statusMessage = "Loading SPC data..."
        try {
            val result = withContext(Dispatchers.Default) {
                val ram = SpcData.buildInitialSpcRam(romParser)
                val dir = SpcData.findSampleDirectory(ram)
                System.err.println("[SPC] Sample directory: ${dir.size} entries")
                val decoded = dir.mapNotNull { entry ->
                    try {
                        val (pcm, loop) = SpcData.decodeBrrWithLoop(ram, entry)
                        if (pcm.isNotEmpty()) DecodedSample(entry, pcm, loop) else null
                    } catch (e: Exception) {
                        System.err.println("[SPC]   Failed to decode #${entry.index}: ${e.message}")
                        null
                    }
                }
                Pair(ram, decoded)
            }
            spcRam = result.first
            samples = result.second
            statusMessage = "Loaded ${result.second.size} BRR samples"
        } catch (e: Exception) {
            statusMessage = "Error: ${e.message}"
            System.err.println("[SPC] Load failed: ${e.message}")
        } finally {
            isLoadingSamples = false
        }
    }

    suspend fun loadTrackSamples(romParser: RomParser) {
        val track = selectedTrack ?: return
        val version = ++loadVersion
        romParserRef = romParser

        if (track.songSet == lastLoadedSongSet && trackSamples.isNotEmpty()) {
            buildCompositeWaveform(track, version)
            return
        }
        isLoadingTrack = true
        statusMessage = "Loading instruments for ${track.name}..."

        try {
            val result = withContext(Dispatchers.Default) {
                val baseRam = spcRam ?: SpcData.buildInitialSpcRam(romParser)
                val ram = baseRam.copyOf()

                val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
                System.err.println("[SPC] Track '${track.name}' songSet=0x${track.songSet.toString(16)}: ${blocks.size} transfer blocks")

                val baseDir = SpcData.findSampleDirectory(baseRam)
                val baseDirMap = baseDir.associate { it.index to it }

                if (blocks.isNotEmpty()) {
                    SpcData.applyTransferBlocks(ram, blocks)
                }
                val dir = SpcData.findSampleDirectory(ram)

                val modifiedRanges = blocks.map { it.destAddr until (it.destAddr + it.data.size) }

                val allDecoded = mutableListOf<DecodedSample>()
                val uniqueDecoded = mutableListOf<DecodedSample>()

                for (entry in dir) {
                    try {
                        val (pcm, loop) = SpcData.decodeBrrWithLoop(ram, entry)
                        if (pcm.size <= 16 || pcm.all { it.toInt() == 0 }) continue
                        val sample = DecodedSample(entry, pcm, loop)
                        allDecoded.add(sample)

                        val brrModified = modifiedRanges.any { range ->
                            entry.startAddr in range || (entry.startAddr + 9) in range
                        }
                        val baseEntry = baseDirMap[entry.index]
                        val dirChanged = baseEntry == null ||
                            baseEntry.startAddr != entry.startAddr ||
                            baseEntry.loopAddr != entry.loopAddr

                        if (brrModified || dirChanged) uniqueDecoded.add(sample)
                    } catch (_: Exception) {}
                }

                System.err.println("[SPC] Directory: ${dir.size} entries, ${allDecoded.size} decoded, ${uniqueDecoded.size} unique to song set")
                arrayOf(baseRam, allDecoded, uniqueDecoded, blocks.size)
            }

            if (version != loadVersion) return
            @Suppress("UNCHECKED_CAST")
            val baseRam = result[0] as ByteArray
            @Suppress("UNCHECKED_CAST")
            val allSamples = result[1] as List<DecodedSample>
            @Suppress("UNCHECKED_CAST")
            val uniqueSamples = result[2] as List<DecodedSample>
            val blockCount = result[3] as Int
            if (spcRam == null) spcRam = baseRam
            lastLoadedSongSet = track.songSet
            trackSamples = allSamples
            trackUniqueSamples = uniqueSamples
            statusMessage = "${uniqueSamples.size} unique / ${allSamples.size} total instruments ($blockCount blocks) for ${track.name}"
            buildCompositeWaveform(track, version)
        } catch (e: Exception) {
            if (version == loadVersion) {
                statusMessage = "Error loading track: ${e.message}"
            }
            System.err.println("[SPC] Track load error: ${e.message}")
        } finally {
            if (version == loadVersion) {
                isLoadingTrack = false
            }
        }
    }

    private suspend fun buildCompositeWaveform(track: SpcData.TrackInfo, version: Int) {
        val rate = sampleRate

        val trimmed = withContext(Dispatchers.Default) {
            val ram = spcRam
            val parser = romParserRef ?: return@withContext ShortArray(0)

            if (ram != null) {
                val nativeResult = tryNativeSpcRender(ram, parser, track, rate)
                if (nativeResult != null && nativeResult.size > rate / 2) {
                    val peak = nativeResult.maxOf { abs(it.toInt()) }
                    if (peak > 200) {
                        System.err.println("[SPC-NATIVE] Rendered ${track.name}: ${nativeResult.size} samples, peak=$peak")
                        return@withContext nativeResult
                    }
                }
            }

            val isVoiceClip = track.songSet in VOICE_CLIP_SONG_SETS
            if (isVoiceClip) {
                val result = renderVoiceClip(ram, parser, track, rate)
                if (result.isNotEmpty()) return@withContext result
            }

            if (ram != null) {
                try {
                    val fullRam = ram.copyOf()
                    val blocks = SpcData.findSongSetTransferData(parser, track.songSet)
                    if (blocks.isNotEmpty()) SpcData.applyTransferBlocks(fullRam, blocks)

                    val rendered = NspcRenderer.renderTrack(fullRam, track.playIndex, rate, 90.0)
                    val renderPeak = if (rendered.isNotEmpty()) rendered.maxOf { abs(it.toInt()) } else 0
                    if (rendered.size > rate / 2 && renderPeak > 200) {
                        System.err.println("[NSPC] Rendered ${track.name}: ${rendered.size} samples (${rendered.size * 1000L / rate}ms), peak=$renderPeak")
                        return@withContext rendered
                    }
                    System.err.println("[NSPC] Render insufficient (${rendered.size} samples, peak=$renderPeak), falling back")
                } catch (e: Exception) {
                    System.err.println("[NSPC] Render failed for ${track.name}: ${e.message}, falling back")
                }
            }

            val allSrcs = trackSamples.ifEmpty { return@withContext ShortArray(0) }
            val srcs = trackUniqueSamples.ifEmpty { allSrcs }
            val uniqueSamples = srcs
                .distinctBy { it.dirEntry.startAddr }
                .filter { it.pcmData.size >= 100 }
                .ifEmpty {
                    allSrcs.distinctBy { it.dirEntry.startAddr }
                        .filter { it.pcmData.size >= 100 }
                }

            if (uniqueSamples.isEmpty()) return@withContext ShortArray(0)

            val gapSamples = rate / 10
            val segments = mutableListOf<ShortArray>()

            for (s in uniqueSamples) {
                val loopRegionSize = if (s.loopStart in 0 until s.pcmData.size)
                    s.pcmData.size - s.loopStart else 0
                val isLoopable = loopRegionSize > 200

                val segment: ShortArray = if (isLoopable) {
                    extendWithLoop(s.pcmData, s.loopStart, rate, 1.2)
                } else {
                    s.pcmData.copyOf()
                }

                val fadeLen = minOf(segment.size, rate / 5)
                for (i in 0 until fadeLen) {
                    val idx = segment.size - fadeLen + i
                    segment[idx] = (segment[idx] * (fadeLen - i) / fadeLen).toShort()
                }
                segments.add(segment)
                segments.add(ShortArray(gapSamples))
            }

            val totalLen = segments.sumOf { it.size }
            val mixed = ShortArray(totalLen)
            var pos = 0
            for (seg in segments) {
                seg.copyInto(mixed, pos)
                pos += seg.size
            }

            val peak = mixed.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
            if (peak < 200) return@withContext mixed
            val gain = 26000.0 / peak
            ShortArray(mixed.size) { (mixed[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
        }

        if (version != loadVersion) return
        currentWaveform = trimmed
        currentLoopStart = -1
        statusMessage = "${track.name}: ${trimmed.size * 1000L / rate}ms"

        if ((autoPlayEnabled || playAllEnabled) && trimmed.isNotEmpty()) {
            autoPlayEnabled = false
            playTrackPreview()
        }
    }

    private fun tryNativeSpcRender(
        baseRam: ByteArray,
        romParser: RomParser,
        track: SpcData.TrackInfo,
        sampleRate: Int
    ): ShortArray? {
        if (!NativeSpcEmulator.isAvailable()) return null
        return try {
            val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
            NativeSpcEmulator().use { emu ->
                emu.loadFromRam(baseRam, blocks, track.playIndex)
                val renderSeconds = 120
                val fadeSeconds = 5
                val nativeSr = NativeSpcEmulator.SAMPLE_RATE
                var mono = emu.renderMono(renderSeconds)

                val fadeSamples = nativeSr * fadeSeconds
                if (mono.size > fadeSamples) {
                    val fadeStart = mono.size - fadeSamples
                    for (i in 0 until fadeSamples) {
                        val gain = 1.0f - (i.toFloat() / fadeSamples)
                        mono[fadeStart + i] = (mono[fadeStart + i] * gain).toInt().toShort()
                    }
                }

                val silenceThreshold = 64
                val silenceWindow = nativeSr / 2
                var trimEnd = mono.size
                while (trimEnd > silenceWindow) {
                    val windowStart = trimEnd - silenceWindow
                    val windowPeak = (windowStart until trimEnd).maxOf { abs(mono[it].toInt()) }
                    if (windowPeak > silenceThreshold) break
                    trimEnd = windowStart
                }
                if (trimEnd < mono.size) {
                    mono = mono.copyOf(trimEnd)
                }

                if (sampleRate != nativeSr && mono.isNotEmpty()) {
                    resampleLinear(mono, nativeSr, sampleRate)
                } else {
                    mono
                }
            }
        } catch (e: Exception) {
            System.err.println("[SPC-JNA] Native render failed for ${track.name}: ${e.message}")
            null
        }
    }

    private fun renderVoiceClip(
        baseRam: ByteArray?,
        romParser: RomParser,
        track: SpcData.TrackInfo,
        @Suppress("UNUSED_PARAMETER") rate: Int
    ): ShortArray {
        val ram = (baseRam ?: SpcData.buildInitialSpcRam(romParser)).copyOf()
        val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
        System.err.println("[VOICE] ${track.name}: songSet=0x${track.songSet.toString(16)}, ${blocks.size} transfer blocks")
        if (blocks.isEmpty()) return ShortArray(0)

        val baseDirBefore = SpcData.findSampleDirectory(ram)
        val beforeAddrs = baseDirBefore.map { it.startAddr }.toSet()
        SpcData.applyTransferBlocks(ram, blocks)
        val dirAfter = SpcData.findSampleDirectory(ram)

        val candidates = mutableListOf<Pair<SpcData.SampleDirEntry, ShortArray>>()
        for (entry in dirAfter) {
            val isNew = entry.startAddr !in beforeAddrs
            val wasModified = blocks.any { blk ->
                val range = blk.destAddr until (blk.destAddr + blk.data.size)
                entry.startAddr in range
            }
            if (!isNew && !wasModified) continue
            try {
                val (pcm, _) = SpcData.decodeBrrWithLoop(ram, entry, maxBlocks = 8192)
                if (pcm.size > 500) {
                    candidates.add(entry to pcm)
                    System.err.println("[VOICE]   candidate #${entry.index}: ${pcm.size} pcm @ 0x${entry.startAddr.toString(16)}")
                }
            } catch (_: Exception) {}
        }

        if (candidates.isEmpty()) {
            System.err.println("[VOICE] No voice sample candidates found, trying all unique samples")
            return ShortArray(0)
        }

        val (_, voicePcm) = candidates.maxBy { it.second.size }
        System.err.println("[VOICE] Selected sample with ${voicePcm.size} pcm samples")

        val peak = voicePcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        val gain = 26000.0 / peak
        return ShortArray(voicePcm.size) { (voicePcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }

    companion object {
        private val VOICE_CLIP_SONG_SETS = setOf(0x3F, 0x42)

        fun resampleLinear(pcm: ShortArray, fromRate: Int, toRate: Int): ShortArray {
            if (fromRate == toRate || pcm.isEmpty()) return pcm
            val ratio = fromRate.toDouble() / toRate
            val outLen = (pcm.size / ratio).toInt()
            val out = ShortArray(outLen)
            for (i in 0 until outLen) {
                val srcPos = i * ratio
                val idx = srcPos.toInt()
                val frac = srcPos - idx
                val s = if (idx + 1 < pcm.size) {
                    (pcm[idx] * (1.0 - frac) + pcm[idx + 1] * frac).toInt()
                } else if (idx < pcm.size) {
                    pcm[idx].toInt()
                } else break
                out[i] = s.coerceIn(-32768, 32767).toShort()
            }
            return out
        }

        fun extendWithLoop(
            pcm: ShortArray,
            loopStart: Int,
            sampleRate: Int,
            targetSeconds: Double
        ): ShortArray {
            val targetLen = (sampleRate * targetSeconds).toInt()
            if (pcm.size >= targetLen) return pcm

            if (loopStart in 0 until pcm.size) {
                val attack = pcm.copyOfRange(0, loopStart)
                val loopRegion = pcm.copyOfRange(loopStart, pcm.size)
                if (loopRegion.isEmpty()) return pcm

                val out = ShortArray(targetLen)
                attack.copyInto(out)
                var pos = attack.size
                while (pos < targetLen) {
                    val remaining = targetLen - pos
                    val copyLen = minOf(loopRegion.size, remaining)
                    loopRegion.copyInto(out, pos, 0, copyLen)
                    pos += copyLen
                }
                val fadeLen = minOf(out.size, (sampleRate * 0.3).toInt())
                for (i in 0 until fadeLen) {
                    val idx = out.size - fadeLen + i
                    out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
                }
                return out
            }

            val fadeLen = minOf(pcm.size, (sampleRate * 0.2).toInt())
            val out = pcm.copyOf()
            for (i in 0 until fadeLen) {
                val idx = out.size - fadeLen + i
                out[idx] = (out[idx] * (fadeLen - i) / fadeLen).toShort()
            }
            return out
        }

        fun resamplePitch(
            pcm: ShortArray,
            loopStart: Int,
            pitchFactor: Double,
            sampleRate: Int,
            targetSeconds: Double
        ): ShortArray {
            if (pcm.isEmpty()) return pcm
            val extended = extendWithLoop(pcm, loopStart, sampleRate, targetSeconds * pitchFactor + 0.1)
            val outLen = (extended.size / pitchFactor).toInt().coerceAtMost((sampleRate * targetSeconds).toInt())
            val out = ShortArray(outLen)
            for (i in 0 until outLen) {
                val srcPos = i * pitchFactor
                val idx = srcPos.toInt()
                if (idx + 1 >= extended.size) break
                val frac = (srcPos - idx).toFloat()
                val v = (extended[idx] * (1f - frac) + extended[idx + 1] * frac).toInt()
                out[i] = v.coerceIn(-32768, 32767).toShort()
            }
            return out
        }
    }

    fun playSample(sample: DecodedSample, loop: Boolean = false) {
        if (sample.pcmData.isEmpty()) { statusMessage = "Empty sample"; return }
        val extended = extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        val pcm = normalizePcm(extended)
        statusMessage = "Playing sample #${sample.dirEntry.index}..."
        player.onComplete = { isPlaying = false; playbackPosition = 0f }
        player.play(pcm, sampleRate, loop)
        isPlaying = true
    }

    fun playWaveform(pcm: ShortArray) {
        if (pcm.isEmpty()) { statusMessage = "Empty waveform"; return }
        player.onComplete = { isPlaying = false; playbackPosition = 0f }
        player.play(pcm, sampleRate)
        isPlaying = true
        statusMessage = "Playing..."
    }

    fun playTrackPreview(startFraction: Float = 0f) {
        val waveform = currentWaveform
        if (waveform == null || waveform.isEmpty()) {
            statusMessage = "No waveform loaded"
            return
        }
        val startFrame = (startFraction * waveform.size).toLong().coerceIn(0, waveform.size.toLong() - 1)
        val loopStr = if (loopEnabled) " (loop)" else ""
        statusMessage = "Playing ${selectedTrack?.name ?: "preview"}$loopStr..."
        player.onComplete = {
            isPlaying = false
            playbackPosition = 0f
            if (playAllEnabled) advanceToNextTrack()
        }
        player.play(waveform, sampleRate, loop = loopEnabled, startFrame = startFrame)
        isPlaying = true
    }

    fun stopPlayback() {
        player.stop()
        isPlaying = false
        playbackPosition = 0f
        statusMessage = "Stopped"
    }

    fun seekTo(fraction: Float) {
        if (player.isActive()) {
            player.seekFraction(fraction)
        } else {
            playbackPosition = fraction
        }
    }

    fun updatePlaybackPosition() {
        if (player.isActive()) {
            playbackPosition = player.positionFraction()
            isPlaying = true
        } else if (isPlaying) {
            isPlaying = false
        }
    }

    var pendingNextTrack by mutableStateOf(false)
        private set

    fun checkPendingAdvance() {
        if (!pendingNextTrack) return
        pendingNextTrack = false
        val tracks = SpcData.KNOWN_TRACKS
        val currentIdx = tracks.indexOfFirst { it.id == selectedTrackId }
        if (currentIdx >= 0 && currentIdx + 1 < tracks.size) {
            val next = tracks[currentIdx + 1]
            selectTrack(next)
        } else {
            playAllEnabled = false
            statusMessage = "Finished playing all tracks"
        }
    }

    private fun advanceToNextTrack() {
        pendingNextTrack = true
    }

    fun exportCurrentWav() {
        val waveform = currentWaveform ?: return
        val name = selectedTrack?.name?.replace(Regex("[^a-zA-Z0-9_\\- ]"), "")?.trim()
            ?: selectedSample?.let { "sample_${it.dirEntry.index}" }
            ?: "sound"

        val dialog = FileDialog(null as Frame?, "Export WAV", FileDialog.SAVE)
        dialog.file = "${name}.wav"
        dialog.setFilenameFilter { _, fn -> fn.endsWith(".wav", ignoreCase = true) }
        dialog.isVisible = true

        val dir = dialog.directory ?: return
        val fileName = dialog.file ?: return
        val file = File(dir, if (fileName.endsWith(".wav", ignoreCase = true)) fileName else "$fileName.wav")

        try {
            exportWav(waveform, sampleRate, file)
            statusMessage = "Exported: ${file.absolutePath}"
            System.err.println("[SPC] Exported WAV: ${file.absolutePath} (${waveform.size} samples, ${sampleRate}Hz)")
        } catch (e: Exception) {
            statusMessage = "Export failed: ${e.message}"
            System.err.println("[SPC] WAV export error: ${e.message}")
        }
    }

    data class ImportedWav(val pcm: ShortArray, val sourceRate: Int)

    fun importWav(): ImportedWav? {
        val dialog = FileDialog(null as Frame?, "Import WAV", FileDialog.LOAD)
        dialog.setFilenameFilter { _, fn -> fn.endsWith(".wav", ignoreCase = true) }
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val fileName = dialog.file ?: return null
        val file = File(dir, fileName)

        return try {
            val audioStream = AudioSystem.getAudioInputStream(file)
            val srcFormat = audioStream.format
            // Convert to mono 16-bit signed PCM
            val targetFormat = AudioFormat(
                srcFormat.sampleRate, 16, 1, true, false
            )
            val converted = if (srcFormat.matches(targetFormat)) {
                audioStream
            } else {
                AudioSystem.getAudioInputStream(targetFormat, audioStream)
            }
            val bytes = converted.readBytes()
            converted.close()
            audioStream.close()

            val pcm = ShortArray(bytes.size / 2)
            for (i in pcm.indices) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                pcm[i] = ((hi shl 8) or lo).toShort()
            }
            val sourceRate = srcFormat.sampleRate.toInt()
            statusMessage = "Imported: ${file.name} (${pcm.size} samples, ${sourceRate}Hz)"
            ImportedWav(pcm, sourceRate)
        } catch (e: Exception) {
            statusMessage = "Import failed: ${e.message}"
            System.err.println("[SPC] WAV import error: ${e.message}")
            null
        }
    }

    /**
     * Replace a sample in the current song set with a WAV file.
     * Simple in-place overwrite: find exact ROM bytes, write new BRR there.
     * If new BRR is longer than original, auto-trim to fit.
     */
    fun replaceSampleInRom(
        romParser: RomParser,
        editorState: EditorState,
        sampleDirIndex: Int
    ) {
        // Use current song set if a track was selected, otherwise base (0x00)
        val songSet = if (currentSongSet >= 0) currentSongSet else 0x00

        val imported = importWav() ?: return
        val resampled = SpcData.resamplePcm(imported.pcm, imported.sourceRate, 32000)
        val newBrr = SpcData.encodeBrr(resampled)

        val result = SpcData.buildSampleReplacementWrites(
            romParser, songSet, sampleDirIndex, newBrr
        )
        if (result == null) {
            statusMessage = "Failed to replace sample (see logs)"
            return
        }

        val (writes, wasTrimmed) = result

        // Create or update patch
        val trackName = selectedTrack?.name ?: "songSet 0x${songSet.toString(16)}"
        val patchName = "SPC: sample #$sampleDirIndex"
        val existing = editorState.project.patches.find { it.name == patchName }
        val patchId = existing?.id
            ?: editorState.addPatch(patchName, "In-place BRR replacement in $trackName").id

        val patchWrites = writes.map { (offset, bytes) ->
            SmPatchWrite(offset.toLong(), bytes.map { it.toInt() and 0xFF })
        }
        editorState.setPatchWrites(patchId, patchWrites)
        editorState.updatePatch(patchId, enabled = true)

        // Show the imported WAV as current waveform
        loadVersion++
        currentWaveform = imported.pcm
        currentLoopStart = -1

        val totalBytes = writes.sumOf { it.second.size }
        val trimNote = if (wasTrimmed) " [TRIMMED to fit ${totalBytes}B slot]" else ""
        statusMessage = "Replaced sample #$sampleDirIndex (${totalBytes}B at ROM 0x${writes.first().first.toString(16)})$trimNote"
    }

    /**
     * Reset a sample replacement by removing its patch.
     */
    fun resetSampleReplacement(editorState: EditorState, sampleDirIndex: Int) {
        val patchName = "SPC: sample #$sampleDirIndex"
        val patch = editorState.project.patches.find { it.name == patchName }
        if (patch != null) {
            editorState.removePatch(patch.id)
            statusMessage = "Reset sample #$sampleDirIndex to original"
        } else {
            statusMessage = "No replacement to reset for sample #$sampleDirIndex"
        }
    }

    /** Check if a sample has been replaced (has an active patch). */
    fun isSampleReplaced(editorState: EditorState, sampleDirIndex: Int): Boolean {
        val patchName = "SPC: sample #$sampleDirIndex"
        return editorState.project.patches.any { it.name == patchName && it.enabled }
    }

    fun importWavAndExportBrr() {
        val imported = importWav() ?: return
        // Resample to 32kHz (SNES native rate) if needed
        val resampled = if (imported.sourceRate != 32000) {
            SpcData.resamplePcm(imported.pcm, imported.sourceRate, 32000)
        } else {
            imported.pcm
        }

        val brr = SpcData.encodeBrr(resampled)

        val dialog = FileDialog(null as Frame?, "Save BRR Sample", FileDialog.SAVE)
        dialog.file = "sample.brr"
        dialog.isVisible = true

        val dir = dialog.directory ?: return
        val fileName = dialog.file ?: return
        val file = File(dir, if (fileName.endsWith(".brr", ignoreCase = true)) fileName else "$fileName.brr")

        try {
            file.writeBytes(brr)
            statusMessage = "Exported BRR: ${file.name} (${brr.size} bytes, ${brr.size / 9} blocks from ${imported.pcm.size} samples)"
            System.err.println("[SPC] Exported BRR: ${file.absolutePath} (${brr.size} bytes)")

            // Load the imported WAV as current waveform for preview
            sampleRate = imported.sourceRate
            currentWaveform = imported.pcm
            currentLoopStart = -1
        } catch (e: Exception) {
            statusMessage = "BRR export failed: ${e.message}"
            System.err.println("[SPC] BRR export error: ${e.message}")
        }
    }

    fun importWavAsPreview() {
        val imported = importWav() ?: return
        loadVersion++ // cancel any in-flight track render
        sampleRate = imported.sourceRate
        currentWaveform = imported.pcm
        currentLoopStart = -1
    }

    private fun normalizePcm(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val peak = pcm.maxOf { abs(it.toInt()) }
        if (peak < 100 || peak > 20000) return pcm
        val gain = 26000.0 / peak
        return ShortArray(pcm.size) { (pcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
