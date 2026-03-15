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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SamusSpriteDecoder
import java.awt.image.BufferedImage

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
    var selectedAnimIdx by remember { mutableStateOf(0) }  // index within group's animationIds
    var selectedFrame by remember { mutableStateOf(0) }

    val groups = SamusSpriteDecoder.ANIMATION_GROUPS
    val currentGroup = groups.getOrNull(selectedGroupIdx) ?: groups.first()
    val currentAnimId = currentGroup.animationIds.getOrNull(selectedAnimIdx) ?: currentGroup.animationIds.first()
    val frameCount = remember(currentAnimId) { decoder.getFrameCount(currentAnimId) }
    val safeFrame = selectedFrame.coerceIn(0, (frameCount - 1).coerceAtLeast(0))

    val palette = remember(selectedSuit) { decoder.readPalette(selectedSuit) }

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
            }
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
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                selectedGroupIdx = gIdx
                                selectedAnimIdx = 0
                                selectedFrame = 0
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
                            Text("${group.animationIds.size} anims — ${group.description}", fontSize = 8.sp,
                                color = Color(0xFF6A6F88))
                        }
                    }
                }
            }

            // ── Divider ──
            Box(Modifier.width(1.dp).fillMaxSize().background(Color(0xFF2A2D45)))

            // ── Right: Sprite preview ──
            Column(
                modifier = Modifier.weight(1f).fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animation direction selector (if group has multiple anims)
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
                                    selectedFrame = 0
                                },
                                color = if (aIdx == selectedAnimIdx) MaterialTheme.colorScheme.primaryContainer
                                else Color(0xFF2A2D45),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("${aIdx}", fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = if (aIdx == selectedAnimIdx) MaterialTheme.colorScheme.onPrimaryContainer
                                    else Color(0xFFB0B8D1),
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Frame info
                Text(
                    "Anim 0x${currentAnimId.toString(16).uppercase()} — Frame $safeFrame/$frameCount",
                    fontSize = 10.sp, color = Color(0xFFB0B8D1)
                )
                Spacer(Modifier.height(8.dp))

                // Rendered pose
                val pose = remember(currentAnimId, safeFrame) { decoder.getPose(currentAnimId, safeFrame) }
                if (pose != null) {
                    val imgSize = 96
                    val pixels = remember(pose, palette) {
                        decoder.renderPose(pose, palette, imgSize, imgSize)
                    }
                    val img = remember(pixels) {
                        val bi = BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_ARGB)
                        bi.setRGB(0, 0, imgSize, imgSize, pixels, 0, imgSize)
                        bi
                    }

                    Box(
                        modifier = Modifier.size(288.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF3A3F5C), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        // Checkerboard background
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
                            contentDescription = "Samus pose",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                    }

                    // Tilemap info
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${pose.tilemaps.size} tilemap entries",
                        fontSize = 9.sp, color = Color(0xFF6A6F88)
                    )
                } else {
                    Text("Could not decode pose", fontSize = 10.sp, color = Color(0xFFFF6B6B))
                }

                // Frame selector
                if (frameCount > 1) {
                    Spacer(Modifier.height(12.dp))
                    Text("Frames", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))

                    // Render thumbnails for all frames
                    val thumbnailSize = 48
                    val thumbnails = remember(currentAnimId, palette) {
                        (0 until frameCount).map { frame ->
                            val p = decoder.getPose(currentAnimId, frame) ?: return@map null
                            val px = decoder.renderPose(p, palette, thumbnailSize, thumbnailSize)
                            val bi = BufferedImage(thumbnailSize, thumbnailSize, BufferedImage.TYPE_INT_ARGB)
                            bi.setRGB(0, 0, thumbnailSize, thumbnailSize, px, 0, thumbnailSize)
                            bi
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(56.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().height(
                            ((thumbnails.size + 14) / 15 * 70).coerceIn(70, 600).dp
                        )
                    ) {
                        itemsIndexed(thumbnails) { idx, thumb ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable { selectedFrame = idx }
                                    .border(
                                        if (idx == safeFrame) 2.dp else 1.dp,
                                        if (idx == safeFrame) Color(0xFFFFD54F) else Color(0xFF3A3F5C),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .background(Color(0xFF2A2A3A), RoundedCornerShape(4.dp))
                                    .padding(2.dp)
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.toComposeImageBitmap(),
                                        contentDescription = "Frame $idx",
                                        modifier = Modifier.size(48.dp),
                                        filterQuality = FilterQuality.None
                                    )
                                } else {
                                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                        Text("?", color = Color(0xFFFF6B6B), fontSize = 10.sp)
                                    }
                                }
                                Text("$idx", fontSize = 7.sp, color = Color(0xFF6A6F88))
                            }
                        }
                    }
                }

                // Palette display
                Spacer(Modifier.height(16.dp))
                Text("Palette (${selectedSuit.name})", fontSize = 10.sp, color = Color(0xFFB0B8D1),
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
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
