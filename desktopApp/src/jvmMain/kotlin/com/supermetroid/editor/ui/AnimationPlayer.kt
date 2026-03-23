package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import kotlinx.coroutines.delay
import java.awt.image.BufferedImage

/**
 * Compose widget for playing sprite animations with transport controls.
 *
 * Features:
 * - Play/Pause with Material Icons
 * - Frame-by-frame stepping (arrow keys, skip buttons)
 * - Speed multiplier (0.25x to 4x)
 * - Frame scrubber slider
 * - Export callbacks for PNG/GIF/Sheet
 */
@Composable
fun AnimationPlayer(
    animation: SpriteAnimation?,
    modifier: Modifier = Modifier,
    previewSize: Int = 288,
    playing: Boolean = false,
    onPlayingChanged: ((Boolean) -> Unit)? = null,
    currentFrame: Int = 0,
    onFrameChanged: ((Int) -> Unit)? = null,
    onExportPng: ((SpriteAnimationFrame, Int) -> Unit)? = null,
    onExportGif: ((SpriteAnimation) -> Unit)? = null,
    onExportSheet: ((SpriteAnimation) -> Unit)? = null,
    showExportButtons: Boolean = true
) {
    if (animation == null || animation.frames.isEmpty()) {
        Box(modifier.size(previewSize.dp), contentAlignment = Alignment.Center) {
            Text("No animation data", fontSize = 10.sp, color = Color(0xFF6A6F88))
        }
        return
    }

    // Use internal state if no external state is provided (standalone mode)
    var internalFrame by remember(animation) { mutableStateOf(0) }
    var internalPlaying by remember(animation) { mutableStateOf(false) }

    val isPlaying = if (onPlayingChanged != null) playing else internalPlaying
    val activeFrame = if (onFrameChanged != null) currentFrame else internalFrame

    fun setPlaying(value: Boolean) {
        if (onPlayingChanged != null) onPlayingChanged(value) else internalPlaying = value
    }
    fun setFrame(value: Int) {
        if (onFrameChanged != null) onFrameChanged(value) else internalFrame = value
    }

    var speedMultiplier by remember { mutableStateOf(1f) }

    val frames = animation.frames
    val safeFrame = activeFrame.coerceIn(0, frames.size - 1)
    val frame = frames[safeFrame]

    // Auto-play timer
    LaunchedEffect(isPlaying, safeFrame, speedMultiplier, animation) {
        if (!isPlaying || frames.size <= 1) return@LaunchedEffect

        val delayMs = if (frame.durationTicks > 0) {
            ((frame.durationTicks * 1000L) / 60.0 / speedMultiplier).toLong().coerceAtLeast(16)
        } else {
            (67.0 / speedMultiplier).toLong().coerceAtLeast(16)
        }

        delay(delayMs)

        val next = safeFrame + 1
        if (next >= frames.size) {
            if (animation.loop) {
                setFrame(0)
            } else {
                setPlaying(false)
            }
        } else {
            setFrame(next)
        }
    }

    Column(
        modifier = modifier
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar -> { setPlaying(!isPlaying); true }
                    Key.DirectionLeft -> {
                        setPlaying(false)
                        setFrame(if (safeFrame > 0) safeFrame - 1 else frames.size - 1)
                        true
                    }
                    Key.DirectionRight -> {
                        setPlaying(false)
                        setFrame(if (safeFrame < frames.size - 1) safeFrame + 1 else 0)
                        true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Sprite preview ──
        val img = remember(frame) {
            val bi = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
            bi
        }

        Box(
            modifier = Modifier.size(previewSize.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF3A3F5C), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            // Checkerboard
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val cellSize = 12f
                val cols = (size.width / cellSize).toInt() + 1
                val rows = (size.height / cellSize).toInt() + 1
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val color = if ((r + c) % 2 == 0) Color(0xFF2A2A3A) else Color(0xFF333348)
                        drawRect(color, Offset(c * cellSize, r * cellSize), Size(cellSize, cellSize))
                    }
                }
            }

            Image(
                bitmap = img.toComposeImageBitmap(),
                contentDescription = frame.label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Frame info ──
        val timingText = if (frame.durationTicks > 0) {
            "${frame.durationTicks} ticks (${frame.durationMs}ms)"
        } else "default timing"
        Text(
            "Frame ${safeFrame + 1}/${frames.size} — $timingText",
            fontSize = 9.sp, color = Color(0xFFB0B8D1), fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(4.dp))

        // ── Transport controls ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step back
            IconButton(
                onClick = {
                    setPlaying(false)
                    setFrame(if (safeFrame > 0) safeFrame - 1 else frames.size - 1)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.SkipPrevious, "Previous frame",
                    modifier = Modifier.size(18.dp), tint = Color(0xFFB0B8D1))
            }

            // Play / Pause — distinct behavior
            if (isPlaying) {
                IconButton(
                    onClick = { setPlaying(false) },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(Icons.Default.Pause, "Pause",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } else {
                IconButton(
                    onClick = {
                        if (frames.size > 1) setPlaying(true)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, "Play",
                        modifier = Modifier.size(22.dp), tint = Color(0xFFB0B8D1))
                }
            }

            // Stop (reset to frame 0)
            IconButton(
                onClick = {
                    setPlaying(false)
                    setFrame(0)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Stop, "Stop",
                    modifier = Modifier.size(18.dp), tint = Color(0xFFB0B8D1))
            }

            // Step forward
            IconButton(
                onClick = {
                    setPlaying(false)
                    setFrame(if (safeFrame < frames.size - 1) safeFrame + 1 else 0)
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.SkipNext, "Next frame",
                    modifier = Modifier.size(18.dp), tint = Color(0xFFB0B8D1))
            }

            Spacer(Modifier.width(8.dp))

            // Speed selector
            val speeds = listOf(0.25f, 0.5f, 1f, 2f, 4f)
            for (speed in speeds) {
                val label = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
                Surface(
                    modifier = Modifier.clickable { speedMultiplier = speed },
                    color = if (speedMultiplier == speed) MaterialTheme.colorScheme.primaryContainer
                    else Color(0xFF2A2D45),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        label, fontSize = 8.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = if (speedMultiplier == speed) MaterialTheme.colorScheme.onPrimaryContainer
                        else Color(0xFFB0B8D1),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // ── Frame scrubber ──
        if (frames.size > 1) {
            Spacer(Modifier.height(4.dp))
            Slider(
                value = safeFrame.toFloat(),
                onValueChange = {
                    setPlaying(false)
                    setFrame(it.toInt())
                },
                valueRange = 0f..(frames.size - 1).toFloat(),
                steps = (frames.size - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color(0xFF3A3F5C)
                )
            )
        }

        // ── Export buttons ──
        if (showExportButtons && (onExportPng != null || onExportGif != null || onExportSheet != null)) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Export:", fontSize = 9.sp, color = Color(0xFF6A6F88))

                if (onExportPng != null) {
                    ExportButton("Frame PNG") { onExportPng(frame, safeFrame) }
                }
                if (onExportGif != null && frames.size > 1) {
                    ExportButton("Animation GIF") { onExportGif(animation) }
                }
                if (onExportSheet != null) {
                    ExportButton("Sprite Sheet") { onExportSheet(animation) }
                }
            }
        }
    }
}

@Composable
private fun ExportButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = Color(0xFF2A3A2A),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label, fontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color(0xFF90D090)
        )
    }
}
