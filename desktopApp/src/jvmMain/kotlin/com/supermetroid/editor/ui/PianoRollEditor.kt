package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence.Song
import com.supermetroid.editor.rom.NspcSequence.Note
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.math.roundToInt

private val pianoRollLog = KotlinLogging.logger {}

// Channel colors (one per voice)
private val CHANNEL_COLORS = arrayOf(
    Color(0xFFE05555), // Ch 0 - red
    Color(0xFF55A5E0), // Ch 1 - blue
    Color(0xFF55E055), // Ch 2 - green
    Color(0xFFE0E055), // Ch 3 - yellow
    Color(0xFFE055E0), // Ch 4 - magenta
    Color(0xFF55E0E0), // Ch 5 - cyan
    Color(0xFFE09555), // Ch 6 - orange
    Color(0xFFA055E0), // Ch 7 - purple
)

// Brighter versions for "playing" notes
private val CHANNEL_COLORS_BRIGHT = arrayOf(
    Color(0xFFFF7777),
    Color(0xFF77CCFF),
    Color(0xFF77FF77),
    Color(0xFFFFFF77),
    Color(0xFFFF77FF),
    Color(0xFF77FFFF),
    Color(0xFFFFBB77),
    Color(0xFFCC77FF),
)

private val PIANO_KEY_BG = Color(0xFF1A1A2E)
private val GRID_LINE = Color(0xFF2A2A3A)
private val GRID_BEAT = Color(0xFF3A3A4A)
private val BLACK_KEY_BG = Color(0xFF151525)
private val PLAYBACK_CURSOR = Color(0xFFFFFFFF)

// Zoom levels: index 0 = most zoomed out, higher = more zoomed in
private val ZOOM_LEVELS = floatArrayOf(2.0f, 1.0f, 0.5f, 0.25f)
private val ZOOM_LABELS = arrayOf("1x", "2x", "4x", "8x")
private const val BASE_NOTE_HEIGHT = 20f  // base note height at zoom index 0
private const val NOTE_RESIZE_HANDLE_PX = 7f
private const val NOTE_TICK_STEP = 12

private enum class PianoRollTool { Select, Paint, Erase }
private enum class PianoRollDragMode { Move, ResizeEnd }

private data class PianoRollSelectionBox(
    val startWorldX: Float,
    val startY: Float,
    val currentWorldX: Float,
    val currentY: Float
)

private data class PianoRollDragState(
    val channel: Int,
    val note: Note,
    val mode: PianoRollDragMode,
    val startWorldX: Float,
    val startY: Float,
    val startTick: Int,
    val startDuration: Int,
    val startNoteValue: Int,
    val currentTick: Int,
    val currentDuration: Int,
    val currentNoteValue: Int,
    val changed: Boolean = false
)

private fun quantizeTick(tick: Int): Int =
    ((tick + NOTE_TICK_STEP / 2) / NOTE_TICK_STEP) * NOTE_TICK_STEP

private fun quantizeTickDelta(delta: Int): Int =
    (delta.toFloat() / NOTE_TICK_STEP).roundToInt() * NOTE_TICK_STEP

private fun noteSummary(
    channel: Int,
    tick: Int,
    duration: Int,
    noteValue: Int,
    velocity: Int,
    quantize: Int,
    instrument: Int
): String {
    val inst = instrument.toString(16).uppercase().padStart(2, '0')
    return "ch=${channel + 1} ${NspcSequence.noteToName(noteValue)} tick=$tick len=$duration vel=$velocity q=$quantize inst=0x$inst"
}

private fun noteSummary(channel: Int, note: Note): String =
    noteSummary(channel, note.tick, note.duration, note.noteValue, note.velocity, note.quantize, note.instrument)

private fun logPianoEdit(action: String, channel: Int, note: Note, extra: String = "") {
    val suffix = if (extra.isBlank()) "" else " $extra"
    pianoRollLog.info { "[SPC-PIANO-EDIT] $action ${noteSummary(channel, note)}$suffix" }
}

private fun hex(value: Int, digits: Int = 2): String =
    "0x" + value.coerceAtLeast(0).toString(16).uppercase().padStart(digits, '0')

