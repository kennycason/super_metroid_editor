package com.supermetroid.editor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.PublishedWithChanges
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.NspcRenderer
import com.supermetroid.editor.rom.NspcSequence
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpcData
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max

// ─── Waveform / bar visualization ───────────────────────────────────────

private val GRADIENT_COLORS = arrayOf(
    java.awt.Color(0x6C, 0x5C, 0xE7),
    java.awt.Color(0x00, 0xB8, 0x94),
    java.awt.Color(0x00, 0xCC, 0x76),
    java.awt.Color(0xFD, 0xCB, 0x6E),
    java.awt.Color(0xE1, 0x7E, 0x55),
    java.awt.Color(0xE0, 0x56, 0x6B),
)

private fun lerpColor(a: java.awt.Color, b: java.awt.Color, t: Float): java.awt.Color {
    val r = (a.red + (b.red - a.red) * t).toInt().coerceIn(0, 255)
    val g = (a.green + (b.green - a.green) * t).toInt().coerceIn(0, 255)
    val bl = (a.blue + (b.blue - a.blue) * t).toInt().coerceIn(0, 255)
    return java.awt.Color(r, g, bl)
}

private fun gradientColor(amplitude: Float): java.awt.Color {
    val t = amplitude.coerceIn(0f, 1f) * (GRADIENT_COLORS.size - 1)
    val i = t.toInt().coerceIn(0, GRADIENT_COLORS.size - 2)
    return lerpColor(GRADIENT_COLORS[i], GRADIENT_COLORS[i + 1], t - i)
}

private fun renderWaveformBars(
    samples: ShortArray,
    width: Int,
    height: Int,
    loopStart: Int = -1,
    playbackFraction: Float = -1f
): BufferedImage {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = java.awt.Color(0x0C, 0x0C, 0x18)
    g.fillRect(0, 0, width, height)
    if (samples.isEmpty()) { g.dispose(); return img }

    val midY = height / 2
    val barWidth = max(1, 2)
    val gap = 1
    val barCount = width / (barWidth + gap)

    for (i in 0 until barCount) {
        val startS = (i.toLong() * samples.size / barCount).toInt()
        val endS = minOf(((i.toLong() + 1) * samples.size / barCount).toInt(), samples.size)
        if (startS >= samples.size) break

        var sumSq = 0.0
        var peak = 0
        for (j in startS until endS) {
            val v = abs(samples[j].toInt())
            sumSq += v.toDouble() * v
            if (v > peak) peak = v
        }
        val rms = kotlin.math.sqrt(sumSq / (endS - startS)).toInt()
        val peakH = (peak * (midY - 2) / 32768f).toInt().coerceAtLeast(1)
        val rmsH = (rms * (midY - 2) / 32768f).toInt().coerceAtLeast(1)

        val x = i * (barWidth + gap)
        val amplitude = peak / 32768f

        val barColor = gradientColor(amplitude)
        g.color = barColor
        g.fillRect(x, midY - rmsH, barWidth, rmsH * 2)

        g.color = java.awt.Color(barColor.red, barColor.green, barColor.blue, 80)
        g.fillRect(x, midY - peakH, barWidth, peakH * 2)
    }

    g.color = java.awt.Color(0xFF, 0xFF, 0xFF, 0x18)
    g.drawLine(0, midY, width, midY)

    if (loopStart in 0 until samples.size) {
        val loopX = (loopStart.toLong() * width / samples.size).toInt()
        g.color = java.awt.Color(0xFD, 0xCB, 0x6E, 0xCC)
        g.drawLine(loopX, 0, loopX, height)
        g.color = java.awt.Color(0xFD, 0xCB, 0x6E, 0x44)
        g.fillRect(loopX, 0, 3, height)
    }

    if (playbackFraction in 0f..1f) {
        val px = (playbackFraction * width).toInt().coerceIn(0, width - 1)
        g.color = java.awt.Color(0xFF, 0x22, 0x22, 0xDD)
        g.fillRect(px - 1, 0, 3, height)
    }

    g.dispose()
    return img
}

// ─── Left column: sound list panel ──────────────────────────────────────

