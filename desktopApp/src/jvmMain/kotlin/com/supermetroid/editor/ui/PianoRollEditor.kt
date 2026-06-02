package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.NspcSequence.Song
import com.supermetroid.editor.rom.NspcSequence.Note
import java.awt.event.MouseEvent
import kotlin.math.abs
import kotlin.math.roundToInt

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

private enum class PianoRollDragMode { Move, ResizeEnd }

private data class PianoRollDragState(
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PianoRollEditor(
    song: Song,
    activeChannel: Int,
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
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var selectedChannel by remember { mutableStateOf(activeChannel) }
    var showAllChannels by remember { mutableStateOf(true) }
    var showNoteProperties by remember { mutableStateOf(false) }
    var dragState by remember { mutableStateOf<PianoRollDragState?>(null) }

    fun scrollTo(x: Float) {
        hScrollPx = x.coerceIn(0f, maxHScroll)
    }

    fun mutateSong(markModified: Boolean = true, mutation: () -> Unit) {
        mutation()
        if (markModified) {
            song.isModified = true
        }
        onSongChanged(song)
    }

    fun selectNote(channel: Int, note: Note, openProperties: Boolean = false) {
        selectedChannel = channel
        selectedNote = note
        if (channel != activeChannel) onActiveChannelChanged(channel)
        if (openProperties) showNoteProperties = true
    }

    fun maxStartTickFor(note: Note): Int =
        (totalTicks - note.duration).coerceAtLeast(0)

    fun maxStartTickForDuration(duration: Int): Int =
        (totalTicks - duration).coerceAtLeast(0)

    fun commitDrag(state: PianoRollDragState?) {
        if (state != null && state.changed) {
            mutateSong {
                state.note.tick = state.currentTick
                state.note.duration = state.currentDuration
                state.note.noteValue = state.currentNoteValue
            }
        }
        dragState = null
    }

    LaunchedEffect(worldWidth, viewportWidth) {
        if (hScrollPx > maxHScroll) hScrollPx = maxHScroll
    }

    LaunchedEffect(song, activeChannel) {
        val stillExists = selectedNote?.let { note ->
            song.channels.any { channel -> channel.notes.any { it === note } }
        } ?: true
        if (!stillExists) {
            selectedNote = null
            showNoteProperties = false
            dragState = null
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
                    mutateSong {
                        song.channels[activeChannel].notes.clear()
                        selectedNote = null
                        showNoteProperties = false
                    }
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

        val noteForProperties = selectedNote
        if (showNoteProperties && noteForProperties != null) {
            NotePropertiesPanel(
                note = noteForProperties,
                channel = selectedChannel,
                totalTicks = totalTicks,
                onClose = { showNoteProperties = false },
                onDelete = {
                    mutateSong {
                        song.channels.forEach { channel ->
                            channel.notes.removeAll { it === noteForProperties }
                        }
                        selectedNote = null
                        showNoteProperties = false
                    }
                },
                onChanged = { mutateSong { } },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            )
        }

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

        // Timeline / seek bar
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
                        if (pan.x != 0f) scrollTo(hScrollPx + pan.x)
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
                    // Background
                    drawRect(Color(0xFF1A1A28), Offset.Zero, size)

                    // Measure markers
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

                    // Playback cursor
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
            // Piano key labels (fixed left column)
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

                    // Note name on C notes and at top
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

            // Main grid + notes
            val visibleChannels = if (showAllChannels) (0 until 8).toList() else listOf(activeChannel)

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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onSizeChanged { viewportWidth = it.width }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val note = selectedNote ?: return@onKeyEvent false
                        val tickStep = if (event.isShiftPressed) 48 else NOTE_TICK_STEP
                        when (event.key) {
                            Key.DirectionLeft -> {
                                mutateSong { note.tick = (note.tick - tickStep).coerceAtLeast(0) }
                                true
                            }
                            Key.DirectionRight -> {
                                mutateSong { note.tick = quantizeTick(note.tick + tickStep).coerceAtMost(maxStartTickFor(note)) }
                                true
                            }
                            Key.DirectionUp -> {
                                mutateSong { note.noteValue = (note.noteValue + 1).coerceAtMost(NspcSequence.NOTE_MAX) }
                                true
                            }
                            Key.DirectionDown -> {
                                mutateSong { note.noteValue = (note.noteValue - 1).coerceAtLeast(NspcSequence.NOTE_MIN) }
                                true
                            }
                            Key.Delete, Key.Backspace -> {
                                mutateSong {
                                    song.channels.forEach { channel ->
                                        channel.notes.removeAll { it === note }
                                    }
                                    selectedNote = null
                                    showNoteProperties = false
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val ne = event.nativeEvent as? MouseEvent
                        val sd = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                        val pan = resolvePanScrollDelta(sd.x, sd.y, ne?.isShiftDown == true)
                        if (pan.x != 0f) scrollTo(hScrollPx + pan.x)
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
                                    selectNote(ch, note)
                                    val worldX = offset.x + hScrollPx
                                    dragState = PianoRollDragState(
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
                                    val tick = ((offset.x + hScrollPx) * ticksPerPixel).toInt()
                                    val reversedPitch = totalPitches - 1 - (offset.y / noteHeight).toInt()
                                    val noteValue = NspcSequence.NOTE_MIN + reversedPitch.coerceIn(0, 71)

                                    val channel = song.channels[activeChannel]
                                    val newNote = Note(
                                        tick = quantizeTick(tick).coerceIn(0, (totalTicks - 24).coerceAtLeast(0)),
                                        duration = 24,
                                        noteValue = noteValue,
                                        velocity = 15,
                                        quantize = 7,
                                        instrument = channel.notes.lastOrNull()?.instrument ?: 0
                                    )
                                    mutateSong {
                                        channel.notes.add(newNote)
                                        selectedNote = newNote
                                        selectedChannel = activeChannel
                                        showNoteProperties = false
                                    }
                                }
                            }
                        }
                        .onPointerEvent(PointerEventType.Move) { event ->
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
                            commitDrag(dragState)
                        }
                        .onPointerEvent(PointerEventType.Exit) {
                            commitDrag(dragState)
                        }
                ) {
                    // Draw grid
                    drawPianoGrid(size.width, size.height, totalPitches, noteHeight, ticksPerPixel, totalTicks, hScrollPx)

                    // Draw notes
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

                            // Is this note currently playing?
                            val isNotePlaying = playbackTick >= 0 &&
                                playbackTick >= drawTick &&
                                playbackTick < drawTick + drawDuration

                            val color = when {
                                isNotePlaying -> CHANNEL_COLORS_BRIGHT[ch]
                                isActiveCh -> CHANNEL_COLORS[ch]
                                else -> CHANNEL_COLORS[ch].copy(alpha = 0.3f)
                            }

                            // Note body
                            drawRect(
                                color = color,
                                topLeft = Offset(x, reversedY + 1f),
                                size = Size(w, noteHeight - 2f)
                            )

                            // Glow effect for playing notes
                            if (isNotePlaying) {
                                drawRect(
                                    color = CHANNEL_COLORS_BRIGHT[ch].copy(alpha = 0.15f),
                                    topLeft = Offset(x - 2f, reversedY - 2f),
                                    size = Size(w + 4f, noteHeight + 4f)
                                )
                            }

                            // Selected highlight + resize grip
                            if (note === selectedNote) {
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

                    // Playback cursor line
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
    onClose: () -> Unit,
    onDelete: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF151525),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color(0xFF3A3A4A))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.width(72.dp)) {
                Text("Ch ${channel + 1}", fontSize = 9.sp, color = CHANNEL_COLORS[channel])
                Text(note.name, fontSize = 13.sp, color = Color.White)
            }

            NoteNumberProperty("Tick", note.tick, 6, 999999) {
                note.tick = quantizeTick(it).coerceIn(0, (totalTicks - note.duration).coerceAtLeast(0))
                onChanged()
            }
            NoteNumberProperty("Len", note.duration, 4, 9999) {
                val maxDuration = (totalTicks - note.tick).coerceAtLeast(NOTE_TICK_STEP)
                note.duration = quantizeTick(it).coerceIn(NOTE_TICK_STEP, maxDuration)
                onChanged()
            }
            NoteNumberProperty("Pitch", note.noteValue, 3, NspcSequence.NOTE_MAX) {
                note.noteValue = it.coerceIn(NspcSequence.NOTE_MIN, NspcSequence.NOTE_MAX)
                onChanged()
            }
            NoteNumberProperty("Vel", note.velocity, 2, 15) {
                note.velocity = it.coerceIn(0, 15)
                onChanged()
            }
            NoteNumberProperty("Q", note.quantize, 1, 7) {
                note.quantize = it.coerceIn(0, 7)
                onChanged()
            }
            NoteNumberProperty("Inst", note.instrument, 3, 255) {
                note.instrument = it.coerceIn(0, 255)
                onChanged()
            }

            Spacer(Modifier.weight(1f))

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
    }
}

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
