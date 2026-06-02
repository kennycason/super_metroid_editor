package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.NspcSequence.Song
import com.supermetroid.editor.rom.NspcSequence.Note
import kotlinx.coroutines.launch

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

private val PIANO_KEY_BG = Color(0xFF1A1A2E)
private val GRID_LINE = Color(0xFF2A2A3A)
private val GRID_BEAT = Color(0xFF3A3A4A)
private val BLACK_KEY_BG = Color(0xFF151525)
private val NOTE_SELECTED = Color(0xFFFFFFFF)

/**
 * Piano roll editor for N-SPC sequence data.
 * Displays notes on a grid with pitch (vertical) and time (horizontal).
 */
@Composable
fun PianoRollEditor(
    song: Song,
    activeChannel: Int,
    onSongChanged: (Song) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // View state
    var ticksPerPixel by remember { mutableStateOf(0.5f) }  // zoom level
    val noteHeight = 10f  // pixels per semitone
    val pianoKeyWidth = 40f  // width of piano key labels
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
                    // Clear all notes in active channel
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
                    modifier = Modifier.height(24.dp).clickable {
                        // Channel selection handled by parent
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

            // Zoom controls
            Text("Zoom:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((label, tpp) in listOf("1x" to 1.0f, "2x" to 0.5f, "4x" to 0.25f)) {
                Surface(
                    modifier = Modifier.height(22.dp).clickable { ticksPerPixel = tpp },
                    color = if (ticksPerPixel == tpp) Color(0xFF3A3A5A) else Color.Transparent,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Text(label, fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }

            Spacer(Modifier.width(8.dp))

            // Tempo
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
        val gridHeight = totalPitches * noteHeight

        val hScrollState = rememberScrollState()
        val vScrollState = rememberScrollState()

        // Start scrolled to middle (around C3-C4 range)
        val initialVScroll = ((totalPitches - 36) * noteHeight).toInt()
        var hasInitialScrolled by remember { mutableStateOf(false) }
        if (!hasInitialScrolled) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                vScrollState.scrollTo(initialVScroll.coerceAtLeast(0))
                hasInitialScrolled = true
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // Piano key labels (fixed left column)
            Canvas(
                modifier = Modifier
                    .width(pianoKeyWidth.dp)
                    .fillMaxHeight()
                    .verticalScroll(vScrollState)
                    .height((gridHeight / 1).dp)
            ) {
                for (pitch in 0 until totalPitches) {
                    val reversedPitch = totalPitches - 1 - pitch
                    val y = pitch * noteHeight
                    val semitone = reversedPitch % 12
                    val octave = reversedPitch / 12 + 1
                    val isBlack = semitone in intArrayOf(1, 3, 6, 8, 10)

                    // Key background
                    drawRect(
                        color = if (isBlack) BLACK_KEY_BG else PIANO_KEY_BG,
                        topLeft = Offset(0f, y),
                        size = Size(pianoKeyWidth, noteHeight)
                    )

                    // Note name on C notes
                    if (semitone == 0 || pitch == 0) {
                        val name = NspcSequence.SEMITONE_NAMES[semitone] + octave
                        drawText(
                            textMeasurer = textMeasurer,
                            text = name,
                            topLeft = Offset(2f, y),
                            style = TextStyle(
                                fontSize = 7.sp,
                                color = Color(0xFFAABBCC)
                            )
                        )
                    }

                    // Border
                    drawLine(GRID_LINE, Offset(0f, y), Offset(pianoKeyWidth, y))
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
                        .width((gridWidth / 1).dp)
                        .height((gridHeight / 1).dp)
                        .pointerInput(activeChannel, ticksPerPixel) {
                            detectTapGestures { offset ->
                                val tick = (offset.x * ticksPerPixel).toInt()
                                val reversedPitch = totalPitches - 1 - (offset.y / noteHeight).toInt()
                                val noteValue = NspcSequence.NOTE_MIN + reversedPitch.coerceIn(0, 71)

                                // Check if clicking on existing note
                                val channel = song.channels[activeChannel]
                                val clicked = channel.notes.find { note ->
                                    tick >= note.tick && tick < note.endTick &&
                                        note.noteValue == noteValue
                                }

                                if (clicked != null) {
                                    if (selectedNote == clicked) {
                                        // Double-click to delete
                                        channel.notes.remove(clicked)
                                        selectedNote = null
                                        onSongChanged(song)
                                    } else {
                                        selectedNote = clicked
                                    }
                                } else {
                                    // Add new note quantized to beat grid
                                    val quantizedTick = (tick / 12) * 12
                                    val newNote = Note(
                                        tick = quantizedTick,
                                        duration = 24, // default 8th note
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
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    // Draw grid
                    drawPianoGrid(canvasWidth, canvasHeight, totalPitches, noteHeight, ticksPerPixel, totalTicks)

                    // Draw notes
                    val channelsToDraw = if (showAllChannels) (0 until 8).toList() else listOf(activeChannel)
                    for (ch in channelsToDraw) {
                        val isActive = ch == activeChannel
                        val alpha = if (isActive) 1f else 0.3f
                        val color = CHANNEL_COLORS[ch].copy(alpha = alpha)

                        for (note in song.channels[ch].notes) {
                            val pitchIdx = note.pitchIndex
                            val reversedY = (totalPitches - 1 - pitchIdx) * noteHeight
                            val x = note.tick / ticksPerPixel
                            val w = (note.duration / ticksPerPixel).coerceAtLeast(2f)

                            // Note rectangle
                            drawRect(
                                color = color,
                                topLeft = Offset(x, reversedY + 1f),
                                size = Size(w, noteHeight - 2f)
                            )

                            // Selected highlight
                            if (note == selectedNote) {
                                drawRect(
                                    color = NOTE_SELECTED.copy(alpha = 0.3f),
                                    topLeft = Offset(x, reversedY),
                                    size = Size(w, noteHeight)
                                )
                            }
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
    totalTicks: Int
) {
    // Horizontal lines (pitch rows)
    for (pitch in 0..totalPitches) {
        val y = pitch * noteHeight
        val reversedPitch = totalPitches - pitch
        val semitone = reversedPitch % 12
        val isBlack = semitone in intArrayOf(1, 3, 6, 8, 10)

        // Black key row background
        if (pitch < totalPitches && isBlack) {
            drawRect(
                color = BLACK_KEY_BG,
                topLeft = Offset(0f, y),
                size = Size(width, noteHeight)
            )
        }

        // Row line (brighter on C notes)
        val lineColor = if (semitone == 0) GRID_BEAT else GRID_LINE
        drawLine(lineColor, Offset(0f, y), Offset(width, y))
    }

    // Vertical lines (time grid)
    // 48 ticks = quarter note, 12 ticks = 16th note
    val tickStep = 12 // 16th note grid
    var tick = 0
    while (tick <= totalTicks) {
        val x = tick / ticksPerPixel
        val isBeat = tick % 48 == 0
        val isMeasure = tick % 192 == 0 // 4/4 time

        val color = when {
            isMeasure -> Color(0xFF5A5A6A)
            isBeat -> GRID_BEAT
            else -> GRID_LINE
        }
        drawLine(color, Offset(x, 0f), Offset(x, height))
        tick += tickStep
    }
}