@Composable
fun SoundListPanel(
    romParser: RomParser?,
    @Suppress("UNUSED_PARAMETER") editorState: EditorState,
    soundEditorState: SoundEditorState,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = modifier) {
        TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, modifier = Modifier.height(32.dp)) {
                Text("Tracks", fontSize = 12.sp)
            }
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, modifier = Modifier.height(32.dp)) {
                Text("Samples", fontSize = 12.sp)
            }
        }
        key(selectedTab) {
            when (selectedTab) {
                0 -> TrackListContent(romParser, soundEditorState, Modifier.fillMaxSize())
                1 -> SampleListContent(romParser, soundEditorState, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TrackListContent(
    romParser: RomParser?,
    state: SoundEditorState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (romParser == null) {
            Text("Load a ROM to browse tracks", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        } else {
            for (track in SpcData.KNOWN_TRACKS) {
                val isSel = state.selectedTrackId == track.id
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { state.selectTrack(track) }
                        .padding(horizontal = 2.dp),
                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                            Text("${track.area}  0x${track.songSet.toString(16).uppercase().padStart(2, '0')}:${track.playIndex.toString(16).uppercase().padStart(2, '0')}",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleListContent(
    romParser: RomParser?,
    state: SoundEditorState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    LaunchedEffect(romParser) {
        if (romParser != null && state.samples.isEmpty()) {
            try {
                state.loadSamples(romParser)
            } catch (e: Exception) {
                System.err.println("[SPC] Sample load error: ${e.message}")
            }
        }
    }

    val currentSamples = state.samples
    val loading = state.isLoadingSamples

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                Text("Loading samples...", fontSize = 12.sp)
            }
        } else if (currentSamples.isEmpty()) {
            Text("No samples found.\nLoad a ROM first.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        } else {
            for (sample in currentSamples) {
                val isSel = state.selectedSampleIndex == sample.dirEntry.index
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { scope.launch { state.selectSample(sample) } }
                        .padding(horizontal = 2.dp),
                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(3.dp)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("#${sample.dirEntry.index}", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sample ${sample.dirEntry.index}", fontSize = 12.sp, maxLines = 1, lineHeight = 15.sp)
                            Text("${sample.pcmData.size} pcm" + if (sample.loopStart >= 0) " (loop)" else "",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp)
                        }
                        Icon(Icons.Default.PlayArrow, "Play",
                            modifier = Modifier.size(18.dp).clickable {
                                state.playSample(sample)
                            }, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ─── Right canvas: sound editor view ─────────────────────────────────────

@Composable
fun SoundEditorCanvas(
    romParser: RomParser?,
    @Suppress("UNUSED_PARAMETER") editorState: EditorState,
    soundEditorState: SoundEditorState,
    modifier: Modifier = Modifier
) {
    val state = soundEditorState
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.selectedTrackId, state.sampleRate) {
        if (state.selectedTrack != null && romParser != null) {
            try {
                state.loadTrackSamples(romParser)
            } catch (e: Exception) {
                System.err.println("[SPC] Track load error in LaunchedEffect: ${e.message}")
            }
        }
    }

    LaunchedEffect(state.pendingNextTrack) {
        state.checkPendingAdvance()
    }

    val track = state.selectedTrack
    val sample = state.selectedSample
    val waveform = state.currentWaveform
    val loading = state.isLoadingTrack
    val loopStart = state.currentLoopStart
    val status = state.statusMessage

    val viewMode = when {
        track != null -> 1
        sample != null -> 2
        waveform != null -> 3  // imported WAV with no track/sample selected
        else -> 0
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Sound", fontWeight = FontWeight.Bold, fontSize = 12.sp)

            if (track != null) {
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text(track.name, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(track.area, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (sample != null) {
                Text("│", fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
                Text("Sample #${sample.dirEntry.index}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text("${sample.pcmData.size} pcm" + if (sample.loopStart >= 0) " loop" else "",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.weight(1f))

            if (status.isNotEmpty()) {
                Text(status, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Spacer(Modifier.height(4.dp))

        // Piano roll mode vs waveform mode
        if (state.isPianoRollOpen && state.editingSong != null) {
            // Tick update loop for playback cursor
            LaunchedEffect(state.isPlaying) {
                while (state.isPlaying && state.isPianoRollOpen) {
                    state.updatePianoRollTick()
                    kotlinx.coroutines.delay(30)
                }
            }

            PianoRollEditor(
                song = state.editingSong!!,
                activeChannel = state.pianoRollChannel,
                onActiveChannelChanged = { state.pianoRollChannel = it },
                onSongChanged = { state.notifySongChanged(it) },
                onPlay = {
                    scope.launch {
                        state.editingSong?.let { song ->
                            if (romParser != null) {
                                // Use native SPC emulator (cycle-accurate hardware emulation)
                                val wav = state.renderEditedSong(romParser, song)
                                if (wav != null && wav.isNotEmpty()) {
                                    state.startPianoRollPlayback(wav)
                                } else {
                                    val ram = state.buildSpcRamForTrack(romParser, state.currentSongSet.coerceAtLeast(0))
                                    val events = song.channels.flatMapIndexed { ch, channel ->
                                        channel.notes.map { note ->
                                            val qTable = NspcSequence.QUANTIZE_TABLE
                                            val vTable = NspcSequence.VELOCITY_TABLE
                                            val playDur = (note.duration * qTable[note.quantize]).toInt().coerceAtLeast(1)
                                            NspcRenderer.NoteEvent(
                                                tickStart = note.tick,
                                                tickDuration = playDur,
                                                channel = ch,
                                                noteValue = note.noteValue,
                                                instrumentIdx = note.instrument,
                                                volume = vTable[note.velocity]
                                            )
                                        }
                                    }
                                    val fallbackWav = NspcRenderer.renderToWav(events, ram, tempo = song.tempo)
                                    state.startPianoRollPlayback(fallbackWav)
                                }
                            }
                        }
                    }
                },
                onStop = {
                    state.stopPlayback()
                    state.pianoRollPlaybackTick = 0
                },
                onDone = { state.closePianoRoll() },
                onReset = { if (romParser != null) state.resetPianoRoll(romParser) },
                onSeek = { tick -> state.pianoRollPlaybackTick = tick },
                isPlaying = state.isPlaying,
                playbackTick = state.pianoRollPlaybackTick,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            key(viewMode) {
                if (viewMode != 0) {
                    SoundEditorActiveContent(
                        state = state,
                        editorState = editorState,
                        romParser = romParser,
                        scope = scope,
                        track = track,
                        sample = sample,
                        waveform = waveform,
                        loading = loading,
                        loopStart = loopStart,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Select a track or sample", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Browse tracks and BRR samples from the ROM", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundEditorActiveContent(
    state: SoundEditorState,
    editorState: EditorState,
    romParser: RomParser?,
    scope: kotlinx.coroutines.CoroutineScope,
    track: SpcData.TrackInfo?,
    sample: SoundEditorState.DecodedSample?,
    waveform: ShortArray?,
    loading: Boolean,
    loopStart: Int,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            state.updatePlaybackPosition()
            kotlinx.coroutines.delay(50)
        }
    }

    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    scope.launch {
                        if (sample != null) state.playSample(sample, loop = state.loopEnabled)
                        else state.playTrackPreview(state.playbackPosition)
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp),
                enabled = (sample != null || waveform != null) && !loading
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Play", fontSize = 10.sp)
            }

            val playAllOn = state.playAllEnabled
            Surface(
                modifier = Modifier.height(28.dp).clickable {
                    state.playAllEnabled = !state.playAllEnabled
                    if (state.playAllEnabled && !state.isPlaying) {
                        scope.launch { state.playTrackPreview() }
                    }
                },
                color = if (playAllOn) Color(0xFF2196F3) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (playAllOn) Color(0xFF2196F3) else MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.QueueMusic, null, Modifier.size(14.dp),
                        tint = if (playAllOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text("Play All", fontSize = 10.sp,
                        color = if (playAllOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedButton(
                onClick = { state.stopPlayback(); state.playAllEnabled = false },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Icon(Icons.Default.Stop, null, Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text("Stop", fontSize = 10.sp)
            }

            val loopOn = state.loopEnabled
            Surface(
                modifier = Modifier.height(28.dp).clickable { state.loopEnabled = !state.loopEnabled },
                color = if (loopOn) Color(0xFF7C4DFF) else Color.Transparent,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (loopOn) Color(0xFF7C4DFF) else MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Loop, null, Modifier.size(14.dp),
                        tint = if (loopOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text("Loop", fontSize = 10.sp,
                        color = if (loopOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (waveform != null && waveform.isNotEmpty()) {
                OutlinedButton(
                    onClick = { scope.launch { state.exportCurrentWav() } },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Export WAV", fontSize = 10.sp)
                }
            }

            // Edit Track — visible when a track is selected
            if (track != null && romParser != null) {
                Button(
                    onClick = { state.openPianoRoll(romParser) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Edit Track", fontSize = 10.sp)
                }
            }

            // Replace WAV — visible when a sample is selected
            if (sample != null && romParser != null) {
                Button(
                    onClick = {
                        state.replaceSampleInRom(romParser, editorState, sample.dirEntry.index)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.height(28.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE05555)
                    )
                ) {
                    Icon(Icons.Default.PublishedWithChanges, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Replace WAV", fontSize = 10.sp)
                }

                // Reset — only if this sample has been replaced
                if (state.isSampleReplaced(editorState, sample.dirEntry.index)) {
                    OutlinedButton(
                        onClick = {
                            state.resetSampleReplacement(editorState, sample.dirEntry.index)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Undo, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Reset", fontSize = 10.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Text("Rate:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (rate in listOf(8000, 16000, 32000)) {
                FilterChip(
                    selected = state.sampleRate == rate,
                    onClick = { state.sampleRate = rate },
                    label = { Text("${rate / 1000}k", fontSize = 8.sp) },
                    modifier = Modifier.height(22.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        val waveformKey = waveform?.size ?: 0
        val pbPos = state.playbackPosition
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(EditorColors.romBackground, RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF2A2A3A), RoundedCornerShape(6.dp))
        ) {
            if (waveform != null && waveform.isNotEmpty()) {
                val waveImg = remember(waveformKey, loopStart, pbPos) {
                    renderWaveformBars(waveform, 900, 240, loopStart, if (state.isPlaying || pbPos > 0f) pbPos else -1f)
                        .toComposeImageBitmap()
                }
                Image(bitmap = waveImg, contentDescription = "Waveform",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                        .pointerInput(waveform) {
                            detectTapGestures { offset ->
                                val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                state.seekTo(frac)
                                if (state.isPlaying) {
                                    scope.launch {
                                        val s = state.selectedSample
                                        if (s != null) state.playSample(s, loop = state.loopEnabled)
                                        else state.playTrackPreview(frac)
                                    }
                                }
                            }
                        })

                val durationMs = waveform.size * 1000L / state.sampleRate
                val peak = waveform.maxOf { abs(it.toInt()) }
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Color(0x88000000)).padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("${durationMs}ms", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("${waveform.size} samples", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("${state.sampleRate}Hz", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    Text("peak: $peak", fontSize = 9.sp, color = Color(0xFFAABBCC))
                    if (loopStart >= 0) {
                        Spacer(Modifier.weight(1f))
                        Text("loop @ $loopStart", fontSize = 9.sp, color = Color(0xFFFDCB6E))
                    }
                }
            } else if (loading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("Loading instruments...",
                            color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("Select a track or sample to see waveform",
                        color = Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        if (track != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("Song Set: 0x${track.songSet.toString(16).uppercase().padStart(2, '0')}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Play Index: 0x${track.playIndex.toString(16).uppercase().padStart(2, '0')}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Area: ${track.area}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (sample != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("SPC: 0x${sample.dirEntry.startAddr.toString(16).uppercase().padStart(4, '0')}",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sample.loopStart >= 0) {
                    Text("Loop: 0x${sample.dirEntry.loopAddr.toString(16).uppercase().padStart(4, '0')}",
                        fontSize = 9.sp, color = Color(0xFFFDCB6E))
                }
                if (romParser != null && state.isSampleReplaced(editorState, sample.dirEntry.index)) {
                    Text("REPLACED", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        color = Color(0xFFE05555))
                }
            }
        }
    }
}
