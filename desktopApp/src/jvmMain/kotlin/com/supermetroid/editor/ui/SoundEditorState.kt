package com.supermetroid.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.supermetroid.editor.data.MusicNativePayloadEdit
import com.supermetroid.editor.data.MusicInstrumentBankEdit
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.data.MusicTrackEdit
import com.supermetroid.editor.rom.NativeSpcEmulator
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

private const val EDIT_TRACK_PREVIEW_GAIN = 2.0
private val VOICE_CLIP_SONG_SETS = setOf(0x3F, 0x42)

private val soundEditorLog = KotlinLogging.logger {}

internal object PianoRollPlaybackTiming {
    private const val PREVIEW_PAD_SECONDS = 2
    private const val MAX_PREVIEW_SECONDS = 240

    fun tickMillis(tempo: Int): Double =
        512.0 / tempo.coerceAtLeast(1)

    fun tickToMillis(tick: Int, tempo: Int): Long =
        (tick.coerceAtLeast(0) * tickMillis(tempo)).roundToLong()

    fun playbackTickAtMillis(positionMillis: Long, tempo: Int, maxTick: Int): Int {
        val tickMs = tickMillis(tempo)
        if (tickMs <= 0.0) return 0
        return (positionMillis / tickMs).toInt()
            .coerceIn(0, maxTick.coerceAtLeast(0))
    }

    fun previewSeconds(song: NspcSequence.Song): Int {
        val durationSeconds = song.totalTicks * tickMillis(song.tempo) / 1000.0
        return ceil(durationSeconds + PREVIEW_PAD_SECONDS).toInt()
            .coerceIn(5, MAX_PREVIEW_SECONDS)
    }
}

