package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.NspcSequence.Song
import com.supermetroid.editor.rom.NspcSequence.Note

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
private val ZOOM_LEVELS = floatArrayOf(2.0f, 1.0f, 0.5f, 0.25f, 0.125f, 0.0625f)
private val ZOOM_LABELS = arrayOf("1x", "2x", "4x", "8x", "16x", "32x")
private const val BASE_NOTE_HEIGHT = 20f  // base note height at zoom index 0

@Composable
fun PianoRollEditor(
    song: Song,
    activeChannel: Int,
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

    // View state
    var zoomIndex by remember { mutableStateOf(2) } // start at 4x
    val ticksPerPixel = ZOOM_LEVELS[zoomIndex]
    val noteHeight = BASE_NOTE_HEIGHT + zoomIndex * 4f  // taller at higher zoom
    val pianoKeyWidth = 44f
    val totalPitches = 72  // C1-B6

    // Editing state
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var showAllChannels by remember { mutableStateOf(true) }

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
                    song.channels[activeChannel].notes.clear()
                    onSongChanged(song)
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
                    modifier = Modifier.height(24.dp).clickable { },
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
                onClick = { if (zoomIndex > 0) zoomIndex-- },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(24.dp),
                enabled = zoomIndex > 0
            ) {
                Icon(Icons.Default.Remove, null, Modifier.size(14.dp))
            }
            Text(ZOOM_LABELS[zoomIndex], fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            OutlinedButton(
                onClick = { if (zoomIndex < ZOOM_LEVELS.size - 1) zoomIndex++ },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(24.dp),
                enabled = zoomIndex < ZOOM_LEVELS.size - 1
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

        // Piano roll canvas
        val totalTicks = (song.totalTicks + 200).coerceAtLeast(960)
        val gridWidth = (totalTicks / ticksPerPixel).toInt()
        val gridHeight = (totalPitches * noteHeight).toInt()

        val hScrollState = rememberScrollState()
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
                val scrollPos = hScrollState.value
                val maxScroll = hScrollState.maxValue
                // Auto-scroll if cursor is past ~80% of visible area
                if (maxScroll > 0 && cursorX > scrollPos + 600) {
                    hScrollState.scrollTo((cursorX - 200).coerceIn(0, maxScroll))
                }
            }
        }

        // Timeline / seek bar
        Row(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            Spacer(Modifier.width(pianoKeyWidth.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidth.dp)
                        .height(20.dp)
                        .pointerInput(ticksPerPixel) {
                            detectTapGestures { offset ->
                                val tick = (offset.x * ticksPerPixel).toInt()
                                onSeek(tick.coerceAtLeast(0))
                            }
                        }
                ) {
                    // Background
                    drawRect(Color(0xFF1A1A28), Offset.Zero, size)

                    // Measure markers
                    val tickStep = 192 // one measure in 4/4
                    var tick = 0
                    var measureNum = 1
                    while (tick <= totalTicks) {
                        val x = tick / ticksPerPixel
                        drawLine(Color(0xFF4A4A5A), Offset(x, 0f), Offset(x, size.height))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = "$measureNum",
                            topLeft = Offset(x + 3f, 2f),
                            style = TextStyle(fontSize = 8.sp, color = Color(0xFF8899AA))
                        )
                        tick += tickStep
                        measureNum++
                    }

                    // Playback cursor
                    if (playbackTick >= 0) {
                        val cx = playbackTick / ticksPerPixel
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScrollState)
                    .verticalScroll(vScrollState)
            ) {
                Canvas(
                    modifier = Modifier
                        .width(gridWidth.dp)
                        .height(gridHeight.dp)
                        .pointerInput(activeChannel, ticksPerPixel, noteHeight) {
                            detectTapGestures { offset ->
                                val tick = (offset.x * ticksPerPixel).toInt()
                                val reversedPitch = totalPitches - 1 - (offset.y / noteHeight).toInt()
                                val noteValue = NspcSequence.NOTE_MIN + reversedPitch.coerceIn(0, 71)

                                val channel = song.channels[activeChannel]
                                val clicked = channel.notes.find { note ->
                                    tick >= note.tick && tick < note.endTick &&
                                        note.noteValue == noteValue
                                }

                                if (clicked != null) {
                                    if (selectedNote == clicked) {
                                        channel.notes.remove(clicked)
                                        selectedNote = null
                                        onSongChanged(song)
                                    } else {
                                        selectedNote = clicked
                                    }
                                } else {
                                    val quantizedTick = (tick / 12) * 12
                                    val newNote = Note(
                                        tick = quantizedTick,
                                        duration = 24,
                                        noteValue = noteValue,
                                        velocity = 15,
                                        quantize = 7,
                                        instrument = channel.notes.lastOrNull()?.instrument ?: 0
                                    )
                                    channel.notes.add(newNote)
                                    selectedNote = newNote
                                    onSongChanged(song)
                                }
                            }
                        }
                ) {
                    // Draw grid
                    drawPianoGrid(size.width, size.height, totalPitches, noteHeight, ticksPerPixel, totalTicks)

                    // Draw notes
                    val channelsToDraw = if (showAllChannels) (0 until 8).toList() else listOf(activeChannel)
                    for (ch in channelsToDraw) {
                        val isActiveCh = ch == activeChannel

                        for (note in song.channels[ch].notes) {
                            val pitchIdx = note.pitchIndex
                            val reversedY = (totalPitches - 1 - pitchIdx) * noteHeight
                            val x = note.tick / ticksPerPixel
                            val w = (note.duration / ticksPerPixel).coerceAtLeast(3f)

                            // Is this note currently playing?
                            val isNotePlaying = playbackTick >= 0 &&
                                playbackTick >= note.tick &&
                                playbackTick < note.endTick

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

                            // Selected highlight
                            if (note == selectedNote) {
                                drawRect(
                                    color = Color.White.copy(alpha = 0.3f),
                                    topLeft = Offset(x, reversedY),
                                    size = Size(w, noteHeight)
                                )
                            }
                        }
                    }

                    // Playback cursor line
                    if (playbackTick >= 0) {
                        val cursorX = playbackTick / ticksPerPixel
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

private fun DrawScope.drawPianoGrid(
    width: Float,
    height: Float,
    totalPitches: Int,
    noteHeight: Float,
    ticksPerPixel: Float,
    totalTicks: Int
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
    var tick = 0
    while (tick <= totalTicks) {
        val x = tick / ticksPerPixel
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
