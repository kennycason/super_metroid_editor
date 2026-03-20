package com.supermetroid.editor.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.GifEncoder
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SamusSpriteDecoder
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import com.supermetroid.editor.rom.renderMultiAnimationSheet
import com.supermetroid.editor.rom.renderSpriteSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SamusSpriteViewer(
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val rp = romParser
    if (rp == null) {
        Text("Load a ROM to view Samus sprites.", modifier = modifier.padding(16.dp))
        return
    }

    val decoder = remember(rp) { SamusSpriteDecoder(rp) }
    var selectedSuit by remember { mutableStateOf(SamusSpriteDecoder.SuitType.POWER) }
    var selectedGroupIdx by remember { mutableStateOf(0) }
    var selectedAnimIdx by remember { mutableStateOf(0) }
    var exportStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // Global playback state — persists across group/variant toggles
    var isPlaying by remember { mutableStateOf(false) }
    var animFrame by remember { mutableStateOf(0) }

    val groups = SamusSpriteDecoder.ANIMATION_GROUPS
    val currentGroup = groups.getOrNull(selectedGroupIdx) ?: groups.first()
    val currentAnimId = currentGroup.animationIds.getOrNull(selectedAnimIdx) ?: currentGroup.animationIds.first()

    // Build animation for the current selection
    val animation = remember(currentAnimId, selectedSuit) {
        // Reset frame to 0 when animation changes, but keep playing state
        animFrame = 0
        decoder.buildAnimation(currentAnimId, selectedSuit, renderSize = 96)
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF1A1A2E))) {
        // ── Header ──
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Samus Sprites", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface)

                Spacer(Modifier.width(16.dp))

                // Suit selector
                for (suit in SamusSpriteDecoder.SuitType.entries) {
                    FilterChip(
                        selected = selectedSuit == suit,
                        onClick = { selectedSuit = suit },
                        label = { Text(suit.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }

                Spacer(Modifier.weight(1f))

                // Export All Samus Sprites button
                Surface(
                    modifier = Modifier.clickable {
                        scope.launch(Dispatchers.IO) {
                            exportStatus = "Building all sprites..."
                            exportAllSamusSprites(decoder, selectedSuit)
                            exportStatus = ""
                        }
                    },
                    color = Color(0xFF2A3A2A),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("Export All Sprites", fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color(0xFF90D090))
                }
            }
        }

        // Status bar
        if (exportStatus.isNotEmpty()) {
            Text(exportStatus, fontSize = 9.sp, color = Color(0xFFFFD54F),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // ── Left: Animation group list ──
            Column(
                modifier = Modifier.width(180.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Animations", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))

                for ((gIdx, group) in groups.withIndex()) {
                    val frameCountPreview = decoder.getFrameCount(group.animationIds.first())
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                selectedGroupIdx = gIdx
                                selectedAnimIdx = 0
                            },
                        color = if (gIdx == selectedGroupIdx) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(group.name, fontSize = 10.sp,
                                fontWeight = if (gIdx == selectedGroupIdx) FontWeight.Bold else FontWeight.Normal,
                                color = if (gIdx == selectedGroupIdx) MaterialTheme.colorScheme.onPrimaryContainer
                                else Color(0xFFB0B8D1))
                            Text("${group.animationIds.size} variants, $frameCountPreview frames — ${group.description}",
                                fontSize = 8.sp, color = Color(0xFF6A6F88))
                        }
                    }
                }
            }

            // ── Divider ──
            Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFF2A2D45)))

            // ── Right: Animation player + details ──
            Column(
                modifier = Modifier.weight(1f).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Direction selector (if group has multiple anims)
                if (currentGroup.animationIds.size > 1) {
                    Text("Direction / Variant", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for ((aIdx, _) in currentGroup.animationIds.withIndex()) {
                            Surface(
                                modifier = Modifier.clickable {
                                    selectedAnimIdx = aIdx
                                },
                                color = if (aIdx == selectedAnimIdx) MaterialTheme.colorScheme.primaryContainer
                                else Color(0xFF2A2D45),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("$aIdx", fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = if (aIdx == selectedAnimIdx) MaterialTheme.colorScheme.onPrimaryContainer
                                    else Color(0xFFB0B8D1),
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Animation name
                Text(
                    "Anim 0x${currentAnimId.toString(16).uppercase()} — ${currentGroup.name}",
                    fontSize = 10.sp, color = Color(0xFFB0B8D1)
                )
                Spacer(Modifier.height(8.dp))

                // ── Animation Player (state hoisted for persistence across toggles) ──
                AnimationPlayer(
                    animation = animation,
                    previewSize = 288,
                    playing = isPlaying,
                    onPlayingChanged = { isPlaying = it },
                    currentFrame = animFrame,
                    onFrameChanged = { animFrame = it },
                    onExportPng = { frame, idx ->
                        scope.launch(Dispatchers.IO) {
                            exportFramePng(frame, "samus_${currentGroup.name.lowercase()}_${currentAnimId}_frame$idx")
                        }
                    },
                    onExportGif = { anim ->
                        scope.launch(Dispatchers.IO) {
                            exportAnimationGif(anim, "samus_${currentGroup.name.lowercase()}_${currentAnimId}")
                        }
                    },
                    onExportSheet = { anim ->
                        scope.launch(Dispatchers.IO) {
                            exportAnimationSheet(anim, "samus_${currentGroup.name.lowercase()}_${currentAnimId}_sheet")
                        }
                    }
                )

                // Palette display
                Spacer(Modifier.height(16.dp))
                Text("Palette (${selectedSuit.name})", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))

                val palette = remember(selectedSuit) { decoder.readPalette(selectedSuit) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (i in 0 until 16) {
                        val argb = palette[i]
                        val r = (argb shr 16) and 0xFF
                        val g = (argb shr 8) and 0xFF
                        val b = argb and 0xFF
                        Box(
                            modifier = Modifier
                                .weight(1f).height(20.dp)
                                .background(
                                    if (i == 0) Color(0xFF2A2A3A) else Color(r / 255f, g / 255f, b / 255f),
                                    RoundedCornerShape(2.dp)
                                )
                                .border(1.dp, Color(0xFF3A3F5C), RoundedCornerShape(2.dp))
                        ) {
                            if (i == 0) {
                                Text("T", fontSize = 7.sp, color = Color(0xFF4A4C5E),
                                    modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Export helpers ──────────────────────────────────────────────────

private fun exportFramePng(frame: SpriteAnimationFrame, defaultName: String) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Frame as PNG"
        selectedFile = File("$defaultName.png")
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile.let {
            if (!it.name.endsWith(".png")) File(it.parentFile, "${it.name}.png") else it
        }
        val bi = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
        ImageIO.write(bi, "png", file)
    }
}

private fun exportAnimationGif(animation: SpriteAnimation, defaultName: String) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Animation as GIF"
        selectedFile = File("$defaultName.gif")
        fileFilter = FileNameExtensionFilter("GIF Images", "gif")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile.let {
            if (!it.name.endsWith(".gif")) File(it.parentFile, "${it.name}.gif") else it
        }
        val gifBytes = GifEncoder.encode(animation.frames)
        file.writeBytes(gifBytes)
    }
}

private fun exportAnimationSheet(animation: SpriteAnimation, defaultName: String) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Sprite Sheet as PNG"
        selectedFile = File("$defaultName.png")
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile.let {
            if (!it.name.endsWith(".png")) File(it.parentFile, "${it.name}.png") else it
        }
        val (pixels, w, h) = renderSpriteSheet(animation.frames, columns = 8)
        if (w > 0 && h > 0) {
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            bi.setRGB(0, 0, w, h, pixels, 0, w)
            ImageIO.write(bi, "png", file)
        }
    }
}

private fun exportAllSamusSprites(decoder: SamusSpriteDecoder, suit: SamusSpriteDecoder.SuitType) {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save All Samus Sprites as Sprite Sheet"
        selectedFile = File("samus_all_sprites_${suit.name.lowercase()}.png")
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val file = chooser.selectedFile.let {
            if (!it.name.endsWith(".png")) File(it.parentFile, "${it.name}.png") else it
        }
        val allAnimations = decoder.buildAllAnimations(suit, renderSize = 64)
        if (allAnimations.isNotEmpty()) {
            val (pixels, w, h) = renderMultiAnimationSheet(allAnimations)
            if (w > 0 && h > 0) {
                val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                bi.setRGB(0, 0, w, h, pixels, 0, w)
                ImageIO.write(bi, "png", file)
            }
        }
    }
}