private fun hexOrUnknown(value: Int, digits: Int = 4): String =
    if (value >= 0) hex(value, digits) else "unknown"

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PianoRollEditor(
    song: Song,
    activeChannel: Int,
    instrumentInfos: List<NspcRenderer.InstrumentEntry> = emptyList(),
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    exportBudgetBytes: Int = 0,
    relocationBudgetBytes: Int = 0,
    originalSequenceBytes: Int = 0,
    originalFlattenedBytes: Int = 0,
    onRecordUndo: (String) -> Unit = {},
    onUndo: () -> Boolean = { false },
    onRedo: () -> Boolean = { false },
    onInstrumentChanged: (NspcRenderer.InstrumentEntry) -> Unit = {},
    onActiveChannelChanged: (Int) -> Unit,
    onSongChanged: (Song) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    onSeek: (Int) -> Unit = {},
    isPlaying: Boolean,
    playbackTick: Int = -1,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }

    // View state
    var zoomIndex by remember { mutableStateOf(2) } // start at 4x
    val pianoKeyWidth = 44f
    val totalPitches = 72  // C1-B6
    val totalTicks = (song.totalTicks + 200).coerceAtLeast(960)
    val effectiveZoomIndex = zoomIndex.coerceIn(0, ZOOM_LEVELS.lastIndex)
    val ticksPerPixel = ZOOM_LEVELS[effectiveZoomIndex]
    val noteHeight = BASE_NOTE_HEIGHT + effectiveZoomIndex * 4f  // taller at higher zoom
    val worldWidth = (totalTicks / ticksPerPixel).toInt()

    var viewportWidth by remember { mutableStateOf(0) }
    var hScrollPx by remember(song) { mutableStateOf(0f) }
    val maxHScroll = (worldWidth - viewportWidth).coerceAtLeast(0).toFloat()

    // Editing state
    var editVersion by remember { mutableStateOf(0) }
    var activeTool by remember { mutableStateOf(PianoRollTool.Paint) }
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var selectedNotes by remember { mutableStateOf<List<PianoRollEditLogic.NoteRef>>(emptyList()) }
    var selectedChannel by remember { mutableStateOf(activeChannel) }
    var showAllChannels by remember { mutableStateOf(true) }
    var showNoteProperties by remember { mutableStateOf(false) }
    var dragState by remember { mutableStateOf<PianoRollDragState?>(null) }
    var selectionBox by remember { mutableStateOf<PianoRollSelectionBox?>(null) }
    var clipboardNotes by remember { mutableStateOf<List<PianoRollEditLogic.ClipboardNote>>(emptyList()) }
    val flattenedBytes = remember(song, editVersion) { NspcSequence.encodedSequenceSize(song) }
    val originalFlatBytes = originalFlattenedBytes.takeIf { it > 0 } ?: flattenedBytes
    val trackUsageBytes = (originalSequenceBytes + flattenedBytes - originalFlatBytes).coerceAtLeast(0)
    val budgetFit = remember(song, editVersion, relocationBudgetBytes, flattenedBytes) {
        if (song.isModified && relocationBudgetBytes > 0 && flattenedBytes > relocationBudgetBytes) {
            runCatching { MusicSequenceBudget.fitSongToEncodedBudget(song, relocationBudgetBytes) }.getOrNull()
        } else {
            null
        }
    }

    fun scrollTo(x: Float) {
        hScrollPx = x.coerceIn(0f, maxHScroll)
    }

    fun mutateSong(markModified: Boolean = true, historyLabel: String? = "Edit", mutation: () -> Unit) {
        if (markModified && historyLabel != null) {
            onRecordUndo(historyLabel)
        }
        mutation()
        if (markModified) {
            song.isModified = true
        }
        editVersion++
        onSongChanged(song)
    }

    fun selectedRefs(): List<PianoRollEditLogic.NoteRef> =
        PianoRollEditLogic.sanitizeSelection(song, selectedNotes)

    fun setSelection(refs: List<PianoRollEditLogic.NoteRef>, openProperties: Boolean = false) {
        val clean = PianoRollEditLogic.sanitizeSelection(song, refs)
        selectedNotes = clean
        val primary = clean.lastOrNull()
        selectedChannel = primary?.channel ?: activeChannel
        selectedNote = primary?.note
        if (primary != null && primary.channel != activeChannel) onActiveChannelChanged(primary.channel)
        showNoteProperties = openProperties && clean.size == 1
    }

    fun selectNote(channel: Int, note: Note, openProperties: Boolean = false) {
        setSelection(listOf(PianoRollEditLogic.NoteRef(channel, note)), openProperties)
    }

    fun clearSelection() {
        selectedNotes = emptyList()
        selectedNote = null
        showNoteProperties = false
        dragState = null
        selectionBox = null
    }

    fun deleteSelection(source: String): Boolean {
        val refs = selectedRefs()
        if (refs.isEmpty()) return false
        refs.forEach { logPianoEdit("Delete", it.channel, it.note, "via=$source") }
        var removed = 0
        mutateSong(historyLabel = "Delete selection") {
            removed = PianoRollEditLogic.deleteSelection(song, refs)
            clearSelection()
        }
        pianoRollLog.info { "[SPC-PIANO-EDIT] DeleteSelection source=$source removed=$removed" }
        return removed > 0
    }

    fun moveSelectionBy(deltaTick: Int, deltaPitch: Int, source: String): Boolean {
        val refs = selectedRefs()
        if (refs.isEmpty()) return false
        var moved = false
        mutateSong(historyLabel = "Move selection") {
            moved = PianoRollEditLogic.moveSelection(song, refs, deltaTick, deltaPitch, totalTicks)
        }
        if (moved) {
            selectedNotes = PianoRollEditLogic.sanitizeSelection(song, refs)
            selectedNote = selectedNotes.lastOrNull()?.note
            selectedChannel = selectedNotes.lastOrNull()?.channel ?: activeChannel
            pianoRollLog.info { "[SPC-PIANO-EDIT] MoveSelection source=$source count=${selectedNotes.size} dt=$deltaTick dp=$deltaPitch" }
        }
        return moved
    }

    fun copySelectionToClipboard(source: String): Boolean {
        val refs = selectedRefs()
        if (refs.isEmpty()) return false
        clipboardNotes = PianoRollEditLogic.copySelection(refs)
        pianoRollLog.info { "[SPC-PIANO-EDIT] CopySelection source=$source count=${clipboardNotes.size}" }
        return true
    }

    fun pasteClipboardAtVisibleCursor(): Boolean {
        if (clipboardNotes.isEmpty()) return false
        val pasteTick = quantizeTick((hScrollPx * ticksPerPixel).toInt()).coerceAtLeast(0)
        var pasted = emptyList<PianoRollEditLogic.NoteRef>()
        mutateSong(historyLabel = "Paste notes") {
            pasted = PianoRollEditLogic.pasteSelection(song, clipboardNotes, pasteTick, totalTicks)
        }
        if (pasted.isNotEmpty()) {
            setSelection(pasted)
            pianoRollLog.info { "[SPC-PIANO-EDIT] PasteSelection count=${pasted.size} tick=$pasteTick" }
        }
        return pasted.isNotEmpty()
    }

    fun undoMusicEdit(): Boolean {
        val restored = onUndo()
        if (restored) {
            clearSelection()
            editVersion++
        }
        return restored
    }

    fun redoMusicEdit(): Boolean {
        val restored = onRedo()
        if (restored) {
            clearSelection()
            editVersion++
        }
        return restored
    }

    fun maxStartTickForDuration(duration: Int): Int =
        (totalTicks - duration).coerceAtLeast(0)

    fun noteTemplateFor(channelIndex: Int, tick: Int): Note? {
        val channelNotes = song.channels[channelIndex].notes
        return channelNotes
            .filter { it.tick <= tick }
            .maxByOrNull { it.tick }
            ?: channelNotes.minByOrNull { abs(it.tick - tick) }
            ?: song.channels
                .flatMap { it.notes }
                .filter { it.instrument != 0 }
                .minByOrNull { abs(it.tick - tick) }
    }

    fun commitDrag(state: PianoRollDragState?) {
        if (state != null && state.changed) {
            val before = noteSummary(state.channel, state.note)
            val after = noteSummary(
                state.channel,
                state.currentTick,
                state.currentDuration,
                state.currentNoteValue,
                state.note.velocity,
                state.note.quantize,
                state.note.instrument
            )
            val action = if (state.mode == PianoRollDragMode.ResizeEnd) "Resize" else "Move"
            mutateSong(historyLabel = "$action note") {
                state.note.tick = state.currentTick
                state.note.duration = state.currentDuration
                state.note.noteValue = state.currentNoteValue
            }
            pianoRollLog.info { "[SPC-PIANO-EDIT] $action $before -> $after" }
        }
        dragState = null
    }

    LaunchedEffect(worldWidth, viewportWidth) {
        if (hScrollPx > maxHScroll) hScrollPx = maxHScroll
    }

    LaunchedEffect(song, activeChannel) {
        val cleanSelection = PianoRollEditLogic.sanitizeSelection(song, selectedNotes)
        if (cleanSelection.size != selectedNotes.size) {
            selectedNotes = cleanSelection
        }
        val stillExists = selectedNote?.let { note ->
            song.channels.any { channel -> channel.notes.any { it === note } }
        } ?: true
        if (!stillExists) {
            selectedNote = null
            showNoteProperties = false
            dragState = null
            selectedNotes = cleanSelection
        }
        if (selectedNote == null) selectedChannel = activeChannel
    }

    Column(modifier = modifier) {
        // Toolbar
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        ) {
            Button(
                onClick = onPlay,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp),
                enabled = !isPlaying
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Play", fontSize = 10.sp)
            }

            OutlinedButton(
                onClick = onStop,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Stop", fontSize = 10.sp)
            }

            OutlinedButton(
                onClick = {
                    val removed = song.channels[activeChannel].notes.size
                    mutateSong(historyLabel = "Clear channel") {
                        song.channels[activeChannel].notes.clear()
                        clearSelection()
                    }
                    pianoRollLog.info { "[SPC-PIANO-EDIT] Clear channel ch=${activeChannel + 1} removed=$removed" }
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Clear Ch", fontSize = 10.sp)
            }

            OutlinedButton(
                onClick = onReset,
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Undo, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Reset", fontSize = 10.sp)
            }

            Spacer(Modifier.width(8.dp))

            OutlinedButton(
                onClick = { undoMusicEdit() },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(28.dp),
                enabled = canUndo
            ) {
                Icon(Icons.Default.Undo, "Undo music edit", Modifier.size(15.dp))
            }
            OutlinedButton(
                onClick = { redoMusicEdit() },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(28.dp),
                enabled = canRedo
            ) {
                Icon(Icons.Default.Redo, "Redo music edit", Modifier.size(15.dp))
            }

            Spacer(Modifier.width(8.dp))

            FilterChip(
                selected = activeTool == PianoRollTool.Select,
                onClick = {
                    activeTool = PianoRollTool.Select
                    focusRequester.requestFocus()
                },
                label = { Icon(Icons.Default.SelectAll, "Select (S)", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
            FilterChip(
                selected = activeTool == PianoRollTool.Paint,
                onClick = {
                    activeTool = PianoRollTool.Paint
                    focusRequester.requestFocus()
                },
                label = { Icon(Icons.Default.Brush, "Paint (P)", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )
            FilterChip(
                selected = activeTool == PianoRollTool.Erase,
                onClick = {
                    activeTool = PianoRollTool.Erase
                    focusRequester.requestFocus()
                },
                label = { Icon(Icons.Default.Clear, "Erase (E)", Modifier.size(14.dp)) },
                modifier = Modifier.height(28.dp)
            )

            Spacer(Modifier.width(8.dp))

            // Channel selector
            for (ch in 0 until 8) {
                val hasNotes = song.channels[ch].notes.isNotEmpty()
                val isActive = ch == activeChannel
                Surface(
                    modifier = Modifier.height(24.dp).clickable {
                        selectedChannel = ch
                        onActiveChannelChanged(ch)
                    },
                    color = if (isActive) CHANNEL_COLORS[ch].copy(alpha = 0.8f)
                    else if (hasNotes) CHANNEL_COLORS[ch].copy(alpha = 0.2f)
                    else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp,
                        if (isActive) CHANNEL_COLORS[ch]
                        else if (hasNotes) CHANNEL_COLORS[ch].copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Text(
                        "${ch + 1}",
                        fontSize = 9.sp,
                        color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // Show all toggle
            Surface(
                modifier = Modifier.height(24.dp).clickable { showAllChannels = !showAllChannels },
                color = if (showAllChannels) Color(0xFF3A3A5A) else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Text("All", fontSize = 9.sp,
                    color = if (showAllChannels) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }

            Spacer(Modifier.weight(1f))

            // Zoom +/- controls
            OutlinedButton(
                onClick = { zoomIndex = (effectiveZoomIndex - 1).coerceAtLeast(0) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(24.dp),
                enabled = effectiveZoomIndex > 0
            ) {
                Icon(Icons.Default.Remove, null, Modifier.size(14.dp))
            }
            Text(ZOOM_LABELS[effectiveZoomIndex], fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton(
                onClick = { zoomIndex = (effectiveZoomIndex + 1).coerceAtMost(ZOOM_LEVELS.lastIndex) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(24.dp),
                enabled = effectiveZoomIndex < ZOOM_LEVELS.lastIndex
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp))
            }

            Spacer(Modifier.width(8.dp))

            Text("Tempo: ${song.tempo}", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = onDone,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.MusicNote, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Done", fontSize = 10.sp)
            }
        }

        PianoRollExportBudgetMeter(
            trackUsageBytes = trackUsageBytes,
            flattenedBytes = flattenedBytes,
            budgetBytes = exportBudgetBytes,
            relocationBudgetBytes = relocationBudgetBytes,
            originalSequenceBytes = originalSequenceBytes,
            isModified = song.isModified,
            fitPreview = budgetFit,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
        )

        // Piano roll canvas
        val gridHeight = (totalPitches * noteHeight).toInt()

        val vScrollState = rememberScrollState()

        // Start scrolled to show C3-C5 range
        var hasInitialScrolled by remember { mutableStateOf(false) }
        if (!hasInitialScrolled) {
            LaunchedEffect(Unit) {
                val targetPitch = totalPitches - 36 // C3-ish
                vScrollState.scrollTo((targetPitch * noteHeight).toInt().coerceAtLeast(0))
                hasInitialScrolled = true
            }
        }

        // Auto-scroll to follow playback cursor
        if (isPlaying && playbackTick >= 0) {
            LaunchedEffect(playbackTick) {
                val cursorX = (playbackTick / ticksPerPixel).toInt()
                // Auto-scroll if cursor is past ~80% of visible area
                if (maxHScroll > 0 && viewportWidth > 0 && cursorX > hScrollPx + viewportWidth * 0.8f) {
                    scrollTo(cursorX - viewportWidth * 0.25f)
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                    Spacer(Modifier.width(pianoKeyWidth.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewportWidth = it.width }
                            .onPointerEvent(PointerEventType.Scroll) { event ->
                                val ne = event.nativeEvent as? MouseEvent
                                val sd = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                                val pan = resolvePanScrollDelta(sd.x, sd.y, ne?.isShiftDown == true)
                                if (pan.x != 0f) scrollTo(hScrollPx + PianoRollEditLogic.scrollPixels(pan.x))
                            }
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .height(20.dp)
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    val ne = event.nativeEvent as? MouseEvent
                                    if (ne == null || ne.button == MouseEvent.BUTTON1) {
                                        val offset = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                        val tick = ((offset.x + hScrollPx) * ticksPerPixel).toInt()
                                        onSeek(tick.coerceIn(0, totalTicks))
                                    }
                                }
                        ) {
                            drawRect(Color(0xFF1A1A28), Offset.Zero, size)

                            val tickStep = 192 // one measure in 4/4
                            var tick = (((hScrollPx * ticksPerPixel).toInt() / tickStep) * tickStep).coerceAtLeast(0)
                            var measureNum = tick / tickStep + 1
                            val visibleEndTick = ((hScrollPx + size.width) * ticksPerPixel).toInt() + tickStep
                            while (tick <= totalTicks && tick <= visibleEndTick) {
                                val x = tick / ticksPerPixel - hScrollPx
                                drawLine(Color(0xFF4A4A5A), Offset(x, 0f), Offset(x, size.height))
                                val labelX = x + 3f
                                if (labelX >= 0f && labelX < size.width - 1f) {
                                    drawText(
                                        textMeasurer = textMeasurer,
                                        text = "$measureNum",
                                        topLeft = Offset(labelX, 2f),
                                        style = TextStyle(fontSize = 8.sp, color = Color(0xFF8899AA))
                                    )
                                }
                                tick += tickStep
                                measureNum++
                            }

                            if (playbackTick >= 0) {
                                val cx = playbackTick / ticksPerPixel - hScrollPx
                                if (cx in -4f..(size.width + 4f)) {
                                    drawRect(
                                        color = Color(0x44FFFFFF),
                                        topLeft = Offset(cx - 2f, 0f),
                                        size = Size(4f, size.height)
                                    )
                                    drawLine(PLAYBACK_CURSOR, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2f)
                                }
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    Canvas(
                        modifier = Modifier
                            .width(pianoKeyWidth.dp)
                            .fillMaxHeight()
                            .verticalScroll(vScrollState)
                            .height(gridHeight.dp)
                    ) {
                        for (pitch in 0 until totalPitches) {
                            val reversedPitch = totalPitches - 1 - pitch
                            val y = pitch * noteHeight
                            val semitone = reversedPitch % 12
                            val octave = reversedPitch / 12 + 1
                            val isBlack = semitone in intArrayOf(1, 3, 6, 8, 10)

                            drawRect(
                                color = if (isBlack) BLACK_KEY_BG else PIANO_KEY_BG,
                                topLeft = Offset(0f, y),
                                size = Size(pianoKeyWidth, noteHeight)
                            )

                            if (semitone == 0 || pitch == 0) {
                                val name = NspcSequence.SEMITONE_NAMES[semitone] + octave
                                val fontSize = if (noteHeight > 16) 9.sp else 7.sp
                                drawText(
                                    textMeasurer = textMeasurer,
                                    text = name,
                                    topLeft = Offset(2f, y + (noteHeight - 12) / 2),
                                    style = TextStyle(fontSize = fontSize, color = Color(0xFFAABBCC))
                                )
                            }

                            drawLine(GRID_LINE, Offset(0f, y + noteHeight), Offset(pianoKeyWidth, y + noteHeight))
                        }
                    }

                    val visibleChannels = if (showAllChannels) (0 until 8).toList() else listOf(activeChannel)
                    val redrawVersion = editVersion

                    fun noteAt(position: Offset): Pair<Int, Note>? {
                        val reversedPitch = totalPitches - 1 - (position.y / noteHeight).toInt()
                        val noteValue = NspcSequence.NOTE_MIN + reversedPitch.coerceIn(0, 71)

                        for (ch in visibleChannels.asReversed()) {
                            val note = song.channels[ch].notes.asReversed().firstOrNull {
                                val startX = it.tick / ticksPerPixel - hScrollPx
                                val endX = it.endTick / ticksPerPixel - hScrollPx
                                it.noteValue == noteValue &&
                                    position.x >= startX &&
                                    position.x <= endX + NOTE_RESIZE_HANDLE_PX
                            }
                            if (note != null) return ch to note
                        }
                        return null
                    }

                    fun isOnResizeHandle(note: Note, position: Offset): Boolean {
                        val endX = note.endTick / ticksPerPixel - hScrollPx
                        return abs(position.x - endX) <= NOTE_RESIZE_HANDLE_PX
                    }

                    fun commitSelectionBox(box: PianoRollSelectionBox) {
                        val leftTick = ((minOf(box.startWorldX, box.currentWorldX)) * ticksPerPixel).toInt()
                        val rightTick = ((maxOf(box.startWorldX, box.currentWorldX)) * ticksPerPixel).toInt()
                        val minPitchRow = totalPitches - 1 - (maxOf(box.startY, box.currentY) / noteHeight).toInt()
                        val maxPitchRow = totalPitches - 1 - (minOf(box.startY, box.currentY) / noteHeight).toInt()
                        val selected = PianoRollEditLogic.selectNotesInRect(
                            song,
                            visibleChannels,
                            leftTick,
                            rightTick,
                            NspcSequence.NOTE_MIN + minPitchRow.coerceIn(0, 71),
                            NspcSequence.NOTE_MIN + maxPitchRow.coerceIn(0, 71)
                        )
                        setSelection(selected)
                        selectionBox = null
                        pianoRollLog.info { "[SPC-PIANO-EDIT] SelectBox count=${selected.size} ticks=$leftTick..$rightTick" }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .focusable()
                            .onSizeChanged { viewportWidth = it.width }
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                val ctrl = event.isCtrlPressed || event.isMetaPressed
                                val tickStep = if (event.isShiftPressed) 48 else NOTE_TICK_STEP
                                when (event.key) {
                                    Key.S -> if (!ctrl) {
                                        activeTool = PianoRollTool.Select
                                        true
                                    } else {
                                        false
                                    }
                                    Key.P -> if (!ctrl) {
                                        activeTool = PianoRollTool.Paint
                                        true
                                    } else {
                                        false
                                    }
                                    Key.E -> if (!ctrl) {
                                        activeTool = PianoRollTool.Erase
                                        true
                                    } else {
                                        false
                                    }
                                    Key.C -> if (ctrl) {
                                        copySelectionToClipboard("keyboard")
                                    } else {
                                        false
                                    }
                                    Key.A -> if (ctrl) {
                                        val refs = visibleChannels.flatMap { ch ->
                                            song.channels[ch].notes.map { note -> PianoRollEditLogic.NoteRef(ch, note) }
                                        }
                                        setSelection(refs)
                                        pianoRollLog.info { "[SPC-PIANO-EDIT] SelectAll count=${refs.size}" }
                                        refs.isNotEmpty()
                                    } else {
                                        false
                                    }
                                    Key.Z -> if (ctrl) {
                                        if (event.isShiftPressed) redoMusicEdit() else undoMusicEdit()
                                    } else {
                                        false
                                    }
                                    Key.Y -> if (ctrl) {
                                        redoMusicEdit()
                                    } else {
                                        false
                                    }
                                    Key.X -> if (ctrl) {
                                        val copied = copySelectionToClipboard("keyboard-cut")
                                        if (copied) deleteSelection("keyboard-cut") else false
                                    } else {
                                        false
                                    }
                                    Key.V -> if (ctrl) {
                                        pasteClipboardAtVisibleCursor()
                                    } else {
                                        false
                                    }
                                    Key.DirectionLeft -> {
                                        moveSelectionBy(-tickStep, 0, "keyboard")
                                    }
                                    Key.DirectionRight -> {
                                        moveSelectionBy(tickStep, 0, "keyboard")
                                    }
                                    Key.DirectionUp -> {
                                        moveSelectionBy(0, 1, "keyboard")
                                    }
                                    Key.DirectionDown -> {
                                        moveSelectionBy(0, -1, "keyboard")
                                    }
                                    Key.Delete, Key.Backspace -> {
                                        deleteSelection("keyboard")
                                    }
                                    else -> false
                                }
                            }
                            .onPointerEvent(PointerEventType.Scroll) { event ->
                                val ne = event.nativeEvent as? MouseEvent
                                val sd = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                                val pan = resolvePanScrollDelta(sd.x, sd.y, ne?.isShiftDown == true)
                                if (pan.x != 0f) scrollTo(hScrollPx + PianoRollEditLogic.scrollPixels(pan.x))
                            }
                            .verticalScroll(vScrollState)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(gridHeight.dp)
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    focusRequester.requestFocus()
                                    val ne = event.nativeEvent as? MouseEvent
                                    val offset = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                    val hit = noteAt(offset)

                                    if (ne != null && ne.button == MouseEvent.BUTTON3) {
                                        if (hit != null) {
                                            val (ch, note) = hit
                                            selectNote(ch, note, openProperties = true)
                                        } else {
                                            showNoteProperties = false
                                        }
                                        return@onPointerEvent
                                    }

                                    if (ne == null || ne.button == MouseEvent.BUTTON1) {
                                        if (hit != null) {
                                            val (ch, note) = hit
                                            if (activeTool == PianoRollTool.Erase) {
                                                selectNote(ch, note)
                                                deleteSelection("eraser")
                                                return@onPointerEvent
                                            }
                                            selectNote(ch, note)
                                            val worldX = offset.x + hScrollPx
                                            dragState = PianoRollDragState(
                                                channel = ch,
                                                note = note,
                                                mode = if (isOnResizeHandle(note, offset)) PianoRollDragMode.ResizeEnd else PianoRollDragMode.Move,
                                                startWorldX = worldX,
                                                startY = offset.y,
                                                startTick = note.tick,
                                                startDuration = note.duration,
                                                startNoteValue = note.noteValue,
                                                currentTick = note.tick,
                                                currentDuration = note.duration,
                                                currentNoteValue = note.noteValue
                                            )
                                        } else {
                                            if (activeTool == PianoRollTool.Select) {
                                                showNoteProperties = false
                                                selectedNotes = emptyList()
                                                selectedNote = null
                                                selectionBox = PianoRollSelectionBox(
                                                    startWorldX = offset.x + hScrollPx,
                                                    startY = offset.y,
                                                    currentWorldX = offset.x + hScrollPx,
                                                    currentY = offset.y
                                                )
                                                return@onPointerEvent
                                            }
                                            if (activeTool == PianoRollTool.Erase) {
                                                return@onPointerEvent
                                            }
                                            val tick = ((offset.x + hScrollPx) * ticksPerPixel).toInt()
                                            val reversedPitch = totalPitches - 1 - (offset.y / noteHeight).toInt()
                                            val noteValue = NspcSequence.NOTE_MIN + reversedPitch.coerceIn(0, 71)

                                            val channel = song.channels[activeChannel]
                                            val quantizedTick = quantizeTick(tick).coerceIn(0, (totalTicks - 24).coerceAtLeast(0))
                                            val template = noteTemplateFor(activeChannel, quantizedTick)
                                            val newNote = Note(
                                                tick = quantizedTick,
                                                duration = template?.duration?.coerceIn(NOTE_TICK_STEP, 0x7F) ?: 24,
                                                noteValue = noteValue,
                                                velocity = template?.velocity ?: 15,
                                                quantize = template?.quantize ?: 7,
                                                instrument = template?.instrument ?: 0x0B
                                            )
                                            mutateSong(historyLabel = "Add note") {
                                                channel.notes.add(newNote)
                                                selectedNotes = listOf(PianoRollEditLogic.NoteRef(activeChannel, newNote))
                                                selectedNote = newNote
                                                selectedChannel = activeChannel
                                                showNoteProperties = false
                                            }
                                            logPianoEdit("Add", activeChannel, newNote)
                                        }
                                    }
                                }
                                .onPointerEvent(PointerEventType.Move) { event ->
                                    val box = selectionBox
                                    if (box != null) {
                                        val offset = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                        selectionBox = box.copy(
                                            currentWorldX = offset.x + hScrollPx,
                                            currentY = offset.y
                                        )
                                        return@onPointerEvent
                                    }
                                    val state = dragState ?: return@onPointerEvent
                                    val offset = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                                    val dxTicks = quantizeTickDelta(((offset.x + hScrollPx - state.startWorldX) * ticksPerPixel).roundToInt())

                                    dragState = when (state.mode) {
                                        PianoRollDragMode.Move -> {
                                            val pitchDelta = ((state.startY - offset.y) / noteHeight).roundToInt()
                                            state.copy(
                                                currentTick = (state.startTick + dxTicks).coerceIn(0, maxStartTickForDuration(state.currentDuration)),
                                                currentNoteValue = (state.startNoteValue + pitchDelta)
                                                    .coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX),
                                                changed = true
                                            )
                                        }
                                        PianoRollDragMode.ResizeEnd -> {
                                            val maxDuration = (totalTicks - state.startTick).coerceAtLeast(NOTE_TICK_STEP)
                                            state.copy(
                                                currentDuration = quantizeTick(state.startDuration + dxTicks)
                                                    .coerceIn(NOTE_TICK_STEP, maxDuration),
                                                changed = true
                                            )
                                        }
                                    }
                                }
                                .onPointerEvent(PointerEventType.Release) {
                                    val box = selectionBox
                                    if (box != null) {
                                        commitSelectionBox(box)
                                        return@onPointerEvent
                                    }
                                    commitDrag(dragState)
                                }
                                .onPointerEvent(PointerEventType.Exit) {
                                    val box = selectionBox
                                    if (box != null) {
                                        commitSelectionBox(box)
                                        return@onPointerEvent
                                    }
                                    commitDrag(dragState)
                                }
                        ) {
                            if (redrawVersion == Int.MIN_VALUE) return@Canvas
                            drawPianoGrid(size.width, size.height, totalPitches, noteHeight, ticksPerPixel, totalTicks, hScrollPx)

                            for (ch in visibleChannels) {
                                val isActiveCh = ch == activeChannel

                                for (note in song.channels[ch].notes) {
                                    val dragPreview = dragState?.takeIf { it.note === note }
                                    val drawTick = dragPreview?.currentTick ?: note.tick
                                    val drawDuration = dragPreview?.currentDuration ?: note.duration
                                    val drawNoteValue = dragPreview?.currentNoteValue ?: note.noteValue
                                    val pitchIdx = (drawNoteValue - NspcSequence.NOTE_MIN).coerceIn(0, 71)
                                    val reversedY = (totalPitches - 1 - pitchIdx) * noteHeight
                                    val x = drawTick / ticksPerPixel - hScrollPx
                                    val w = (drawDuration / ticksPerPixel).coerceAtLeast(3f)
                                    if (x + w < 0f || x > size.width) continue

                                    val isSelected = selectedNotes.any { it.note === note }
                                    val isNotePlaying = playbackTick >= 0 &&
                                        playbackTick >= drawTick &&
                                        playbackTick < drawTick + drawDuration

                                    val color = when {
                                        isNotePlaying -> CHANNEL_COLORS_BRIGHT[ch]
                                        isActiveCh -> CHANNEL_COLORS[ch]
                                        else -> CHANNEL_COLORS[ch].copy(alpha = 0.3f)
                                    }

                                    drawRect(
                                        color = color,
                                        topLeft = Offset(x, reversedY + 1f),
                                        size = Size(w, noteHeight - 2f)
                                    )

                                    if (isNotePlaying) {
                                        drawRect(
                                            color = CHANNEL_COLORS_BRIGHT[ch].copy(alpha = 0.15f),
                                            topLeft = Offset(x - 2f, reversedY - 2f),
                                            size = Size(w + 4f, noteHeight + 4f)
                                        )
                                    }

                                    if (isSelected) {
                                        drawRect(
                                            color = Color.White.copy(alpha = 0.3f),
                                            topLeft = Offset(x, reversedY),
                                            size = Size(w, noteHeight)
                                        )
                                        drawRect(
                                            color = Color.White.copy(alpha = 0.75f),
                                            topLeft = Offset(x + w - 3f, reversedY + 2f),
                                            size = Size(3f, noteHeight - 4f)
                                        )
                                    }
                                }
                            }

                            selectionBox?.let { box ->
                                val left = minOf(box.startWorldX, box.currentWorldX) - hScrollPx
                                val top = minOf(box.startY, box.currentY)
                                val width = abs(box.currentWorldX - box.startWorldX)
                                val height = abs(box.currentY - box.startY)
                                drawRect(
                                    color = Color.White.copy(alpha = 0.12f),
                                    topLeft = Offset(left, top),
                                    size = Size(width, height)
                                )
                                drawRect(
                                    color = Color.White.copy(alpha = 0.85f),
                                    topLeft = Offset(left, top),
                                    size = Size(width, height),
                                    style = Stroke(width = 1.5f)
                                )
                            }

                            if (playbackTick >= 0) {
                                val cursorX = playbackTick / ticksPerPixel - hScrollPx
                                if (cursorX in -2f..(size.width + 2f)) {
                                    drawLine(
                                        color = PLAYBACK_CURSOR,
                                        start = Offset(cursorX, 0f),
                                        end = Offset(cursorX, size.height),
                                        strokeWidth = 2f
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val noteForProperties = selectedNote
            if (showNoteProperties && noteForProperties != null) {
                NotePropertiesPanel(
                    note = noteForProperties,
                    channel = selectedChannel,
                    totalTicks = totalTicks,
                    instrumentInfos = instrumentInfos,
                    usedInstrumentIds = song.channels.flatMap { it.notes.map { note -> note.instrument } }.toSet().sorted(),
                    onInstrumentChanged = onInstrumentChanged,
                    onClose = { showNoteProperties = false },
                    onDelete = {
                        setSelection(listOf(PianoRollEditLogic.NoteRef(selectedChannel, noteForProperties)))
                        deleteSelection("properties")
                    },
                    onBeforeChange = { label -> onRecordUndo(label) },
                    onChanged = { mutateSong(historyLabel = null) { } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun PianoRollExportBudgetMeter(
    trackUsageBytes: Int,
    flattenedBytes: Int,
    budgetBytes: Int,
    relocationBudgetBytes: Int,
    originalSequenceBytes: Int,
    isModified: Boolean,
    fitPreview: MusicSequenceBudget.FitResult?,
    modifier: Modifier = Modifier
) {
    val hasBudget = budgetBytes > 0
    val ratio = if (hasBudget) trackUsageBytes.toFloat() / budgetBytes else 0f
    val trackDelta = trackUsageBytes - originalSequenceBytes
    val trackOver = trackUsageBytes - budgetBytes
    val relocationText = when {
        relocationBudgetBytes <= 0 -> "phase-1 flat export ${flattenedBytes} B"
        flattenedBytes <= relocationBudgetBytes -> "phase-1 flat export $flattenedBytes/$relocationBudgetBytes B"
        fitPreview != null -> {
            "phase-1 flat export $flattenedBytes/$relocationBudgetBytes B trims after tick ${fitPreview.cutoffTick}"
        }
        else -> "phase-1 flat export $flattenedBytes/$relocationBudgetBytes B cannot fit"
    }
    val color = when {
        !hasBudget -> MaterialTheme.colorScheme.outline
        trackUsageBytes <= budgetBytes -> Color(0xFF55D17A)
        ratio <= 1.10f -> Color(0xFFE0B84F)
        else -> Color(0xFFE06666)
    }
    val message = when {
        !hasBudget -> "Track budget unavailable; $relocationText"
        !isModified -> {
            "Track budget $trackUsageBytes/$budgetBytes B vanilla; $relocationText"
        }
        trackUsageBytes <= budgetBytes -> {
            val deltaLabel = if (trackDelta >= 0) "+$trackDelta" else trackDelta.toString()
            "Track budget $trackUsageBytes/$budgetBytes B ($deltaLabel B); $relocationText"
        }
        relocationBudgetBytes > 0 && flattenedBytes <= relocationBudgetBytes -> {
            "Track over by $trackOver B; $relocationText"
        }
        fitPreview != null -> "Track over by $trackOver B; $relocationText"
        else -> "Track over by $trackOver B; $relocationText"
    }

    Surface(
        modifier = modifier,
        color = Color(0xFF171722),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text("Export budget", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = ratio.coerceIn(0f, 1f),
                modifier = Modifier.width(160.dp).height(6.dp),
                color = color,
                trackColor = Color(0xFF2B2B38)
            )
            Text(message, fontSize = 9.sp, color = color, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

private fun DrawScope.drawPianoGrid(
    width: Float,
    height: Float,
    totalPitches: Int,
    noteHeight: Float,
    ticksPerPixel: Float,
    totalTicks: Int,
    scrollX: Float
) {
    // Horizontal lines (pitch rows)
    for (pitch in 0..totalPitches) {
        val y = pitch * noteHeight
        val reversedPitch = totalPitches - pitch
        val semitone = reversedPitch % 12
        val isBlack = semitone in intArrayOf(1, 3, 6, 8, 10)

        if (pitch < totalPitches && isBlack) {
            drawRect(
                color = BLACK_KEY_BG,
                topLeft = Offset(0f, y),
                size = Size(width, noteHeight)
            )
        }

        val lineColor = if (semitone == 0) GRID_BEAT else GRID_LINE
        drawLine(lineColor, Offset(0f, y), Offset(width, y))
    }

    // Vertical lines (time grid)
    val tickStep = 12 // 16th note grid
    var tick = (((scrollX * ticksPerPixel).toInt() / tickStep) * tickStep).coerceAtLeast(0)
    val visibleEndTick = ((scrollX + width) * ticksPerPixel).toInt() + tickStep
    while (tick <= totalTicks && tick <= visibleEndTick) {
        val x = tick / ticksPerPixel - scrollX
        val isBeat = tick % 48 == 0
        val isMeasure = tick % 192 == 0

        val color = when {
            isMeasure -> Color(0xFF5A5A6A)
            isBeat -> GRID_BEAT
            else -> GRID_LINE
        }
        drawLine(color, Offset(x, 0f), Offset(x, height))
        tick += tickStep
    }
}

@Composable
private fun NotePropertiesPanel(
    note: Note,
    channel: Int,
    totalTicks: Int,
    instrumentInfos: List<NspcRenderer.InstrumentEntry>,
    usedInstrumentIds: List<Int>,
    onInstrumentChanged: (NspcRenderer.InstrumentEntry) -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onBeforeChange: (String) -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxInstrument = if (instrumentInfos.isNotEmpty()) instrumentInfos.lastIndex else 255

    Surface(
        modifier = modifier.clearAndSetSemantics { },
        color = Color(0xFF151525),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color(0xFF3A3A4A))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.width(72.dp)) {
                    Text("Ch ${channel + 1}", fontSize = 9.sp, color = CHANNEL_COLORS[channel])
                    Text(note.name, fontSize = 13.sp, color = Color.White)
                    Text("${hex(note.noteValue)} / ${note.noteValue}", fontSize = 8.sp, color = Color(0xFF8899AA))
                }

                NoteNumberProperty("Tick", note.tick, 6, 999999) {
                    val old = note.tick
                    val next = quantizeTick(it).coerceIn(0, (totalTicks - note.duration).coerceAtLeast(0))
                    if (next != old) {
                        onBeforeChange("Set note tick")
                        note.tick = next
                        logPianoEdit("SetTick", channel, note, "$old->${note.tick}")
                        onChanged()
                    }
                }
                NoteNumberProperty("Len", note.duration, 4, 9999) {
                    val old = note.duration
                    val maxDuration = (totalTicks - note.tick).coerceAtLeast(NOTE_TICK_STEP)
                    val next = quantizeTick(it).coerceIn(NOTE_TICK_STEP, maxDuration)
                    if (next != old) {
                        onBeforeChange("Set note length")
                        note.duration = next
                        logPianoEdit("SetLen", channel, note, "$old->${note.duration}")
                        onChanged()
                    }
                }
                NoteNumberProperty("Pitch", note.noteValue, 3, NspcSequence.NOTE_MAX) {
                    val old = note.noteValue
                    val next = it.coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX)
                    if (next != old) {
                        onBeforeChange("Set note pitch")
                        note.noteValue = next
                        pianoRollLog.info {
                            "[SPC-PIANO-EDIT] SetPitch ch=${channel + 1} " +
                                "${NspcSequence.noteToName(old)}->${note.name} ${noteSummary(channel, note)}"
                        }
                        onChanged()
                    }
                }
                NoteNumberProperty("Vel", note.velocity, 2, 15) {
                    val old = note.velocity
                    val next = it.coerceIn(0, 15)
                    if (next != old) {
                        onBeforeChange("Set note velocity")
                        note.velocity = next
                        logPianoEdit("SetVel", channel, note, "$old->${note.velocity}")
                        onChanged()
                    }
                }
                NoteNumberProperty("Q", note.quantize, 1, 7) {
                    val old = note.quantize
                    val next = it.coerceIn(0, 7)
                    if (next != old) {
                        onBeforeChange("Set note quantize")
                        note.quantize = next
                        logPianoEdit("SetQuantize", channel, note, "$old->${note.quantize}")
                        onChanged()
                    }
                }
                NoteNumberProperty("Inst", note.instrument, 3, maxInstrument) {
                    val old = note.instrument
                    val next = it.coerceIn(0, maxInstrument)
                    if (next != old) {
                        onBeforeChange("Set note instrument")
                        note.instrument = next
                        val oldInst = old.toString(16).uppercase().padStart(2, '0')
                        val newInst = note.instrument.toString(16).uppercase().padStart(2, '0')
                        logPianoEdit("SetInstrument", channel, note, "0x$oldInst->0x$newInst")
                        onChanged()
                    }
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Delete", fontSize = 9.sp)
                }
                OutlinedButton(
                    onClick = onClose,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text("Close", fontSize = 9.sp)
                }
            }

            InstrumentInsightPanel(
                note = note,
                channel = channel,
                instrumentInfos = instrumentInfos,
                usedInstrumentIds = usedInstrumentIds,
                onInstrumentChanged = onInstrumentChanged,
                onInstrumentSelected = { inst ->
                    val old = note.instrument
                    if (inst != old) {
                        onBeforeChange("Set note instrument")
                        note.instrument = inst
                        logPianoEdit("SetInstrument", channel, note, "${hex(old)}->${hex(note.instrument)} via=used-chip")
                        onChanged()
                    }
                }
            )
        }
    }
}

@Composable
private fun InstrumentInsightPanel(
    note: Note,
    channel: Int,
    instrumentInfos: List<NspcRenderer.InstrumentEntry>,
    usedInstrumentIds: List<Int>,
    onInstrumentChanged: (NspcRenderer.InstrumentEntry) -> Unit,
    onInstrumentSelected: (Int) -> Unit
) {
    val info = instrumentInfos.getOrNull(note.instrument)
    val validRange = if (instrumentInfos.isNotEmpty()) "0..${instrumentInfos.lastIndex}" else "unknown"

    Surface(
        color = Color(0xFF101020),
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, Color(0xFF2A2A3A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Instrument ${hex(note.instrument)} / ${note.instrument}", fontSize = 10.sp, color = Color.White)
                Text("valid: $validRange", fontSize = 9.sp, color = Color(0xFF8899AA))
                if (info == null) {
                    Text("No table entry found for this song set", fontSize = 9.sp, color = Color(0xFFE0A055))
                }
            }

            if (info != null) {
                Text(
                    "Shared entry: SRCN selects a BRR sample; ADSR/GAIN/pitch adjust shape playback for every note using this Inst.",
                    fontSize = 8.sp,
                    color = Color(0xFF78889A),
                    lineHeight = 11.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InstrumentNumberProperty("SRCN", info.srcn, 2, 39, "0..39") {
                        onInstrumentChanged(info.copy(srcn = it))
                    }
                    InstrumentNumberProperty("ADSR1", info.adsr1, 3, 255, hex(info.adsr1)) {
                        onInstrumentChanged(info.copy(adsr1 = it))
                    }
                    InstrumentNumberProperty("ADSR2", info.adsr2, 3, 255, hex(info.adsr2)) {
                        onInstrumentChanged(info.copy(adsr2 = it))
                    }
                    InstrumentNumberProperty("GAIN", info.gain, 3, 255, hex(info.gain)) {
                        onInstrumentChanged(info.copy(gain = it))
                    }
                    InstrumentNumberProperty("Tune", info.pitchAdj, 5, 0xFFFF, "${"%.3f".format(instrumentPitchMultiplier(info))}x") {
                        onInstrumentChanged(info.copy(pitchAdj = it))
                    }
                    Text(
                        instrumentSummary(info),
                        fontSize = 9.sp,
                        color = Color(0xFFAABBCC),
                        lineHeight = 12.sp
                    )
                }
            }

            if (usedInstrumentIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Used in track:", fontSize = 9.sp, color = Color(0xFF8899AA))
                    for (inst in usedInstrumentIds.take(12)) {
                        val selected = inst == note.instrument
                        Surface(
                            modifier = Modifier.height(24.dp).clickable { onInstrumentSelected(inst) },
                            color = if (selected) CHANNEL_COLORS[channel].copy(alpha = 0.65f) else Color(0xFF202033),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, if (selected) CHANNEL_COLORS[channel] else Color(0xFF3A3A4A))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight().padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    hex(inst),
                                    fontSize = 8.sp,
                                    lineHeight = 8.sp,
                                    color = if (selected) Color.White else Color(0xFFAABBCC)
                                )
                            }
                        }
                    }
                    if (usedInstrumentIds.size > 12) {
                        Text("+${usedInstrumentIds.size - 12}", fontSize = 8.sp, color = Color(0xFF8899AA))
                    }
                }
            }
        }
    }
}

private fun instrumentSummary(info: NspcRenderer.InstrumentEntry): String {
    val brrBytes = if (info.brrByteLength >= 0) "${info.brrByteLength}B (${info.brrByteLength / 9} blocks)" else "unknown"
    val loop = if (info.brrLoops) "loop flag present" else "no loop flag seen"
    val adsrMode = if ((info.adsr1 and 0x80) != 0) "ADSR" else "GAIN"
    val attack = info.adsr1 and 0x0F
    val decay = (info.adsr1 shr 4) and 0x07
    val sustainLevel = (info.adsr2 shr 5) and 0x07
    val sustainRate = info.adsr2 and 0x1F
    return "table=${hex(info.tableAddr, 4)} entry=${hex(info.index)} sample=${hexOrUnknown(info.sampleStartAddr)} " +
        "loopPtr=${hexOrUnknown(info.sampleLoopAddr)} BRR=$brrBytes, $loop\n" +
        "mode=$adsrMode A=$attack D=$decay SL=$sustainLevel SR=$sustainRate tune=${"%.3f".format(instrumentPitchMultiplier(info))}x"
}

private fun instrumentPitchMultiplier(info: NspcRenderer.InstrumentEntry): Double =
    if (info.pitchAdj == 0) 1.0 else info.pitchAdj.toDouble() / 0x1000

@Composable
private fun NoteNumberProperty(
    label: String,
    value: Int,
    maxDigits: Int,
    maxValue: Int,
    onChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, color = Color(0xFFAABBCC))
        StatNumberInput(
            value = value,
            onChange = onChange,
            maxDigits = maxDigits,
            maxValue = maxValue,
            modifier = Modifier.width(54.dp)
        )
    }
}

@Composable
private fun InstrumentNumberProperty(
    label: String,
    value: Int,
    maxDigits: Int,
    maxValue: Int,
    hint: String,
    onChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 8.sp, color = Color(0xFFAABBCC), lineHeight = 8.sp)
        StatNumberInput(
            value = value,
            onChange = onChange,
            maxDigits = maxDigits,
            maxValue = maxValue,
            modifier = Modifier.width(if (maxValue > 255) 68.dp else 46.dp)
        )
        Text(hint, fontSize = 7.sp, color = Color(0xFF78889A), lineHeight = 7.sp)
    }
}