class SoundEditorState(private val instrumentPlayer: SoundPlayer = SoundPlayer(),
    private val heldInstrumentPlayer: HeldSoundPlayer = HeldSoundPlayer()) {
    private val player = SoundPlayer()
    private var instrumentPreviewVersion = 0
    private data class InstrumentPreviewRequest(val parser: RomParser, val index: Int, val note: NspcSequence.Note,
        val sampleSource: Int?, val trackId: Int, val original: Boolean, val held: Boolean = false)
    private var instrumentPreviewRequest: InstrumentPreviewRequest? = null
    var instrumentAuditionOriginal by mutableStateOf(false)
        private set
    var liveInstrumentPreview by mutableStateOf(true)
        private set
    internal var instrumentPreviewRefreshVersion by mutableStateOf(0)
        private set
    var instrumentPreviewIndex by mutableStateOf<Int?>(null)
        private set
    var instrumentPreviewSample by mutableStateOf<Int?>(null)
        private set
    var isInstrumentPreviewLoading by mutableStateOf(false)
        private set
    var instrumentPreviewMessage by mutableStateOf("")
        private set
    val nativeInstrumentPreviewAvailable: Boolean get() = NativeSpcEmulator.isAvailable()

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
    internal val sampleWorkbench = SampleWorkbenchState()
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
    var pianoRollInstruments by mutableStateOf<List<NspcRenderer.InstrumentEntry>>(emptyList())
        private set
    var pianoRollSourceInstruments by mutableStateOf<List<NspcRenderer.InstrumentEntry>>(emptyList())
        private set
    var pianoRollSamples by mutableStateOf<List<MusicSample>>(emptyList())
        private set
    var pianoRollSampleVersion by mutableStateOf(0)
        private set
    var isLoadingPianoRollSamples by mutableStateOf(false)
        private set
    var pianoRollSamplesProblem by mutableStateOf<String?>(null)
        private set
    private var sampleCatalogRam: ByteArray? = null
    private var sampleCatalogSongSet = -1
    private var sampleCatalogBankBlocks: List<SpcData.TransferBlock> = emptyList()
    private var sampleCatalogCustom: List<SpcData.TransferBlock> = emptyList()
    private var sampleCatalogImports: List<Pair<String, List<SpcData.TransferBlock>>> = emptyList()
    private var loadedSampleVersion = -1
    private var originalPianoRollInstruments: List<NspcRenderer.InstrumentEntry> = emptyList()
    private var originalPianoRollSong: NspcSequence.Song? = null
    private val pianoRollHistory = PianoRollEditLogic.EditHistory()
    var pianoRollUndoDepth by mutableStateOf(0)
        private set
    var pianoRollRedoDepth by mutableStateOf(0)
        private set
    var pianoRollPlaybackTick by mutableStateOf(-1)
    private var pianoRollPlayStartMs = 0L
    private var pianoRollTickMs = 0.0
    var pianoRollExportBudgetBytes by mutableStateOf(0)
        private set
    var pianoRollRelocationBudgetBytes by mutableStateOf(0)
        private set
    var pianoRollOriginalSequenceBytes by mutableStateOf(0)
        private set
    var pianoRollOriginalFlattenedBytes by mutableStateOf(0)
        private set
    var pendingTrackImport by mutableStateOf<PendingTrackImport?>(null)
        private set
    private var pianoRollNativeSource: MusicNativePayloadEdit? = null
    var instrumentBankProblem by mutableStateOf<String?>(null)
        private set
    var instrumentBankTracks by mutableStateOf<List<String>>(emptyList())
        private set

    /** Open the piano roll editor for the current track. */
    fun openPianoRoll(romParser: RomParser, editorState: EditorState? = null) {
        val track = selectedTrack ?: run {
            soundEditorLog.warn { "[SPC-PIANO] openPianoRoll: no track selected" }
            return
        }
        stopPlayback()
        instrumentAuditionOriginal = false
        romParserRef = romParser
        editorStateRef = editorState
        refreshInstrumentBank(romParser, editorState)
        playAllEnabled = false
        soundEditorLog.info {
            "[SPC-PIANO] Opening piano roll for '${track.name}' " +
                "songSet=0x${track.songSet.toString(16)} playIndex=${track.playIndex}"
        }
        val ram = buildSpcRamForTrack(romParser, track.songSet)
        val originalSong = NspcSequence.parse(ram, track.playIndex)
        val originalInstruments = NspcRenderer.readInstrumentTable(ram)
        val trackBudget = MusicSequenceBudget.trackBudgetForTrack(romParser, track.songSet, track.playIndex)
        pianoRollExportBudgetBytes = trackBudget.trackBytes
        pianoRollRelocationBudgetBytes = trackBudget.relocationBytes
        pianoRollOriginalSequenceBytes = trackBudget.trackBytes
        pianoRollOriginalFlattenedBytes = NspcSequence.encodedSequenceSize(originalSong)
        originalPianoRollInstruments = originalInstruments
        originalPianoRollSong = PianoRollPreviewLogic.deepCopySong(originalSong)

        val savedEdit = editorState?.getMusicEdit(track.songSet, track.playIndex)
        val song = savedEdit?.let { MusicEditConversion.toSong(it) } ?: originalSong
        setPianoRollSoundBank(savedEdit?.nativePayload, originalInstruments)
        song.title = track.name
        song.isModified = hasPianoRollEdits(song)
        editingSong = song
        clearPianoRollHistory()
        pendingTrackImport = null
        pianoRollChannel = 0
        pianoRollPlaybackTick = -1
        isPianoRollOpen = true

        val totalNotes = song.channels.sumOf { it.notes.size }
        val totalCmds = song.channels.sumOf { it.commands.size }
        val activeChannels = song.channels.count { it.notes.isNotEmpty() }
        soundEditorLog.info {
            "[SPC-PIANO] Parsed: $totalNotes notes, $totalCmds commands, " +
                "$activeChannels active channels, ${song.totalTicks} ticks, tempo=${song.tempo}"
        }
        for (ch in 0 until 8) {
            val notes = song.channels[ch].notes
            val cmds = song.channels[ch].commands
            if (notes.isNotEmpty() || cmds.isNotEmpty()) {
                val instrSet = notes.map { it.instrument }.toSet()
                val range = if (notes.isNotEmpty()) ", range ${NspcSequence.noteToName(notes.minOf { it.noteValue })}-${NspcSequence.noteToName(notes.maxOf { it.noteValue })}" else ""
                soundEditorLog.info { "[SPC-PIANO]   Ch $ch: ${notes.size} notes, ${cmds.size} cmds, instruments=$instrSet$range" }
            }
        }
        statusMessage = if (savedEdit != null) {
            "Editing saved project music: ${track.name} ($totalNotes notes, ${song.totalTicks} ticks)"
        } else {
            "Editing: ${track.name} ($totalNotes notes, ${song.totalTicks} ticks)"
        }
    }

    /** Close piano roll, return to waveform view. */
    fun closePianoRoll() {
        soundEditorLog.info { "[SPC-PIANO] Closing piano roll" }
        stopPlayback()
        instrumentAuditionOriginal = false
        playAllEnabled = false
        isPianoRollOpen = false
        pianoRollPlaybackTick = -1
        pianoRollExportBudgetBytes = 0
        pianoRollRelocationBudgetBytes = 0
        pianoRollOriginalSequenceBytes = 0
        pianoRollOriginalFlattenedBytes = 0
        clearPianoRollHistory()
        pendingTrackImport = null
        clearNativeSource()
    }

    data class PendingTrackImport(
        val label: String,
        val fileName: String,
        val song: NspcSequence.Song,
        val instruments: List<NspcRenderer.InstrumentEntry>,
        val reportLines: List<String>,
        val nativePayload: MusicNativePayloadEdit? = null,
        val applyBlockedReason: String? = null,
        val fittedImport: FittedTrackImport? = null
    ) {
        val canApply: Boolean get() = applyBlockedReason == null
    }

    data class FittedTrackImport(
        val label: String,
        val song: NspcSequence.Song,
        val instruments: List<NspcRenderer.InstrumentEntry>,
        val summary: String,
        val usesStockInstrumentFallback: Boolean = false
    )

    /** Reset piano roll to original track data. */
    fun resetPianoRoll(romParser: RomParser, editorState: EditorState? = null) {
        val track = selectedTrack ?: return
        soundEditorLog.info { "[SPC-PIANO] Resetting to original track data" }
        stopPlayback()
        pendingTrackImport = null
        removePianoRollProjectEdit(editorState)
        clearNativeSource()
        editingSong = null
        refreshInstrumentBank(romParser, editorState ?: editorStateRef)
        val ram = buildSpcRamForTrack(romParser, track.songSet)
        val song = NspcSequence.parse(ram, track.playIndex)
        pianoRollInstruments = NspcRenderer.readInstrumentTable(ram)
        val trackBudget = MusicSequenceBudget.trackBudgetForTrack(romParser, track.songSet, track.playIndex)
        pianoRollExportBudgetBytes = trackBudget.trackBytes
        pianoRollRelocationBudgetBytes = trackBudget.relocationBytes
        pianoRollOriginalSequenceBytes = trackBudget.trackBytes
        pianoRollOriginalFlattenedBytes = NspcSequence.encodedSequenceSize(song)
        originalPianoRollInstruments = pianoRollInstruments
        originalPianoRollSong = PianoRollPreviewLogic.deepCopySong(song)
        song.title = track.name
        editingSong = song
        clearPianoRollHistory()
        pianoRollPlaybackTick = 0
        removePianoRollProjectEdit(editorState)
        clearNativeSource()
        refreshOpenBankInstruments()
        statusMessage = "Reset to original"
    }

    fun exportPianoRollMidi() {
        val song = editingSong ?: run {
            statusMessage = "Open Edit Track before exporting MIDI"
            return
        }
        val track = selectedTrack
        val file = chooseMusicSaveFile(
            title = "Export MIDI",
            defaultName = "${musicFileStem(track?.name ?: song.title.ifBlank { "track" })}.mid",
            extension = "mid",
            extraExtensions = setOf("midi")
        ) ?: return

        try {
            MusicTrackInterchange.writeMidi(song, file, pianoRollInstruments)
            statusMessage = "Exported MIDI: ${file.name}"
            soundEditorLog.info { "[SPC-IMPORT] Exported MIDI ${file.absolutePath}" }
        } catch (e: Exception) {
            statusMessage = "MIDI export failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] MIDI export failed: ${e.message}" }
        }
    }

    fun exportSelectedTrackMidi(romParser: RomParser, editorState: EditorState? = null) {
        val data = resolveTrackInterchangeData(romParser, editorState) ?: return
        val file = chooseMusicSaveFile(
            title = "Export MIDI",
            defaultName = "${musicFileStem(data.track.name)}.mid",
            extension = "mid",
            extraExtensions = setOf("midi")
        ) ?: return

        try {
            MusicTrackInterchange.writeMidi(data.song, file, data.instruments)
            statusMessage = "Exported MIDI: ${file.name}"
            soundEditorLog.info { "[SPC-IMPORT] Exported selected track MIDI ${file.absolutePath}" }
        } catch (e: Exception) {
            statusMessage = "MIDI export failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] Selected track MIDI export failed: ${e.message}" }
        }
    }

    fun exportSelectedTrackNative(romParser: RomParser, editorState: EditorState? = null) {
        val data = resolveTrackInterchangeData(romParser, editorState) ?: return
        val file = chooseMusicSaveFile(
            title = "Export SPC or N-SPC",
            defaultName = "${musicFileStem(data.track.name)}.spc",
            extension = "spc",
            extraExtensions = setOf("nspc")
        ) ?: return

        try {
            if (file.extension.equals("nspc", ignoreCase = true)) {
                val sharedProject = editorStateRef?.project
                val sharedNativeBank = sharedProject?.musicEdits?.values?.any { it.songSet == data.track.songSet && it.nativePayload != null } == true
                if (sharedNativeBank) {
                    MusicTrackInterchange.writeRawTransferBlocks(MusicBankResolver.resolve(romParser, requireNotNull(sharedProject), data.track.songSet).blocks, file)
                    statusMessage = "Exported raw N-SPC bank: ${file.name}"
                } else if (data.nativePayload != null) {
                    MusicTrackInterchange.writeRawTransferBlocks(
                        ImportedMusicResolver.resolve(romParser, MusicEditConversion.toProjectEdit(
                            data.track, data.song, data.nativePayload
                        )).blocks,
                        file
                    )
                    statusMessage = "Exported raw N-SPC payload: ${file.name}"
                } else {
                    MusicTrackInterchange.writeNspcTrack(
                        data.song,
                        data.instruments,
                        data.track.songSet,
                        data.track.playIndex,
                        file
                    )
                    statusMessage = "Exported N-SPC track: ${file.name}"
                }
            } else {
                val ram = buildTrackSpcSnapshotRam(
                    romParser = romParser,
                    track = data.track,
                    song = data.song,
                    instruments = data.instruments,
                    originalSong = data.originalSong,
                    nativePayload = data.nativePayload
                )
                val spc = buildSpcSnapshotBytes(ram, data.track.playIndex, data.track.name)
                file.writeBytes(spc)
                statusMessage = "Exported SPC snapshot: ${file.name}"
            }
            soundEditorLog.info { "[SPC-IMPORT] Exported selected native track ${file.absolutePath}" }
        } catch (e: Exception) {
            statusMessage = "Native export failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] Selected native export failed: ${e.message}" }
        }
    }

    fun importPianoRollMidi(romParser: RomParser, editorState: EditorState? = null) {
        selectedTrack ?: run {
            statusMessage = "Select a track before importing MIDI"
            return
        }
        if (!isPianoRollOpen || editingSong == null) {
            openPianoRoll(romParser, editorState)
        }
        val file = chooseMusicLoadFile("Import MIDI") { name ->
            name.endsWith(".mid", ignoreCase = true) || name.endsWith(".midi", ignoreCase = true)
        } ?: return

        try {
            val track = selectedTrack ?: return
            val imported = MusicTrackInterchange.readMidiWithReport(file, pianoRollInstruments)
            stagePendingTrackImport(
                romParser = romParser,
                track = track,
                label = "MIDI",
                fileName = file.name,
                song = imported.song,
                instruments = emptyList(),
                report = imported.report,
                nativePayload = null
            )
            statusMessage = "Review MIDI import report for ${file.name}"
            soundEditorLog.info {
                "[SPC-IMPORT] Imported MIDI ${file.absolutePath}: " +
                    "${imported.song.channels.sumOf { it.notes.size }} notes"
            }
        } catch (e: Exception) {
            statusMessage = "MIDI import failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] MIDI import failed: ${e.message}" }
        }
    }

    fun importPianoRollImpulseTracker(romParser: RomParser, editorState: EditorState? = null) {
        selectedTrack ?: run {
            statusMessage = "Select a track before importing IT"
            return
        }
        if (!isPianoRollOpen || editingSong == null) {
            openPianoRoll(romParser, editorState)
        }
        val file = chooseMusicLoadFile("Import Impulse Tracker") { name ->
            name.endsWith(".it", ignoreCase = true)
        } ?: return

        try {
            val track = selectedTrack ?: return
            val originalBlocks = SpcData.findSongSetTransferData(romParser, track.songSet)
            val maxNativePayloadChainBytes = if (originalBlocks.isNotEmpty()) {
                MusicTransferChainBudget.MAX_SINGLE_LOROM_BANK_BYTES -
                    MusicTransferChainBudget.serializedTransferChainSize(originalBlocks) + 2
            } else {
                null
            }
            val imported = ImpulseTrackerImport.read(
                file = file,
                targetPlayIndex = track.playIndex,
                baseInstruments = originalPianoRollInstruments,
                maxNativePayloadChainBytes = maxNativePayloadChainBytes
            )
            val nativePayload = imported.nativePayload?.let { MusicEditConversion.toProjectNativePayload(it, imported.song) }
            stagePendingTrackImport(
                romParser = romParser,
                track = track,
                label = "IT",
                fileName = file.name,
                song = imported.song,
                instruments = imported.instruments,
                report = imported.report,
                nativePayload = nativePayload,
                editableFallbackSong = imported.editableFallbackSong,
                editableFallbackInstruments = imported.editableFallbackInstruments
            )
            statusMessage = "Review IT import report for ${file.name}"
            soundEditorLog.info {
                "[SPC-IMPORT] Imported IT ${file.absolutePath}: " +
                    "${imported.song.channels.sumOf { it.notes.size }} notes"
            }
        } catch (e: Exception) {
            statusMessage = "IT import failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] IT import failed: ${e.message}" }
        }
    }

    fun exportPianoRollNative(romParser: RomParser) {
        val song = editingSong ?: run {
            statusMessage = "Open Edit Track before exporting native audio"
            return
        }
        val track = selectedTrack ?: run {
            statusMessage = "Select a track before exporting native audio"
            return
        }
        val defaultName = "${musicFileStem(track.name)}.spc"
        val file = chooseMusicSaveFile(
            title = "Export SPC or N-SPC",
            defaultName = defaultName,
            extension = "spc",
            extraExtensions = setOf("nspc")
        ) ?: return

        try {
            if (file.extension.equals("nspc", ignoreCase = true)) {
                val nativeBlocks = pianoRollNativeExportBlocks(romParser, song)
                if (nativeBlocks != null) {
                    MusicTrackInterchange.writeRawTransferBlocks(
                        nativeBlocks,
                        file
                    )
                    statusMessage = "Exported raw N-SPC payload: ${file.name}"
                } else {
                    MusicTrackInterchange.writeNspcTrack(song, pianoRollInstruments, track.songSet, track.playIndex, file)
                    statusMessage = "Exported N-SPC track: ${file.name}"
                }
            } else {
                val ram = buildPianoRollSpcSnapshotRam(romParser, song)
                val spc = buildSpcSnapshotBytes(ram, track.playIndex, track.name)
                file.writeBytes(spc)
                statusMessage = "Exported SPC snapshot: ${file.name}"
            }
            soundEditorLog.info { "[SPC-IMPORT] Exported native track ${file.absolutePath}" }
        } catch (e: Exception) {
            statusMessage = "Native export failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] Native export failed: ${e.message}" }
        }
    }

    fun importPianoRollNative(romParser: RomParser, editorState: EditorState? = null) {
        val track = selectedTrack ?: run {
            statusMessage = "Select a track before importing native audio"
            return
        }
        if (!isPianoRollOpen || editingSong == null) {
            openPianoRoll(romParser, editorState)
        }
        val file = chooseMusicLoadFile("Import SPC or N-SPC") { name ->
            name.endsWith(".spc", ignoreCase = true) || name.endsWith(".nspc", ignoreCase = true)
        } ?: return

        try {
            val imported = MusicTrackInterchange.readNative(file, track.playIndex)
            val source = if (imported.sourcePlayIndex >= 0 && imported.sourcePlayIndex != track.playIndex) {
                " (source play ${imported.sourcePlayIndex})"
            } else {
                ""
            }
            stagePendingTrackImport(
                romParser = romParser,
                track = track,
                label = "${imported.formatLabel}$source",
                fileName = file.name,
                song = imported.song,
                instruments = imported.instruments,
                report = imported.report,
                nativePayload = imported.nativePayload?.let { MusicEditConversion.toProjectNativePayload(it, imported.song) }
            )
            statusMessage = "Review ${imported.formatLabel} import report for ${file.name}"
            soundEditorLog.info {
                "[SPC-IMPORT] Imported ${imported.formatLabel} ${file.absolutePath}: " +
                    "${imported.song.channels.sumOf { it.notes.size }} notes, " +
                    "${imported.instruments.size} instruments"
            }
        } catch (e: Exception) {
            statusMessage = "Native import failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] Native import failed: ${e.message}" }
        }
    }

    fun applyPendingTrackImport(editorState: EditorState? = null) {
        val pending = pendingTrackImport ?: return
        val blocked = pending.applyBlockedReason
        if (blocked != null) {
            statusMessage = "Import blocked: $blocked"
            return
        }
        applyImportedPianoRollSong(
            importedSong = pending.song,
            importedInstruments = pending.instruments,
            nativePayload = pending.nativePayload,
            editorState = editorState,
            label = pending.label
        )
        statusMessage = "Applied ${pending.label} import: ${pending.fileName}"
        pendingTrackImport = null
    }

    fun applyPendingTrackImportFitted(editorState: EditorState? = null) {
        val pending = pendingTrackImport ?: return
        val fitted = pending.fittedImport ?: return
        applyImportedPianoRollSong(
            importedSong = fitted.song,
            importedInstruments = fitted.instruments,
            nativePayload = null,
            editorState = editorState,
            label = fitted.label
        )
        statusMessage = "Applied fitted ${pending.label} import: ${pending.fileName}"
        pendingTrackImport = null
    }

    fun cancelPendingTrackImport() {
        val pending = pendingTrackImport ?: return
        pendingTrackImport = null
        statusMessage = "Cancelled ${pending.label} import"
    }

    private fun stagePendingTrackImport(
        romParser: RomParser? = null,
        track: SpcData.TrackInfo? = selectedTrack,
        label: String,
        fileName: String,
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>,
        report: MusicTrackInterchange.InterchangeReport,
        nativePayload: MusicNativePayloadEdit? = null,
        editableFallbackSong: NspcSequence.Song? = null,
        editableFallbackInstruments: List<NspcRenderer.InstrumentEntry> = emptyList()
    ) {
        val importDecision = buildPendingImportDecision(
            romParser = romParser,
            track = track,
            label = label,
            song = song,
            instruments = instruments,
            nativePayload = nativePayload,
            editableFallbackSong = editableFallbackSong,
            editableFallbackInstruments = editableFallbackInstruments
        )
        val reportLines = buildImportReportLines(report).toMutableList()
        val safetyLines = buildList {
            addAll(importDecision.reportLines)
            if (nativePayload != null) {
                add("Native sounds are shared by this bank.")
                if (currentInstrumentBank()?.instruments?.isNotEmpty() == true) add("Existing shared sound edits stay active.")
            } else if (instruments.isNotEmpty()) add("Instrument settings update this shared bank.")
            importDecision.blockReason?.let { add("Apply blocked: $it") }
            importDecision.fittedImport?.let { add(it.summary) }
        }
        if (safetyLines.isNotEmpty()) {
            reportLines.addAll(minOf(2, reportLines.size), safetyLines)
        }
        pendingTrackImport = PendingTrackImport(
            label = label,
            fileName = fileName,
            song = song,
            instruments = instruments,
            reportLines = reportLines.distinct(),
            nativePayload = nativePayload,
            applyBlockedReason = importDecision.blockReason,
            fittedImport = importDecision.fittedImport
        )
    }

    private data class PendingImportDecision(
        val blockReason: String? = null,
        val fittedImport: FittedTrackImport? = null,
        val reportLines: List<String> = emptyList()
    )

    private fun buildPendingImportDecision(
        romParser: RomParser?,
        track: SpcData.TrackInfo?,
        label: String,
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>,
        nativePayload: MusicNativePayloadEdit?,
        editableFallbackSong: NspcSequence.Song?,
        editableFallbackInstruments: List<NspcRenderer.InstrumentEntry>
    ): PendingImportDecision {
        if (instrumentBankProblem != null) {
            return PendingImportDecision(blockReason = instrumentBankProblem)
        }
        if (nativePayload != null && romParser != null && track != null) {
            editorStateRef?.project?.let { project ->
                val staged = project.copy(musicEdits = project.musicEdits.toMutableMap().also {
                    it[MusicEditConversion.key(track.songSet, track.playIndex)] = MusicEditConversion.toProjectEdit(track, song, nativePayload)
                })
                try {
                    MusicBankResolver.sourceRam(romParser, staged, track.songSet, includeOverrides = false)
                } catch (error: IllegalArgumentException) {
                    return PendingImportDecision(blockReason = error.message ?: "Native sources overlap in this bank.")
                }
            }
            val originalBlocks = SpcData.findSongSetTransferData(romParser, track.songSet)
            if (originalBlocks.isNotEmpty()) {
                val assessment = MusicTransferChainBudget.assessNativePayloadRomExport(
                    originalBlocks = originalBlocks,
                    payload = nativePayload,
                    targetPlayIndex = track.playIndex
                )
                val nativeBudgetLine = if (assessment.canExportToRom) {
                    "Native payload ROM chain ${assessment.relocatedChainBytes}/${assessment.maxChainBytes} B; " +
                        "${assessment.maxChainBytes - assessment.relocatedChainBytes} B free."
                } else {
                    "Native payload ROM chain ${assessment.relocatedChainBytes}/${assessment.maxChainBytes} B."
                }
                if (!assessment.canExportToRom) {
                    val fallbackInstruments = editableFallbackInstruments.ifEmpty {
                        originalPianoRollInstruments
                    }
                    val fallback = editableFallbackSong?.let {
                        buildFittedEditableImport(
                            song = it,
                            instruments = fallbackInstruments,
                            label = "$label stock fit",
                            includeNativeDropWarning = true
                        )
                    }
                    val reason = "native/custom-sample payload is ${assessment.overByBytes} B over the one-bank ROM export limit " +
                        "(${assessment.relocatedChainBytes}/${assessment.maxChainBytes} B)"
                    return PendingImportDecision(
                        blockReason = if (fallback != null) {
                            reason
                        } else {
                            "$reason; no editable fitted fallback is available"
                        },
                        fittedImport = fallback,
                        reportLines = listOf(nativeBudgetLine)
                    )
                }
                return PendingImportDecision(reportLines = listOf(nativeBudgetLine))
            }
        }

        if (nativePayload == null && pianoRollRelocationBudgetBytes > 0) {
            val encodedBytes = NspcSequence.encodedSequenceSize(song)
            val editableBudgetLine = if (encodedBytes <= pianoRollRelocationBudgetBytes) {
                "Editable relocation fit ${encodedBytes}/${pianoRollRelocationBudgetBytes} B; " +
                    "${pianoRollRelocationBudgetBytes - encodedBytes} B free."
            } else {
                "Editable relocation fit ${encodedBytes}/${pianoRollRelocationBudgetBytes} B."
            }
            if (encodedBytes > pianoRollRelocationBudgetBytes) {
                val fitted = buildFittedEditableImport(
                    song = song,
                    instruments = instruments,
                    label = "$label fitted",
                    includeNativeDropWarning = false
                )
                val reason = "editable sequence is ${encodedBytes - pianoRollRelocationBudgetBytes} B over the current relocation budget " +
                    "($encodedBytes/$pianoRollRelocationBudgetBytes B)"
                return PendingImportDecision(
                    blockReason = if (fitted != null) reason else "$reason; it could not be trimmed safely",
                    fittedImport = fitted,
                    reportLines = listOf(editableBudgetLine)
                )
            }
            return PendingImportDecision(reportLines = listOf(editableBudgetLine))
        }

        return PendingImportDecision()
    }

    private fun buildFittedEditableImport(
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>,
        label: String,
        includeNativeDropWarning: Boolean
    ): FittedTrackImport? {
        val budgetBytes = pianoRollRelocationBudgetBytes
        if (budgetBytes <= 0) return null
        val fit = try {
            MusicSequenceBudget.fitSongToEncodedBudget(song, budgetBytes)
        } catch (e: Exception) {
            soundEditorLog.warn(e) { "[SPC-IMPORT] Could not build fitted import: ${e.message}" }
            return null
        }
        val fittedSong = PianoRollPreviewLogic.deepCopySong(fit.song)
        fittedSong.isModified = true
        val copiedInstruments = instruments.map { it.copy() }
        val action = if (fit.trimmed) {
            "Apply Fitted will trim ${fit.removedNotes} notes and ${fit.removedCommands} commands after tick " +
                "${fit.cutoffTick} to fit ${fit.encodedBytes}/${fit.budgetBytes} B."
        } else {
            "Apply Fitted will fit editable notes at ${fit.encodedBytes}/${fit.budgetBytes} B."
        }
        val summary = if (includeNativeDropWarning) {
            action.replace("Apply Fitted", "Apply Stock Fit") +
                " Custom sample payload will be dropped; built-in/fallback instruments will be used."
        } else {
            action
        }
        return FittedTrackImport(
            label = label,
            song = fittedSong,
            instruments = copiedInstruments,
            summary = summary,
            usesStockInstrumentFallback = includeNativeDropWarning
        )
    }

    private fun buildImportReportLines(report: MusicTrackInterchange.InterchangeReport): List<String> {
        val lines = mutableListOf<String>()
        lines += report.compactSummary()
        lines += "tempo ${report.tempo}, editable N-SPC estimate ${report.encodedBytes} B"
        if (pianoRollExportBudgetBytes > 0 && report.encodedBytes > pianoRollExportBudgetBytes) {
            lines += "Over vanilla track budget by ${report.encodedBytes - pianoRollExportBudgetBytes} B; ROM export will require relocation or trimming."
        }
        if (pianoRollRelocationBudgetBytes > 0 && report.encodedBytes > pianoRollRelocationBudgetBytes) {
            lines += "Over current relocation budget by ${report.encodedBytes - pianoRollRelocationBudgetBytes} B; use Apply Fitted to trim before applying."
        }
        if (report.activeChannels > 8) {
            lines += "More than 8 active channels are not supported by SNES playback."
        }
        lines += report.warnings
        return lines
    }

    internal fun applyImportedPianoRollSong(
        importedSong: NspcSequence.Song,
        importedInstruments: List<NspcRenderer.InstrumentEntry>,
        nativePayload: MusicNativePayloadEdit?,
        editorState: EditorState?,
        label: String
    ) {
        val track = selectedTrack ?: return
        if (nativePayload == null && importedInstruments.isNotEmpty()) {
            MusicInstrumentBanks.writes(importedInstruments.associate { it.index to MusicInstrumentBanks.entry(it) })
        }
        require(instrumentBankProblem == null) {
            instrumentBankProblem.orEmpty()
        }
        stopPlayback()
        recordPianoRollEdit("Import $label")
        val nextSong = PianoRollPreviewLogic.deepCopySong(importedSong)
        nextSong.title = track.name
        nextSong.isModified = true
        editingSong = nextSong
        if (importedInstruments.isNotEmpty()) {
            pianoRollInstruments = importedInstruments.map { it.copy() }
        }
        setPianoRollSoundBank(
            nativePayload,
            pianoRollInstruments
        )
        // An editable bundle explicitly imports its settings into the shared bank owner.
        if (nativePayload == null && importedInstruments.isNotEmpty()) {
            val parser = romParserRef
            val editor = editorState ?: editorStateRef
            if (parser != null && editor != null) {
                val source = MusicBankResolver.sourceRam(parser, requireNotNull(projectForBank(nextSong)), track.songSet, includeOverrides = false)
                val defaults = NspcRenderer.readInstrumentTable(source).associateBy { it.index }
                val values = currentInstrumentBank()?.instruments?.toMutableMap() ?: mutableMapOf()
                for (entry in importedInstruments) {
                    if (PianoRollPreviewLogic.instrumentBytes(entry).contentEquals(PianoRollPreviewLogic.instrumentBytes(defaults.getValue(entry.index)))) values.remove(entry.index)
                    else values[entry.index] = MusicInstrumentBanks.entry(entry)
                }
                editor.setMusicInstrumentBank(track.songSet, (currentInstrumentBank() ?: MusicInstrumentBankEdit()).copy(instruments = values))
                refreshOpenBankInstruments()
            }
        }
        pianoRollPlaybackTick = 0
        syncPianoRollProjectEdit(editorState)
        soundEditorLog.info {
            "[SPC-IMPORT] Applied $label to '${track.name}': " +
                "${nextSong.channels.sumOf { it.notes.size }} notes, " +
                "${pianoRollInstruments.size} instruments"
        }
    }

    internal fun buildPianoRollSpcSnapshotRam(
        romParser: RomParser,
        song: NspcSequence.Song
    ): ByteArray {
        val track = selectedTrack ?: error("No selected track")
        return buildTrackSpcSnapshotRam(
            romParser = romParser,
            track = track,
            song = song,
            instruments = pianoRollInstruments,
            originalSong = originalPianoRollSong,
            nativePayload = pianoRollNativeSource
        )
    }

    private fun buildTrackSpcSnapshotRam(
        romParser: RomParser,
        track: SpcData.TrackInfo,
        song: NspcSequence.Song,
        instruments: List<NspcRenderer.InstrumentEntry>,
        originalSong: NspcSequence.Song?,
        nativePayload: MusicNativePayloadEdit? = null
    ): ByteArray {
        val bankProject = if (isPianoRollOpen) projectForBank(song) else editorStateRef?.project
        bankProject?.let { return MusicBankResolver.resolve(romParser, it, track.songSet).ram }
        if (nativePayload != null) {
            return ImportedMusicResolver.resolve(romParser, MusicEditConversion.toProjectEdit(
                track, song, nativePayload
            )).ram
        }
        val baseRam = SpcData.buildInitialSpcRam(romParser)
        val songBlocks = SpcData.findSongSetTransferData(romParser, track.songSet)
        val ram = baseRam.copyOf()
        SpcData.applyTransferBlocks(ram, songBlocks)

        if (instruments.isNotEmpty()) {
            PianoRollPreviewLogic.writeInstrumentEntries(ram, instruments)
        }

        val hasNoteDelta = PianoRollPreviewLogic.deltaOverlayPlan(song, originalSong)?.hasDelta
            ?: song.isModified
        if (hasNoteDelta) {
            val sequenceWrites = try {
                relocatedSequencePatchForTrack(
                    romParser = romParser,
                    songBlocks = songBlocks,
                    spcRam = ram,
                    track = track,
                    song = song
                ).writes
            } catch (e: Exception) {
                soundEditorLog.warn(e) {
                    "[SPC-IMPORT] Relocated SPC snapshot encode failed; trying direct encode: ${e.message}"
                }
                NspcSequence.encode(song, track.playIndex, ram, failOnOverflow = true)
            }
            applySpcWrites(ram, sequenceWrites)
        }

        return ram
    }

    internal fun buildSpcSnapshotBytes(ram: ByteArray, playIndex: Int, title: String): ByteArray {
        if (!NativeSpcEmulator.isAvailable()) {
            soundEditorLog.warn { "[SPC-IMPORT] libspc unavailable; exporting static SPC RAM image" }
            return NativeSpcEmulator.buildSpcFileImage(ram, title)
        }
        return try {
            NativeSpcEmulator().use { emu ->
                // Snapshot before advancing the song so short arrangements retain their attack.
                emu.loadFromRam(ram, emptyList(), playIndex, playbackWarmupSeconds = 0)
                emu.saveSpcSnapshot(title)
            }
        } catch (e: LinkageError) {
            soundEditorLog.warn { "[SPC-IMPORT] Native SPC snapshot export unavailable; exporting static SPC RAM image: ${e.message}" }
            NativeSpcEmulator.buildSpcFileImage(ram, title)
        } catch (e: Exception) {
            soundEditorLog.warn(e) {
                "[SPC-IMPORT] Native SPC snapshot export failed; exporting static SPC RAM image: ${e.message}"
            }
            NativeSpcEmulator.buildSpcFileImage(ram, title)
        }
    }

    private data class TrackInterchangeData(
        val track: SpcData.TrackInfo,
        val song: NspcSequence.Song,
        val originalSong: NspcSequence.Song,
        val instruments: List<NspcRenderer.InstrumentEntry>,
        val nativePayload: MusicNativePayloadEdit? = null
    )

    private fun resolveTrackInterchangeData(
        romParser: RomParser,
        editorState: EditorState?
    ): TrackInterchangeData? {
        if (editorState != null) editorStateRef = editorState
        val track = selectedTrack ?: run {
            statusMessage = "Select a track before exporting"
            return null
        }
        return try {
            val ram = buildSpcRamForTrack(romParser, track.songSet)
            val originalSong = NspcSequence.parse(ram, track.playIndex).also {
                it.title = track.name
            }
            val originalInstruments = NspcRenderer.readInstrumentTable(ram)
            val savedEdit = editorState?.getMusicEdit(track.songSet, track.playIndex)
            val song = savedEdit?.let { MusicEditConversion.toSong(it) } ?: originalSong
            song.title = track.name
            TrackInterchangeData(track, song, originalSong, originalInstruments, savedEdit?.nativePayload)
        } catch (e: Exception) {
            statusMessage = "Track export setup failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC-IMPORT] Failed to resolve track export data: ${e.message}" }
            null
        }
    }

    private fun applySpcWrites(ram: ByteArray, writes: Map<Int, ByteArray>) {
        for ((addr, data) in writes) {
            require(addr >= 0 && addr + data.size <= ram.size) {
                "SPC write 0x${addr.toString(16)} overruns RAM"
            }
            data.copyInto(ram, addr)
        }
    }

    private fun chooseMusicSaveFile(
        title: String,
        defaultName: String,
        extension: String,
        extraExtensions: Set<String> = emptySet()
    ): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
        dialog.file = defaultName
        val allowedExtensions = setOf(extension.lowercase()) + extraExtensions.mapTo(mutableSetOf()) { it.lowercase() }
        dialog.setFilenameFilter { _, name ->
            allowedExtensions.any { name.endsWith(".$it", ignoreCase = true) }
        }
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val fileName = dialog.file ?: return null
        val normalized = if (fileName.contains('.')) fileName else "$fileName.$extension"
        return File(dir, normalized)
    }

    private fun chooseMusicLoadFile(title: String, filter: (String) -> Boolean): File? {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> filter(name) }
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val fileName = dialog.file ?: return null
        return File(dir, fileName)
    }

    private fun musicFileStem(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
            .ifBlank { "track" }

    /** Start piano roll playback with tick tracking. */
    fun startPianoRollPlayback(wav: ShortArray, approximate: Boolean = false) {
        stopInstrumentPreview()
        val song = editingSong ?: return
        val preview = normalizePreviewPcm(wav, extraGain = EDIT_TRACK_PREVIEW_GAIN)
        pianoRollTickMs = PianoRollPlaybackTiming.tickMillis(song.tempo)
        pianoRollPlayStartMs = System.currentTimeMillis()
        pianoRollPlaybackTick = 0
        val peak = if (wav.isNotEmpty()) wav.maxOf { kotlin.math.abs(it.toInt()) } else 0
        val previewPeak = if (preview.isNotEmpty()) preview.maxOf { kotlin.math.abs(it.toInt()) } else 0
        soundEditorLog.info {
            "[SPC-PIANO] Starting playback: ${preview.size} samples (${preview.size / 32000}s), " +
                "tempo=${song.tempo}, ticks=${song.totalTicks}, tickMs=${"%.1f".format(pianoRollTickMs)}, " +
                "peak=$peak, previewPeak=$previewPeak"
        }
        player.onComplete = {
            isPlaying = false
            playbackPosition = 0f
            pianoRollPlaybackTick = -1
        }
        player.play(preview, NativeSpcEmulator.SAMPLE_RATE)
        isPlaying = true
        statusMessage = if (approximate) "Approximate preview · native playback unavailable" else "Playing edit preview..."
    }

    /** Update piano roll playback tick based on elapsed time. */
    fun updatePianoRollTick() {
        if (!isPlaying || pianoRollPlaybackTick < 0) return
        val song = editingSong ?: return
        if (!player.isActive()) {
            isPlaying = false
            return
        }
        if (pianoRollTickMs <= 0.0) {
            pianoRollTickMs = PianoRollPlaybackTiming.tickMillis(song.tempo)
        }
        val maxTick = song.totalTicks
        pianoRollPlaybackTick = PianoRollPlaybackTiming.playbackTickAtMillis(
            positionMillis = player.positionMillis(),
            tempo = song.tempo,
            maxTick = maxTick
        )
    }

    fun seekPianoRollToTick(tick: Int) {
        val song = editingSong ?: run {
            pianoRollPlaybackTick = tick.coerceAtLeast(0)
            return
        }
        if (pianoRollTickMs <= 0.0) {
            pianoRollTickMs = PianoRollPlaybackTiming.tickMillis(song.tempo)
        }
        val clampedTick = tick.coerceIn(0, song.totalTicks.coerceAtLeast(0))
        val seekMs = PianoRollPlaybackTiming.tickToMillis(clampedTick, song.tempo)
        pianoRollPlaybackTick = clampedTick
        pianoRollPlayStartMs = System.currentTimeMillis() - seekMs
        if (player.isActive()) {
            player.seekMillis(seekMs)
            playbackPosition = player.positionFraction()
        }
    }

    /** Notify that the song was modified (triggers recompose). */
    fun notifySongChanged(song: NspcSequence.Song, editorState: EditorState? = null) {
        val prevNotes = editingSong?.channels?.sumOf { it.notes.size } ?: 0
        val newNotes = song.channels.sumOf { it.notes.size }
        val hasNoteDelta = PianoRollPreviewLogic.deltaOverlayPlan(song, originalPianoRollSong)?.hasDelta ?: true
        song.isModified = hasNoteDelta
        soundEditorLog.info { "[SPC-PIANO] Song modified: $prevNotes -> $newNotes notes, noteDelta=$hasNoteDelta" }
        editingSong = PianoRollPreviewLogic.deepCopySong(song)
        syncPianoRollProjectEdit(editorState)
    }

    fun recordPianoRollEdit(label: String) {
        val song = editingSong ?: return
        pianoRollHistory.record(song, pianoRollInstruments, label, pianoRollNativeSource, currentInstrumentBank())
        syncPianoRollHistoryDepths()
        soundEditorLog.info { "[SPC-PIANO-UNDO] Record '$label' undo=$pianoRollUndoDepth redo=$pianoRollRedoDepth" }
    }

    fun undoPianoRollEdit(editorState: EditorState? = null): Boolean {
        val song = editingSong ?: return false
        val snapshot = pianoRollHistory.undo(song, pianoRollInstruments, pianoRollNativeSource, currentInstrumentBank()) ?: return false
        restoreInstrumentBank(snapshot.instrumentBank, editorState)
        restorePianoRollSnapshot(snapshot)
        syncPianoRollHistoryDepths()
        syncPianoRollProjectEdit(editorState)
        romParserRef?.let { refreshInstrumentBank(it, editorState ?: editorStateRef); refreshOpenBankInstruments() }
        soundEditorLog.info { "[SPC-PIANO-UNDO] Undo '${snapshot.label}' undo=$pianoRollUndoDepth redo=$pianoRollRedoDepth" }
        return true
    }

    fun redoPianoRollEdit(editorState: EditorState? = null): Boolean {
        val song = editingSong ?: return false
        val snapshot = pianoRollHistory.redo(song, pianoRollInstruments, pianoRollNativeSource, currentInstrumentBank()) ?: return false
        restoreInstrumentBank(snapshot.instrumentBank, editorState)
        restorePianoRollSnapshot(snapshot)
        syncPianoRollHistoryDepths()
        syncPianoRollProjectEdit(editorState)
        romParserRef?.let { refreshInstrumentBank(it, editorState ?: editorStateRef); refreshOpenBankInstruments() }
        soundEditorLog.info { "[SPC-PIANO-UNDO] Redo '${snapshot.label}' undo=$pianoRollUndoDepth redo=$pianoRollRedoDepth" }
        return true
    }

    private fun restorePianoRollSnapshot(snapshot: PianoRollEditLogic.EditSnapshot) {
        stopTrackPlayback()
        if (!liveInstrumentPreview) stopInstrumentPreview()
        val restored = PianoRollPreviewLogic.deepCopySong(snapshot.song)
        editingSong = restored
        pianoRollInstruments = snapshot.instruments.map { it.copy() }
        pianoRollNativeSource = MusicEditConversion.copyNativePayload(snapshot.nativePayload)
        restored.isModified = hasPianoRollEdits(restored)
        pianoRollPlaybackTick = pianoRollPlaybackTick.coerceAtMost(restored.totalTicks)
        statusMessage = "Restored: ${snapshot.label}"
        invalidateInstrumentPreview(refreshOriginal = true)
    }

    private fun clearPianoRollHistory() {
        pianoRollHistory.clear()
        syncPianoRollHistoryDepths()
    }

    private fun syncPianoRollHistoryDepths() {
        pianoRollUndoDepth = pianoRollHistory.undoDepth
        pianoRollRedoDepth = pianoRollHistory.redoDepth
    }

    /** A late focus/wheel callback from a closed/switched inspector must not edit the next bank. */
    internal fun commitPianoRollInstrument(expectedTrackId: Int, updated: NspcRenderer.InstrumentEntry, romParser: RomParser?, editorState: EditorState? = null): Boolean {
        if (!isPianoRollOpen || selectedTrackId != expectedTrackId || instrumentBankProblem != null) return false
        val before = pianoRollInstruments.getOrNull(updated.index) ?: return false
        if (PianoRollPreviewLogic.instrumentBytes(before).contentEquals(PianoRollPreviewLogic.instrumentBytes(updated))) return false
        recordPianoRollEdit("Instrument edit")
        updatePianoRollInstrument(updated, romParser, editorState)
        return true
    }

    fun updatePianoRollInstrument(
        updated: NspcRenderer.InstrumentEntry,
        romParser: RomParser?,
        editorState: EditorState? = null
    ) {
        val idx = updated.index
        if (idx !in pianoRollInstruments.indices) return
        if (instrumentBankProblem != null) {
            statusMessage = instrumentBankProblem.orEmpty()
            return
        }
        stopTrackPlayback()
        if (!liveInstrumentPreview || instrumentPreviewRequest?.index != idx) stopInstrumentPreview()

        val old = pianoRollInstruments[idx]
        val normalized = updated.copy(
            // Preserve the engine's noise source flag when editing other settings.
            srcn = if (updated.srcn >= 0x80) updated.srcn.coerceAtMost(255) else updated.srcn.coerceIn(0, SpcData.SAMPLE_COUNT - 1),
            adsr1 = updated.adsr1.coerceIn(0, 255),
            adsr2 = updated.adsr2.coerceIn(0, 255),
            gain = updated.gain.coerceIn(0, 255),
            pitchAdj = updated.pitchAdj.coerceIn(0, 0xFFFF),
            index = old.index,
            tableAddr = old.tableAddr
        )
        val next = pianoRollInstruments.toMutableList()
        next[idx] = normalized

        val projectState = editorState ?: editorStateRef
        val track = selectedTrack
        if (projectState != null && track != null && romParser != null) {
            val source = MusicBankResolver.sourceRam(romParser, requireNotNull(projectForBank()), track.songSet, includeOverrides = false)
            val base = NspcRenderer.readInstrumentTable(source)[idx]
            val values = currentInstrumentBank()?.instruments?.toMutableMap() ?: mutableMapOf()
            if (PianoRollPreviewLogic.instrumentBytes(base).contentEquals(PianoRollPreviewLogic.instrumentBytes(normalized))) values.remove(idx)
            else values[idx] = MusicInstrumentBanks.entry(normalized)
            projectState.setMusicInstrumentBank(track.songSet, (currentInstrumentBank() ?: MusicInstrumentBankEdit()).copy(instruments = values))
        }

        pianoRollInstruments = if (romParser != null) {
            rebuildInstrumentMetadata(romParser, next)
        } else {
            next
        }

        val before = "srcn=${hexByte(old.srcn)} adsr1=${hexByte(old.adsr1)} adsr2=${hexByte(old.adsr2)} " +
            "gain=${hexByte(old.gain)} pitch=${hexWord(old.pitchAdj)}"
        val after = "srcn=${hexByte(normalized.srcn)} adsr1=${hexByte(normalized.adsr1)} adsr2=${hexByte(normalized.adsr2)} " +
            "gain=${hexByte(normalized.gain)} pitch=${hexWord(normalized.pitchAdj)}"
        soundEditorLog.info { "[SPC-PIANO-INST] Set index=${hexByte(idx)} $before -> $after" }
        syncPianoRollProjectEdit(editorState)
        statusMessage = "Edited shared instrument #$idx"
        // A chosen source replaces a candidate audition, retaining the auditioned note.
        instrumentPreviewRequest = instrumentPreviewRequest?.copy(sampleSource = null)
        instrumentPreviewSample = null
        invalidateInstrumentPreview()
    }

    private fun hasPianoRollEdits(song: NspcSequence.Song): Boolean {
        val hasNoteDelta = PianoRollPreviewLogic.deltaOverlayPlan(song, originalPianoRollSong)?.hasDelta
            ?: song.isModified
        return pianoRollNativeSource != null || hasNoteDelta
    }

    private fun setPianoRollSoundBank(
        payload: MusicNativePayloadEdit?,
        instruments: List<NspcRenderer.InstrumentEntry>
    ) {
        pianoRollNativeSource = MusicEditConversion.copyNativePayload(payload)
        pianoRollInstruments = romParserRef?.let { rebuildInstrumentMetadata(it, instruments) } ?: instruments
        pianoRollSourceInstruments = emptyList() // Musical tuning requires a resolved source, never a guessed reference.
        refreshOpenBankInstruments()
    }

    private fun clearNativeSource() {
        pianoRollNativeSource = null
    }

    private fun currentNativeImportEdit(song: NspcSequence.Song): MusicTrackEdit =
        MusicEditConversion.toProjectEdit(requireNotNull(selectedTrack), song, pianoRollNativeSource)

    internal fun pianoRollNativeExportBlocks(romParser: RomParser, song: NspcSequence.Song): List<SpcData.TransferBlock>? =
        projectForBank(song)?.let { project ->
            val track = requireNotNull(selectedTrack)
            if (project.musicEdits.values.any { it.songSet == track.songSet && it.nativePayload != null } || currentInstrumentBank()?.samples?.isNotEmpty() == true) MusicBankResolver.resolve(romParser, project, track.songSet).blocks
            else null
        } ?: pianoRollNativeSource?.let { ImportedMusicResolver.resolve(romParser, currentNativeImportEdit(song)).blocks }

    private fun currentInstrumentBank(): MusicInstrumentBankEdit? = selectedTrack?.let {
        editorStateRef?.project?.musicInstrumentBanks?.get(MusicInstrumentBanks.key(it.songSet))
    }

    private fun restoreInstrumentBank(bank: MusicInstrumentBankEdit?, editor: EditorState?) {
        val track = selectedTrack ?: return
        (editor ?: editorStateRef)?.setMusicInstrumentBank(track.songSet, bank)
    }

    private fun refreshInstrumentBank(parser: RomParser, editor: EditorState?) {
        val track = selectedTrack ?: return
        instrumentBankProblem = null
        instrumentBankTracks = (SpcData.KNOWN_TRACKS.filter { it.songSet == track.songSet }.map { it.name } +
            editor?.project?.musicEdits?.values.orEmpty().filter { it.songSet == track.songSet }.map { it.trackName }).distinct()
        if (editor == null) return
        try {
            MusicBankResolver.sourceRam(parser, editor.project, track.songSet)
        } catch (error: Exception) {
            instrumentBankProblem = error.message ?: "Invalid sound-bank settings"
        }
    }

    fun resetSharedInstrument(index: Int) {
        val parser = romParserRef ?: return
        val track = selectedTrack ?: return
        if (currentInstrumentBank()?.instruments?.containsKey(index) != true) return
        val source = MusicBankResolver.sourceRam(parser, requireNotNull(projectForBank()), track.songSet, includeOverrides = false)
        recordPianoRollEdit("Reset shared instrument #$index")
        updatePianoRollInstrument(NspcRenderer.readInstrumentTable(source)[index], parser, editorStateRef)
    }

    private fun refreshOpenBankInstruments() {
        val parser = romParserRef ?: return
        val track = selectedTrack ?: return
        if (instrumentBankProblem != null) return
        val project = projectForBank() ?: return
        try {
            val source = MusicBankResolver.sourceRam(parser, project, track.songSet, includeOverrides = false)
            pianoRollSourceInstruments = NspcRenderer.readInstrumentTable(source)
            cacheSampleSource(source, parser, project, track.songSet)
            MusicInstrumentBanks.writes(MusicInstrumentBanks.instruments(project, track.songSet)).forEach { (at, data) -> data.copyInto(source, at) }
            pianoRollInstruments = NspcRenderer.readInstrumentTable(source)
        } catch (error: Exception) {
            instrumentBankProblem = error.message ?: "Cannot load shared sound bank"
        }
    }

    private fun cacheSampleSource(ram: ByteArray, parser: RomParser, project: SmEditProject, songSet: Int) {
        val bankBlocks = SpcData.findSongSetTransferData(parser, songSet)
        val imports = project.musicEdits.toSortedMap().values.filter { it.songSet == songSet }.mapNotNull { edit ->
            edit.nativePayload?.let { it.sourceFileName to MusicEditConversion.toTransferBlocks(it) }
        }
        fun shape(blocks: List<SpcData.TransferBlock>) = blocks.map { it.destAddr to it.data.size }
        if (sampleCatalogSongSet == songSet && sampleCatalogRam?.contentEquals(ram) == true &&
            shape(bankBlocks) == shape(sampleCatalogBankBlocks) &&
            imports.map { it.first to shape(it.second) } == sampleCatalogImports.map { it.first to shape(it.second) }) return
        sampleCatalogRam = ram.copyOf()
        sampleCatalogSongSet = songSet
        sampleCatalogBankBlocks = bankBlocks
        sampleCatalogImports = imports
        sampleCatalogCustom = project.musicInstrumentBanks[MusicInstrumentBanks.key(songSet)]?.samples.orEmpty().values.flatMap { sample ->
            listOf(SpcData.TransferBlock(sample.startAddr, MusicSampleBanks.decode(sample)),
                SpcData.TransferBlock(SpcData.SAMPLE_DIRECTORY + sample.srcn * 4, ram.copyOfRange(SpcData.SAMPLE_DIRECTORY + sample.srcn * 4, SpcData.SAMPLE_DIRECTORY + sample.srcn * 4 + 4)))
        }
        pianoRollSampleVersion++
        pianoRollSamples = emptyList()
        pianoRollSamplesProblem = null
        isLoadingPianoRollSamples = true
    }

    /** Detached snapshot in, small display catalog out; never decodes BRR during composition. */
    suspend fun loadPianoRollSampleCatalog() {
        val ram = sampleCatalogRam ?: return
        val version = pianoRollSampleVersion
        if (loadedSampleVersion == version) return
        val blocks = sampleCatalogBankBlocks
        val imports = sampleCatalogImports
        val custom = sampleCatalogCustom
        isLoadingPianoRollSamples = true
        try {
            val samples = withContext(Dispatchers.Default) { MusicSampleCatalog.read(ram, blocks, imports, custom) }
            if (version != pianoRollSampleVersion) return
            pianoRollSamples = samples
            loadedSampleVersion = version
            pianoRollSamplesProblem = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (version == pianoRollSampleVersion) pianoRollSamplesProblem = error.message ?: "Cannot read bank samples"
        } finally {
            if (version == pianoRollSampleVersion) isLoadingPianoRollSamples = false
        }
    }

    fun choosePianoRollSample(index: Int, srcn: Int): Boolean {
        val parser = romParserRef ?: return false
        val editor = editorStateRef ?: return false
        if (loadedSampleVersion != pianoRollSampleVersion || instrumentBankProblem != null) return false
        val sample = pianoRollSamples.firstOrNull { it.srcn == srcn } ?: return false
        val entry = pianoRollInstruments.firstOrNull { it.index == index } ?: return false
        if (entry.srcn == srcn) return false
        recordPianoRollEdit("Change sound recording")
        updatePianoRollInstrument(MusicSampleCatalog.apply(entry, sample), parser, editor)
        return true
    }

    internal fun useWorkbenchSample(application: MusicSampleBanks.Application, parser: RomParser, editor: EditorState): Boolean {
        val changed = applySoundBank(application.target, application.bank, application.changed, "Use sample in sound", parser, editor)
        if (changed) statusMessage = "Using sample in ${application.target.soundName} · ${application.remainingBytes} B sample memory available"
        return changed
    }

    internal fun useSoundPreset(application: SoundPresets.Application, parser: RomParser, editor: EditorState): Boolean {
        val changed = applySoundBank(application.target, application.bank, application.changed, "Apply sound preset", parser, editor)
        if (changed) statusMessage = "Applied ${application.preset.name} to ${application.target.soundName} · ${application.remainingBytes} B sample memory available"
        return changed
    }

    private fun applySoundBank(target: SampleBankPreview.Target, bank: MusicInstrumentBankEdit, changed: Boolean,
        label: String, parser: RomParser, editor: EditorState): Boolean {
        val track = selectedTrack ?: error("Choose a track before using this sample")
        require(isPianoRollOpen && track.songSet == target.songSet && (target.trackId == null || track.id == target.trackId)) {
            "The selected track changed. Reopen the sound library or sample workbench."
        }
        require(editor.project.projectId == target.project.projectId && editor.project.romPath == target.project.romPath &&
            editor.project.musicEdits == target.project.musicEdits && editor.project.musicInstrumentBanks == target.project.musicInstrumentBanks) {
            "The sound bank changed. Reopen the sound library or sample workbench."
        }
        if (!changed) return false
        require(instrumentBankProblem == null) { instrumentBankProblem.orEmpty() }
        val staged = MusicBankTransitions.snapshot(editor.project)
        val key = MusicInstrumentBanks.key(track.songSet)
        if (bank.instruments.isEmpty() && bank.samples.isEmpty()) staged.musicInstrumentBanks.remove(key)
        else staged.musicInstrumentBanks[key] = requireNotNull(MusicInstrumentBanks.copy(bank))
        MusicBankResolver.resolve(parser, staged, track.songSet)
        recordPianoRollEdit(label)
        stopPlayback()
        instrumentAuditionOriginal = false
        editor.setMusicInstrumentBank(track.songSet, bank)
        refreshInstrumentBank(parser, editor)
        refreshOpenBankInstruments()
        return true
    }

    private fun projectForBank(song: NspcSequence.Song? = null): SmEditProject? {
        val project = editorStateRef?.project ?: return null
        val track = selectedTrack ?: return project
        val live = song ?: editingSong?.takeIf { isPianoRollOpen }
        if (live == null) return project
        return project.copy(musicEdits = project.musicEdits.toMutableMap().also {
            it[MusicEditConversion.key(track.songSet, track.playIndex)] = currentNativeImportEdit(live)
        })
    }

    fun syncPianoRollProjectEdit(editorState: EditorState?) {
        val projectState = editorState ?: return
        val track = selectedTrack ?: return
        val song = editingSong ?: return
        val key = MusicEditConversion.key(track.songSet, track.playIndex)

        if (!hasPianoRollEdits(song)) {
            if (projectState.removeMusicEdit(key)) {
                soundEditorLog.info { "[SPC-PIANO-SAVE] Removed project music edit key=$key (${track.name})" }
            }
            return
        }

        val nativePayload = MusicEditConversion.copyNativePayload(pianoRollNativeSource)
        projectState.setMusicEdit(key, MusicEditConversion.toProjectEdit(track, song, nativePayload))
        soundEditorLog.info { "[SPC-PIANO-SAVE] Staged project music edit key=$key (${track.name})" }
    }

    fun removePianoRollProjectEdit(editorState: EditorState?) {
        val projectState = editorState ?: return
        val track = selectedTrack ?: return
        val key = MusicEditConversion.key(track.songSet, track.playIndex)
        if (projectState.removeMusicEdit(key)) {
            soundEditorLog.info { "[SPC-PIANO-SAVE] Removed project music edit key=$key (${track.name})" }
        }
    }

    fun applyPianoRollInstrumentEdits(spcRam: ByteArray) {
        PianoRollPreviewLogic.applyInstrumentEdits(
            spcRam,
            pianoRollInstruments,
            originalPianoRollInstruments
        )
    }

    suspend fun renderPianoRollFallbackPreview(
        romParser: RomParser,
        song: NspcSequence.Song
    ): ShortArray {
        val track = selectedTrack
        val bankProject = projectForBank(song)
        if (bankProject != null && track != null) {
            if (instrumentBankProblem != null) return ShortArray(0)
            return withContext(Dispatchers.Default) {
                val ram = MusicBankResolver.sourceRam(romParser, bankProject, track.songSet)
                val events = song.channels.flatMapIndexed { ch, channel -> channel.notes.map { PianoRollPreviewLogic.noteEvent(ch, it) } }
                NspcRenderer.renderToWav(events, ram, tempo = song.tempo)
            }
        }
        val importedEdit = pianoRollNativeSource?.let { currentNativeImportEdit(song) }
        return withContext(Dispatchers.Default) {
            if (importedEdit != null && track != null) {
                val ram = ImportedMusicResolver.sourceRam(romParser, track.songSet, track.playIndex,
                    requireNotNull(importedEdit.nativePayload))
                val events = song.channels.flatMapIndexed { ch, channel -> channel.notes.map { PianoRollPreviewLogic.noteEvent(ch, it) } }
                return@withContext NspcRenderer.renderToWav(events, ram, tempo = song.tempo)
            }
            val ram = buildSpcRamForTrack(romParser, currentSongSet.coerceAtLeast(0))
            applyPianoRollInstrumentEdits(ram)

            val deltaPlan = PianoRollPreviewLogic.deltaOverlayPlan(song, originalPianoRollSong)
            if (deltaPlan != null && deltaPlan.hasDelta && track != null && NativeSpcEmulator.isAvailable()) {
                val nativeBase = renderOriginalNativeForHybrid(romParser, track, seconds = 120)
                if (nativeBase != null && nativeBase.isNotEmpty()) {
                    val maxSeconds = nativeBase.size / 32000.0
                    val additions = if (deltaPlan.addEvents.isNotEmpty()) {
                        NspcRenderer.renderToWav(
                            deltaPlan.addEvents,
                            ram,
                            tempo = song.tempo,
                            maxSeconds = maxSeconds,
                            normalize = false
                        )
                    } else {
                        ShortArray(0)
                    }
                    val removals = if (deltaPlan.removeEvents.isNotEmpty()) {
                        NspcRenderer.renderToWav(
                            deltaPlan.removeEvents,
                            ram,
                            tempo = song.tempo,
                            maxSeconds = maxSeconds,
                            normalize = false
                        )
                    } else {
                        ShortArray(0)
                    }
                    val mixed = PianoRollPreviewLogic.mixPreviewDeltaPcm(nativeBase, additions, removals)
                    val basePeak = PianoRollPreviewLogic.peakOf(nativeBase)
                    val addPeak = PianoRollPreviewLogic.peakOf(additions)
                    val removePeak = PianoRollPreviewLogic.peakOf(removals)
                    val mixedPeak = PianoRollPreviewLogic.peakOf(mixed)
                    soundEditorLog.info {
                        "[SPC-PIANO] Delta hybrid preview: native original +" +
                            "${deltaPlan.addEvents.size} -${deltaPlan.removeEvents.size} note overlays, " +
                            "basePeak=$basePeak, addPeak=$addPeak, removePeak=$removePeak, mixedPeak=$mixedPeak"
                    }
                    return@withContext mixed
                }
            }

            if (deltaPlan == null) {
                soundEditorLog.warn { "[SPC-PIANO] Fallback requires full software render; original edit baseline unavailable" }
            } else if (deltaPlan.hasDelta) {
                soundEditorLog.warn { "[SPC-PIANO] Fallback using full software render; delta hybrid preview unavailable" }
            } else {
                soundEditorLog.info { "[SPC-PIANO] Fallback using full software render; no edit delta found" }
            }

            val events = song.channels.flatMapIndexed { ch, channel ->
                channel.notes.map { note -> PianoRollPreviewLogic.noteEvent(ch, note) }
            }
            NspcRenderer.renderToWav(events, ram, tempo = song.tempo)
        }
    }

    /**
     * Render a song through the native SPC emulator.
     * - If unmodified: renders the original track directly (perfect sound)
     * - If modified: encodes edited notes to N-SPC binary, patches into SPC RAM, then renders
     */
    suspend fun renderEditedSong(romParser: RomParser, song: NspcSequence.Song): ShortArray? {
        val track = selectedTrack ?: run {
            soundEditorLog.warn { "[SPC-PIANO] renderEditedSong: no track selected" }
            return null
        }
        if (!NativeSpcEmulator.isAvailable()) {
            soundEditorLog.warn { "[SPC-PIANO] Native emulator not available, will fall back to software renderer" }
            return null
        }

        val bankProject = projectForBank(song)
        if (bankProject != null) {
            if (instrumentBankProblem != null) return null
            return withContext(Dispatchers.Default) {
                try {
                    val bank = MusicBankResolver.resolve(romParser, bankProject, track.songSet)
                    NativeSpcEmulator().use { emulator ->
                        emulator.loadFromRam(bank.ram, emptyList(), track.playIndex, playbackWarmupSeconds = 0)
                        emulator.renderMono(PianoRollPlaybackTiming.previewSeconds(song))
                    }
                } catch (error: Exception) {
                    soundEditorLog.warn(error) { "[SPC] Shared bank cannot render natively: ${error.message}" }
                    null
                }
            }
        }

        val importedEdit = pianoRollNativeSource?.let { currentNativeImportEdit(song) }
        val instrumentWrites = pianoRollInstrumentPatchWrites()
        val noteDeltaPlan = PianoRollPreviewLogic.deltaOverlayPlan(song, originalPianoRollSong)
        val hasNoteDelta = noteDeltaPlan?.hasDelta ?: song.isModified
        val hasRenderableEdits = hasNoteDelta || instrumentWrites.isNotEmpty()
        val previewSeconds = PianoRollPlaybackTiming.previewSeconds(song)
        soundEditorLog.info {
            "[SPC-PIANO] renderEditedSong: track='${track.name}', songSet=0x${track.songSet.toString(16)}, " +
                "playIndex=${track.playIndex}, modified=${song.isModified}, noteDelta=$hasNoteDelta, " +
                "instrumentEdits=${instrumentWrites.size}, notes=${song.channels.sumOf { it.notes.size }}, " +
                "ticks=${song.totalTicks}, previewSeconds=$previewSeconds"
        }

        return withContext(Dispatchers.Default) {
            try {
                val baseRam = SpcData.buildInitialSpcRam(romParser)
                val songBlocks = SpcData.findSongSetTransferData(romParser, track.songSet)
                soundEditorLog.info { "[SPC-PIANO] SPC RAM built: ${songBlocks.size} song transfer blocks" }
                if (importedEdit != null) {
                    soundEditorLog.info { "[SPC-PIANO] Rendering native payload import via SPC emulator" }
                    val resolved = ImportedMusicResolver.resolve(romParser, importedEdit)
                    NativeSpcEmulator().use { emu ->
                        emu.loadFromRam(resolved.ram, emptyList(), track.playIndex, playbackWarmupSeconds = 0)
                        val mono = emu.renderMono(previewSeconds)
                        val peak = if (mono.isNotEmpty()) mono.maxOf { kotlin.math.abs(it.toInt()) } else 0
                        soundEditorLog.info {
                            "[SPC-PIANO] Native payload render OK: ${mono.size} samples (${mono.size / 32000}s), peak=$peak"
                        }
                        mono // A quiet or muted imported envelope is valid native output.
                    }
                } else if (!hasRenderableEdits) {
                    soundEditorLog.info { "[SPC-PIANO] Rendering ORIGINAL track (unmodified) via native SPC emulator" }
                    NativeSpcEmulator().use { emu ->
                        emu.loadFromRam(baseRam, songBlocks, track.playIndex)
                        val mono = emu.renderMono(previewSeconds)
                        val peak = if (mono.isNotEmpty()) mono.maxOf { kotlin.math.abs(it.toInt()) } else 0
                        soundEditorLog.info { "[SPC-PIANO] Native render OK: ${mono.size} samples (${mono.size / 32000}s), peak=$peak" }
                        mono
                    }
                } else {
                    soundEditorLog.info { "[SPC-PIANO] Rendering MODIFIED track via native SPC emulator" }
                    val spcRam = baseRam.copyOf()
                    SpcData.applyTransferBlocks(spcRam, songBlocks)

                    // Log original conductor location
                    val origConductor = (spcRam[0x581E + track.playIndex * 2].toInt() and 0xFF) or
                        ((spcRam[0x581E + track.playIndex * 2 + 1].toInt() and 0xFF) shl 8)
                    soundEditorLog.info { "[SPC-PIANO] Original conductor for playIndex ${track.playIndex} at 0x${origConductor.toString(16)}" }

                    val relocatedSequence = if (hasNoteDelta) {
                        relocatedSequencePatchForTrack(
                            romParser = romParser,
                            songBlocks = songBlocks,
                            spcRam = spcRam,
                            track = track,
                            song = song
                        ).also { patch ->
                            soundEditorLog.info {
                                "[SPC-PIANO] Relocated preview sequence: " +
                                    "0x${patch.allocation.start.toString(16)}.." +
                                    "0x${patch.allocation.endExclusive.toString(16)}, " +
                                    "${patch.fit.encodedBytes}/${patch.fit.budgetBytes} bytes"
                            }
                            if (patch.fit.trimmed) {
                                soundEditorLog.warn {
                                    "[SPC-PIANO] Preview tail-trimmed ${patch.fit.removedNotes} notes and " +
                                        "${patch.fit.removedCommands} commands after tick ${patch.fit.cutoffTick}"
                                }
                            }
                        }
                    } else {
                        null
                    }
                    val sequenceWrites = relocatedSequence?.writes ?: emptyMap()
                    val writes = sequenceWrites + instrumentWrites
                    val totalBytes = writes.values.sumOf { it.size }
                    soundEditorLog.info { "[SPC-PIANO] Encoded ${writes.size} writes, $totalBytes bytes total:" }
                    for ((addr, data) in writes) {
                        val preview = if (data.size <= 24) " = [${data.joinToString(",") { "%02x".format(it.toInt() and 0xFF) }}]" else ""
                        soundEditorLog.info { "[SPC-PIANO]   0x${addr.toString(16).padStart(4, '0')}: ${data.size} bytes$preview" }
                    }

                    val patchBlocks = writes.map { (addr, data) ->
                        SpcData.TransferBlock(addr, data)
                    }

                    soundEditorLog.info { "[SPC-PIANO] Loading native SPC: ${songBlocks.size} song blocks + ${patchBlocks.size} patch blocks" }
                    NativeSpcEmulator().use { emu ->
                        emu.loadFromRam(baseRam, songBlocks + patchBlocks, track.playIndex)
                        val mono = emu.renderMono(previewSeconds)
                        val peak = if (mono.isNotEmpty()) mono.maxOf { kotlin.math.abs(it.toInt()) } else 0
                        soundEditorLog.info { "[SPC-PIANO] Native render OK: ${mono.size} samples (${mono.size / 32000}s), peak=$peak" }
                        if (peak < 200) {
                            soundEditorLog.warn { "[SPC-PIANO] Modified native render was silent; using software preview renderer" }
                            null
                        } else {
                            mono
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                val message = e.message ?: ""
                if (message.contains("overflow into instrument table")) {
                    soundEditorLog.warn { "[SPC-PIANO] Native re-encode cannot fit edited sequence; using fallback preview: $message" }
                } else {
                    soundEditorLog.error(e) { "[SPC-PIANO] Native render FAILED: ${e.message}" }
                }
                null
            } catch (e: Exception) {
                soundEditorLog.error(e) { "[SPC-PIANO] Native render FAILED: ${e.message}" }
                null
            }
        }
    }

    fun buildSpcRamForTrack(romParser: RomParser, songSet: Int): ByteArray {
        val project = projectForBank()
        if (project != null && instrumentBankProblem == null) {
            return MusicBankResolver.sourceRam(romParser, project, songSet)
        }
        val baseRam = SpcData.buildInitialSpcRam(romParser)
        val blocks = SpcData.findSongSetTransferData(romParser, songSet)
        SpcData.applyTransferBlocks(baseRam, blocks)
        return baseRam
    }

    private fun rebuildInstrumentMetadata(
        romParser: RomParser,
        instruments: List<NspcRenderer.InstrumentEntry>
    ): List<NspcRenderer.InstrumentEntry> {
        val track = selectedTrack ?: return instruments
        val ram = pianoRollNativeSource?.let {
            ImportedMusicResolver.sourceRam(romParser, track.songSet, track.playIndex, it).also { ram ->
                PianoRollPreviewLogic.writeInstrumentEntries(ram, instruments)
            }
        } ?: buildSpcRamForTrack(romParser, track.songSet).also {
            PianoRollPreviewLogic.writeInstrumentEntries(it, instruments)
        }
        return NspcRenderer.readInstrumentTable(ram)
    }

    private fun pianoRollInstrumentPatchWrites(): Map<Int, ByteArray> {
        return PianoRollPreviewLogic.instrumentPatchWrites(
            pianoRollInstruments,
            originalPianoRollInstruments
        )
    }

    /** Native single-note audition; its private RAM and player never replace the song or waveform. */
    suspend fun previewInstrument(romParser: RomParser, index: Int, note: NspcSequence.Note, sampleSource: Int? = null,
        original: Boolean = false, held: Boolean = false) {
        require(!original || sampleSource == null) { "Original audition uses the source bank's sample" }
        require(note.noteValue in NspcSequence.NOTE_MIN..NspcSequence.NOTE_MAX) { "Choose an audition pitch from C1 to B6" }
        val track = selectedTrack ?: return
        val current = pianoRollInstruments.firstOrNull { it.index == index } ?: return
        if (sampleSource != null && (loadedSampleVersion != pianoRollSampleVersion || pianoRollSamples.none { it.srcn == sampleSource })) {
            stopInstrumentPreview()
            instrumentPreviewMessage = "Source is not available in this bank"
            return
        }
        val instrument = if (sampleSource == null) current else current.copy(srcn = sampleSource)
        if (!nativeInstrumentPreviewAvailable) return
        stopTrackPlayback()
        playAllEnabled = false
        if (held) instrumentPlayer.stop()
        val version = ++instrumentPreviewVersion
        val previewNote = note.copy(instrument = index)
        val tempo = editingSong?.tempo ?: InstrumentAudition.TEMPO
        val instruments = pianoRollInstruments.toList()
        val payload = MusicEditConversion.copyNativePayload(pianoRollNativeSource)
        val bankProject = projectForBank()
        if (instrumentBankProblem != null) {
            stopInstrumentPreview()
            instrumentPreviewMessage = instrumentBankProblem.orEmpty()
            return
        }
        instrumentPreviewIndex = index
        instrumentPreviewSample = sampleSource
        instrumentAuditionOriginal = original
        instrumentPreviewRequest = InstrumentPreviewRequest(romParser, index, note.copy(), sampleSource, selectedTrackId, original, held)
        val loop = liveInstrumentPreview
        isInstrumentPreviewLoading = true
        instrumentPreviewMessage = "Preparing preview…"
        try {
            val preview = withContext(Dispatchers.Default) {
                val ram = if (bankProject != null) MusicBankResolver.sourceRam(romParser, bankProject, track.songSet, includeOverrides = !original) else payload?.let {
                    ImportedMusicResolver.sourceRam(romParser, track.songSet, track.playIndex, it)
                } ?: buildSpcRamForTrack(romParser, track.songSet).also {
                    if (!original) PianoRollPreviewLogic.writeInstrumentEntries(it, instruments)
                }
                val audition = if (original) NspcRenderer.readInstrumentTable(ram).firstOrNull { it.index == index }
                    ?.takeIf(InstrumentDescriptions::playable) ?: error("Original sound is unavailable in this bank") else instrument
                InstrumentAudition.build(ram, audition, previewNote, tempo, held)
            }
            if (version != instrumentPreviewVersion) return
            val label = if (original) "Original · ${previewNote.name}" else if (sampleSource == null) previewNote.name else "${if (sampleSource >= 0x80) "noise rate ${sampleSource and 31}" else "sample #$sampleSource"} · ${previewNote.name}"
            if (held) {
                instrumentPlayer.stop()
                heldInstrumentPlayer.play(preview) {
                    if (version == instrumentPreviewVersion) {
                        isInstrumentPreviewLoading = false
                        instrumentPreviewMessage = "Holding · $label"
                    }
                }
                if (version == instrumentPreviewVersion) stopInstrumentPreview()
                return
            }
            val pcm = withContext(Dispatchers.Default) { InstrumentAudition.render(preview) }
            if (version != instrumentPreviewVersion) return
            heldInstrumentPlayer.stop()
            instrumentPreviewMessage = "Playing · $label"
            instrumentPlayer.onComplete = {
                if (version == instrumentPreviewVersion) {
                    instrumentPreviewIndex = null
                    instrumentPreviewSample = null
                    instrumentPreviewRequest = null
                    instrumentPreviewMessage = "Preview finished · $label"
                }
            }
            // Fixed monitor gain: envelope and gain changes must stay audible when comparing sounds.
            when (instrumentPlayer.play(pcm, NativeSpcEmulator.SAMPLE_RATE, loop = loop)) {
                SoundPlayer.PlaybackStart.STARTED -> Unit
                SoundPlayer.PlaybackStart.EMPTY -> {
                    instrumentPreviewIndex = null
                    instrumentPreviewSample = null
                    instrumentPreviewRequest = null
                    instrumentPreviewMessage = "No audio samples to play"
                }
                SoundPlayer.PlaybackStart.OUTPUT_UNAVAILABLE -> {
                    instrumentPreviewIndex = null
                    instrumentPreviewSample = null
                    instrumentPreviewRequest = null
                    instrumentPreviewMessage = "Could not open audio output"
                }
            }
        } catch (cancelled: CancellationException) {
            if (version == instrumentPreviewVersion) stopInstrumentPreview()
            throw cancelled
        } catch (error: Exception) {
            if (version == instrumentPreviewVersion) {
                stopInstrumentPreview()
                instrumentPreviewMessage = "Could not preview this instrument"
            }
            soundEditorLog.warn(error) { "[SPC-INSTRUMENT] Native audition failed for $index" }
        } finally {
            if (version == instrumentPreviewVersion) isInstrumentPreviewLoading = false
        }
    }

    fun stopInstrumentPreview() {
        instrumentPreviewVersion++
        instrumentPreviewRequest = null
        instrumentPlayer.onComplete = null
        instrumentPlayer.stop()
        heldInstrumentPlayer.stop()
        instrumentPreviewIndex = null
        instrumentPreviewSample = null
        isInstrumentPreviewLoading = false
        instrumentPreviewMessage = ""
    }

    internal fun releaseInstrumentKey(index: Int, pitch: Int) {
        val request = instrumentPreviewRequest ?: return
        if (!request.held || request.index != index || request.note.noteValue != pitch) return
        instrumentPreviewVersion++
        instrumentPreviewRequest = null
        instrumentPreviewIndex = null
        instrumentPreviewSample = null
        isInstrumentPreviewLoading = false
        instrumentPreviewMessage = "Key released"
        heldInstrumentPlayer.release()
    }

    fun setInstrumentPreviewLive(enabled: Boolean) {
        if (enabled == liveInstrumentPreview) return
        stopInstrumentPreview()
        liveInstrumentPreview = enabled
    }

    fun compareOriginalInstrument(original: Boolean) {
        if (original == instrumentAuditionOriginal) return
        instrumentAuditionOriginal = original
        val request = instrumentPreviewRequest ?: return
        instrumentPreviewRequest = request.copy(original = original, sampleSource = null)
        instrumentPreviewSample = null
        instrumentPreviewVersion++
        isInstrumentPreviewLoading = true
        instrumentPreviewRefreshVersion++
    }

    private fun invalidateInstrumentPreview(refreshOriginal: Boolean = false) {
        val request = instrumentPreviewRequest ?: return
        if ((!liveInstrumentPreview && !request.held) || request.original && !refreshOriginal) return
        instrumentPreviewVersion++
        isInstrumentPreviewLoading = true
        instrumentPreviewRefreshVersion++
    }

    internal suspend fun refreshInstrumentPreview() {
        val request = instrumentPreviewRequest ?: return
        if (request.trackId != selectedTrackId || !isPianoRollOpen) return
        previewInstrument(request.parser, request.index, request.note, request.sampleSource, request.original, request.held)
    }

    private fun relocatedSequencePatchForTrack(
        romParser: RomParser,
        songBlocks: List<SpcData.TransferBlock>,
        spcRam: ByteArray,
        track: SpcData.TrackInfo,
        song: NspcSequence.Song
    ): MusicSequenceBudget.RelocatedSequencePatch {
        val baseBlocks = SpcData.parseTransferBlocks(romParser.getRomData(), romParser.snesToPc(0xCF8000))
        val knownPlayIndexes = SpcData.KNOWN_TRACKS
            .filter { it.songSet == track.songSet }
            .mapTo(mutableSetOf()) { it.playIndex }
        knownPlayIndexes += track.playIndex
        val protectedPlayIndexes = knownPlayIndexes - track.playIndex
        val occupied = MusicSequenceBudget.buildProtectedSpcOccupancy(
            baseBlocks = baseBlocks,
            songBlocks = songBlocks,
            spcRam = spcRam,
            protectedPlayIndexes = protectedPlayIndexes
        )
        return MusicSequenceBudget.encodeRelocated(
            song = song,
            playIndex = track.playIndex,
            spcRam = spcRam,
            occupiedRanges = occupied,
            key = MusicEditConversion.key(track.songSet, track.playIndex)
        )
    }

    private fun hexByte(value: Int): String =
        "0x" + value.coerceIn(0, 255).toString(16).uppercase().padStart(2, '0')

    private fun hexWord(value: Int): String =
        "0x" + value.coerceIn(0, 0xFFFF).toString(16).uppercase().padStart(4, '0')

    private fun renderOriginalNativeForHybrid(
        romParser: RomParser,
        track: SpcData.TrackInfo,
        seconds: Int
    ): ShortArray? {
        return try {
            val baseRam = SpcData.buildInitialSpcRam(romParser)
            val songBlocks = SpcData.findSongSetTransferData(romParser, track.songSet)
            val instrumentBlocks = pianoRollInstrumentPatchWrites().map { (addr, data) ->
                SpcData.TransferBlock(addr, data)
            }
            NativeSpcEmulator().use { emu ->
                emu.loadFromRam(baseRam, songBlocks + instrumentBlocks, track.playIndex)
                emu.renderMono(seconds)
            }
        } catch (e: Exception) {
            soundEditorLog.warn(e) { "[SPC-PIANO] Hybrid native base render failed: ${e.message}" }
            null
        }
    }

    private var spcRam: ByteArray? = null
    private var romParserRef: RomParser? = null
    private var editorStateRef: EditorState? = null
    private var lastLoadedSongSet = -1
    private var lastLoadedMusicEditVersion = -1
    private var loadVersion = 0

    data class DecodedSample(
        val dirEntry: SpcData.SampleDirEntry,
        val pcmData: ShortArray,
        val loopStart: Int
    )

    fun selectTrack(track: SpcData.TrackInfo) {
        instrumentAuditionOriginal = false
        stopInstrumentPreview()
        if (track.songSet != sampleCatalogSongSet) {
            sampleCatalogRam = null
            sampleCatalogSongSet = -1
            sampleCatalogBankBlocks = emptyList()
            sampleCatalogImports = emptyList()
            pianoRollSampleVersion++
            pianoRollSamples = emptyList()
            pianoRollSamplesProblem = null
            isLoadingPianoRollSamples = false
        }
        val shouldAutoPlay = isPlaying || player.isActive() || autoPlayEnabled
        player.stop()
        isPlaying = false
        playbackPosition = 0f
        pianoRollPlaybackTick = -1

        val clearSamples = track.songSet != lastLoadedSongSet
        selectedTrackId = track.id
        selectedTrack = track
        currentSongSet = track.songSet
        selectedSample = null
        selectedSampleIndex = -1
        currentWaveform = null
        currentLoopStart = -1
        isPianoRollOpen = false
        editingSong = null
        pendingTrackImport = null
        clearNativeSource()
        instrumentBankProblem = null
        statusMessage = "Track: ${track.name}"
        autoPlayEnabled = shouldAutoPlay
        if (clearSamples) {
            trackSamples = emptyList()
            trackUniqueSamples = emptyList()
        }
    }

    suspend fun selectSample(sample: DecodedSample) {
        stopInstrumentPreview()
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
                soundEditorLog.info { "[SPC] Sample directory: ${dir.size} entries" }
                val decoded = dir.mapNotNull { entry ->
                    try {
                        val (pcm, loop) = SpcData.decodeBrrWithLoop(ram, entry)
                        if (pcm.isNotEmpty()) DecodedSample(entry, pcm, loop) else null
                    } catch (e: Exception) {
                        soundEditorLog.warn(e) { "[SPC]   Failed to decode #${entry.index}: ${e.message}" }
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
            soundEditorLog.error(e) { "[SPC] Load failed: ${e.message}" }
        } finally {
            isLoadingSamples = false
        }
    }

    suspend fun loadTrackSamples(romParser: RomParser, editorState: EditorState? = null) {
        val track = selectedTrack ?: return
        val version = ++loadVersion
        romParserRef = romParser
        editorStateRef = editorState

        val musicVersion = editorState?.musicEditVersion ?: 0
        if (track.songSet == lastLoadedSongSet && trackSamples.isNotEmpty() && lastLoadedMusicEditVersion == musicVersion) {
            buildCompositeWaveform(track, version)
            return
        }
        isLoadingTrack = true
        statusMessage = "Loading instruments for ${track.name}..."

        try {
            val result = withContext(Dispatchers.Default) {
                val baseRam = spcRam ?: SpcData.buildInitialSpcRam(romParser)
                val ram = editorState?.project?.let { MusicBankResolver.sourceRam(romParser, it, track.songSet) } ?: baseRam.copyOf()

                val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
                soundEditorLog.info { "[SPC] Track '${track.name}' songSet=0x${track.songSet.toString(16)}: ${blocks.size} transfer blocks" }

                val baseDir = SpcData.findSampleDirectory(baseRam)
                val baseDirMap = baseDir.associate { it.index to it }

                if (editorState == null && blocks.isNotEmpty()) {
                    SpcData.applyTransferBlocks(ram, blocks)
                }
                val dir = SpcData.findSampleDirectory(ram)

                val importedBlocks = editorState?.project?.musicEdits?.values.orEmpty()
                    .filter { it.songSet == track.songSet }.flatMap { it.nativePayload?.let(MusicEditConversion::toTransferBlocks).orEmpty() }
                val modifiedRanges = (blocks + importedBlocks).map { it.destAddr until (it.destAddr + it.data.size) }

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

                soundEditorLog.info { "[SPC] Directory: ${dir.size} entries, ${allDecoded.size} decoded, ${uniqueDecoded.size} unique to song set" }
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
            lastLoadedMusicEditVersion = musicVersion
            trackSamples = allSamples
            trackUniqueSamples = uniqueSamples
            statusMessage = "${uniqueSamples.size} unique / ${allSamples.size} total instruments ($blockCount blocks) for ${track.name}"
            buildCompositeWaveform(track, version)
        } catch (e: Exception) {
            if (version == loadVersion) {
                statusMessage = "Error loading track: ${e.message}"
            }
            soundEditorLog.error(e) { "[SPC] Track load error: ${e.message}" }
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
            val savedEdit = editorStateRef?.getMusicEdit(track.songSet, track.playIndex)

            val bankProject = editorStateRef?.project
            val sharedEdited = bankProject != null && (savedEdit != null ||
                bankProject.musicInstrumentBanks[MusicInstrumentBanks.key(track.songSet)]?.let { it.instruments.isNotEmpty() || it.samples.isNotEmpty() } == true ||
                bankProject.musicEdits.values.any { it.songSet == track.songSet && it.nativePayload != null })
            if (sharedEdited) {
                return@withContext renderSharedBankPreview(parser, track, requireNotNull(bankProject), rate)
            }
            if (ram != null) {
                val nativeResult = tryNativeSpcRender(ram, parser, track, rate)
                if (nativeResult != null && nativeResult.size > rate / 2) {
                    val peak = nativeResult.maxOf { abs(it.toInt()) }
                    if (peak > 200) {
                        soundEditorLog.info { "[SPC-NATIVE] Rendered ${track.name}: ${nativeResult.size} samples, peak=$peak" }
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
                        soundEditorLog.info { "[NSPC] Rendered ${track.name}: ${rendered.size} samples (${rendered.size * 1000L / rate}ms), peak=$renderPeak" }
                        return@withContext rendered
                    }
                    soundEditorLog.warn { "[NSPC] Render insufficient (${rendered.size} samples, peak=$renderPeak), falling back" }
                } catch (e: Exception) {
                    soundEditorLog.warn(e) { "[NSPC] Render failed for ${track.name}: ${e.message}, falling back" }
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
        val editedLabel = if (editorStateRef?.hasMusicEdit(track.songSet, track.playIndex) == true) " [edited]" else ""
        statusMessage = "${track.name}$editedLabel: ${trimmed.size * 1000L / rate}ms"

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
                val nativeSr = NativeSpcEmulator.SAMPLE_RATE
                val mono = trimNativeTrackPreview(emu.renderMono(120), nativeSr)
                if (sampleRate != nativeSr && mono.isNotEmpty()) resampleLinear(mono, nativeSr, sampleRate)
                else mono
            }
        } catch (e: Exception) {
            soundEditorLog.warn(e) { "[SPC-JNA] Native render failed for ${track.name}: ${e.message}" }
            null
        }
    }

    private fun renderSharedBankPreview(parser: RomParser, track: SpcData.TrackInfo, project: SmEditProject, rate: Int): ShortArray {
        val bank = try {
            MusicBankResolver.resolve(parser, project, track.songSet)
        } catch (error: Exception) {
            soundEditorLog.warn(error) { "[SPC] Bank allocation failed; using its samples for approximate preview" }
            null
        }
        if (bank != null && NativeSpcEmulator.isAvailable()) {
            try {
                NativeSpcEmulator().use { emulator ->
                    emulator.loadFromRam(bank.ram, emptyList(), track.playIndex, playbackWarmupSeconds = 0)
                    return resampleLinear(trimNativeTrackPreview(emulator.renderMono(120), NativeSpcEmulator.SAMPLE_RATE), NativeSpcEmulator.SAMPLE_RATE, rate)
                }
            } catch (error: Exception) {
                soundEditorLog.warn(error) { "[SPC] Shared bank native preview failed" }
            }
        }
        // Invalid or overlapping source data must not substitute a different bank or stock song.
        val ram = bank?.ram ?: MusicBankResolver.sourceRam(parser, project, track.songSet)
        val song = project.musicEdits[MusicEditConversion.key(track.songSet, track.playIndex)]?.let { MusicEditConversion.toSong(it) }
            ?: NspcSequence.parse(ram, track.playIndex)
        val events = song.channels.flatMapIndexed { ch, channel -> channel.notes.map { PianoRollPreviewLogic.noteEvent(ch, it) } }
        return NspcRenderer.renderToWav(events, ram, tempo = song.tempo, sampleRate = rate, maxSeconds = 120.0)
    }

    private fun renderVoiceClip(
        baseRam: ByteArray?,
        romParser: RomParser,
        track: SpcData.TrackInfo,
        @Suppress("UNUSED_PARAMETER") rate: Int
    ): ShortArray {
        val ram = (baseRam ?: SpcData.buildInitialSpcRam(romParser)).copyOf()
        val blocks = SpcData.findSongSetTransferData(romParser, track.songSet)
        soundEditorLog.info { "[VOICE] ${track.name}: songSet=0x${track.songSet.toString(16)}, ${blocks.size} transfer blocks" }
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
                    soundEditorLog.info { "[VOICE]   candidate #${entry.index}: ${pcm.size} pcm @ 0x${entry.startAddr.toString(16)}" }
                }
            } catch (_: Exception) {}
        }

        if (candidates.isEmpty()) {
            soundEditorLog.warn { "[VOICE] No voice sample candidates found, trying all unique samples" }
            return ShortArray(0)
        }

        val (_, voicePcm) = candidates.maxBy { it.second.size }
        soundEditorLog.info { "[VOICE] Selected sample with ${voicePcm.size} pcm samples" }

        val peak = voicePcm.maxOf { abs(it.toInt()) }.coerceAtLeast(1)
        val gain = 26000.0 / peak
        return ShortArray(voicePcm.size) { (voicePcm[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }
    }


    fun playSample(sample: DecodedSample, loop: Boolean = false) {
        stopInstrumentPreview()
        if (sample.pcmData.isEmpty()) { statusMessage = "Empty sample"; return }
        val extended = extendWithLoop(sample.pcmData, sample.loopStart, sampleRate, 4.0)
        val pcm = normalizePreviewPcm(extended)
        statusMessage = "Playing sample #${sample.dirEntry.index}..."
        player.onComplete = { isPlaying = false; playbackPosition = 0f }
        player.play(pcm, sampleRate, loop)
        isPlaying = true
    }

    fun playWaveform(pcm: ShortArray) {
        stopInstrumentPreview()
        if (pcm.isEmpty()) { statusMessage = "Empty waveform"; return }
        player.onComplete = { isPlaying = false; playbackPosition = 0f }
        player.play(pcm, sampleRate)
        isPlaying = true
        statusMessage = "Playing..."
    }

    fun playTrackPreview(startFraction: Float = 0f) {
        stopInstrumentPreview()
        val waveform = currentWaveform
        if (waveform == null || waveform.isEmpty()) {
            statusMessage = "No waveform loaded"
            return
        }
        val preview = normalizePreviewPcm(waveform)
        val startFrame = (startFraction * preview.size).toLong().coerceIn(0, preview.size.toLong() - 1)
        val loopStr = if (loopEnabled) " (loop)" else ""
        statusMessage = "Playing ${selectedTrack?.name ?: "preview"}$loopStr..."
        player.onComplete = {
            isPlaying = false
            playbackPosition = 0f
            if (playAllEnabled) advanceToNextTrack()
        }
        player.play(preview, sampleRate, loop = loopEnabled, startFrame = startFrame)
        isPlaying = true
    }

    fun stopPlayback() {
        stopInstrumentPreview()
        stopTrackPlayback()
    }

    private fun stopTrackPlayback() {
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
            soundEditorLog.info { "[SPC] Exported WAV: ${file.absolutePath} (${waveform.size} samples, ${sampleRate}Hz)" }
        } catch (e: Exception) {
            statusMessage = "Export failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC] WAV export error: ${e.message}" }
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
            soundEditorLog.error(e) { "[SPC] WAV import error: ${e.message}" }
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
            soundEditorLog.info { "[SPC] Exported BRR: ${file.absolutePath} (${brr.size} bytes)" }

            // Load the imported WAV as current waveform for preview
            sampleRate = imported.sourceRate
            currentWaveform = imported.pcm
            currentLoopStart = -1
        } catch (e: Exception) {
            statusMessage = "BRR export failed: ${e.message}"
            soundEditorLog.error(e) { "[SPC] BRR export error: ${e.message}" }
        }
    }

    fun importWavAsPreview() {
        val imported = importWav() ?: return
        loadVersion++ // cancel any in-flight track render
        sampleRate = imported.sourceRate
        currentWaveform = imported.pcm
        currentLoopStart = -1
    }

}
