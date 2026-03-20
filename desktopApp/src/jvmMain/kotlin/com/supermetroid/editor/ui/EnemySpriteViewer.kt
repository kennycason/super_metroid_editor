package com.supermetroid.editor.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.GifEncoder
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import com.supermetroid.editor.rom.renderSpriteSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun EnemySpriteViewer(
    entry: EnemySpriteGraphics.Companion.EnemySpriteEntry,
    romParser: RomParser?,
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    val rp = romParser
    if (rp == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Load a ROM to view sprites", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Pixel editor state
    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingWidth by remember { mutableStateOf(0) }
    var editingHeight by remember { mutableStateOf(0) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingReference by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    // If pixel editor is open, show it full-screen
    val ep = editingPixels
    if (ep != null) {
        SpritePixelEditor(
            label = "${entry.name} Tile Sheet",
            initialPixels = ep,
            imageWidth = editingWidth,
            imageHeight = editingHeight,
            fixedPalette = editingPalette,
            referenceImage = editingReference,
            onApply = { pixels ->
                editorState.applyEnemyTileSheetEdits(rp, entry.speciesId, pixels, editingWidth, editingHeight)
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingPalette = null
                editingReference = null
            },
            modifier = modifier
        )
        return
    }

    val stats = remember(entry.speciesId) {
        EnemySpriteGraphics.readSpeciesStats(rp, entry.speciesId)
    }
    val palette = remember(entry.speciesId) {
        EnemySpriteGraphics.readEnemyPalette(rp, entry.speciesId)
    }
    val gfxBlock = remember(entry.speciesId) {
        EnemySpriteGraphics.readGraphicsBlock(rp, entry.speciesId)
    }

    val tileData = remember(entry.speciesId, refreshKey) {
        editorState.loadEnemyTileData(rp, entry.speciesId)
    }

    val assembledSprite = remember(entry.speciesId, refreshKey) {
        val pal = palette ?: return@remember null
        val td = tileData ?: return@remember null
        val smap = EnemySpritemap(rp)
        val defaultSmap = smap.findDefaultSpritemap(entry.speciesId) ?: return@remember null
        val result = smap.renderSpritemap(defaultSmap, td, pal) ?: return@remember null
        // Quality check: skip if the sprite is mostly empty (< 5% non-transparent pixels).
        // This catches garbage spritemaps from boss sub-entities whose init AI doesn't
        // follow standard patterns and yields wrong tile references.
        val totalPixels = result.width * result.height
        val nonTransparent = result.pixels.count { (it ushr 24) > 0 }
        if (totalPixels > 64 && nonTransparent < totalPixels * 5 / 100) return@remember null
        result
    }

    val tileSheet = remember(entry.speciesId, refreshKey) {
        val pal = palette ?: return@remember null
        val td = tileData ?: return@remember null
        val gfx = EnemySpriteGraphics(rp)
        gfx.loadFromRaw(listOf(td))
        gfx.renderSheet(pal, 16)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            entry.name,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Species info
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Species Info", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface)
                Divider(modifier = Modifier.padding(vertical = 2.dp))

                val hexId = entry.speciesId.toString(16).uppercase().padStart(4, '0')
                InfoRow("Species ID", "\$A0:$hexId")

                if (stats != null) {
                    val (tileSize, hp, damage) = stats
                    InfoRow("HP", "$hp")
                    InfoRow("Damage", "$damage")
                    InfoRow("Tile Data Size", "$tileSize bytes (${tileSize / 32} tiles)")
                }

                if (gfxBlock != null) {
                    val snesHex = "\$${(gfxBlock.snesAddress shr 16).toString(16).uppercase()}:" +
                        "${(gfxBlock.snesAddress and 0xFFFF).toString(16).uppercase().padStart(4, '0')}"
                    InfoRow("GFX Address", snesHex)
                }
            }
        }

        // Palette display
        if (palette != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Palette [ROM]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (i in 0 until 16) {
                            val argb = palette[i]
                            val r = (argb shr 16) and 0xFF
                            val g = (argb shr 8) and 0xFF
                            val b = argb and 0xFF
                            val a = (argb ushr 24) and 0xFF
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (a < 128) MaterialTheme.colorScheme.surface
                                        else Color(r / 255f, g / 255f, b / 255f)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Enemy animation (OAM instruction list frames)
        val scope = rememberCoroutineScope()
        val enemyAnimation = remember(entry.speciesId, refreshKey) {
            val pal = palette ?: return@remember null
            val td = tileData ?: return@remember null
            val smap = EnemySpritemap(rp)
            smap.buildAnimation(entry.speciesId, td, pal, entry.name)
        }

        if (enemyAnimation != null && enemyAnimation.frames.size > 1) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Animation (${enemyAnimation.frames.size} frames)", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    AnimationPlayer(
                        animation = enemyAnimation,
                        previewSize = 192,
                        onExportPng = { frame, idx ->
                            scope.launch(Dispatchers.IO) {
                                enemyExportFramePng(frame, "${entry.name.lowercase().replace(' ', '_')}_frame$idx")
                            }
                        },
                        onExportGif = { anim ->
                            scope.launch(Dispatchers.IO) {
                                enemyExportAnimationGif(anim, entry.name.lowercase().replace(' ', '_'))
                            }
                        },
                        onExportSheet = { anim ->
                            scope.launch(Dispatchers.IO) {
                                enemyExportSheet(anim, "${entry.name.lowercase().replace(' ', '_')}_sheet")
                            }
                        }
                    )
                }
            }
        }

        // Assembled sprite preview (OAM spritemap assembly)
        if (assembledSprite != null) {
            val scale = maxOf(4, minOf(8, 128 / maxOf(assembledSprite.width, assembledSprite.height)))
            val dispW = assembledSprite.width * scale
            val dispH = assembledSprite.height * scale
            val bitmap = remember(assembledSprite) {
                val img = BufferedImage(assembledSprite.width, assembledSprite.height, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, assembledSprite.width, assembledSprite.height, assembledSprite.pixels, 0, assembledSprite.width)
                img.toComposeImageBitmap()
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sprite [Assembled]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    val checkerSize = 4
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(dispW.dp, dispH.dp)) {
                            for (cy in 0 until dispH step checkerSize) {
                                for (cx in 0 until dispW step checkerSize) {
                                    val isLight = ((cx / checkerSize) + (cy / checkerSize)) % 2 == 0
                                    drawRect(
                                        color = if (isLight) Color(0xFF3A3A4A) else Color(0xFF2A2A3A),
                                        topLeft = Offset(cx.toFloat(), cy.toFloat()),
                                        size = Size(checkerSize.toFloat(), checkerSize.toFloat())
                                    )
                                }
                            }
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = "${entry.name} Assembled Sprite",
                            modifier = Modifier.size(dispW.dp, dispH.dp),
                            filterQuality = FilterQuality.None
                        )
                    }
                    Text("${assembledSprite.width}×${assembledSprite.height}px • ${assembledSprite.spritemap.entries.size} OAM entries",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Tile sheet preview with Edit button
        if (tileSheet != null) {
            val (pixels, w, h) = tileSheet
            val hasCustom = editorState.hasCustomEnemyTiles(entry.speciesId)
            val bitmap = remember(pixels, w, h) {
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, w, h, pixels, 0, w)
                img.toComposeImageBitmap()
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tile Sheet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Surface(
                                color = if (hasCustom) Color(0xFF333366) else Color(0xFF336633),
                                shape = RoundedCornerShape(3.dp)
                            ) {
                                Text(
                                    if (hasCustom) "CUSTOM" else "ROM",
                                    fontSize = 7.sp,
                                    color = if (hasCustom) Color(0xFF8888FF) else Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = {
                                    val pal = palette ?: return@Button
                                    // Build reference bitmap from assembled sprite
                                    val refBitmap = assembledSprite?.let { sprite ->
                                        val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
                                        img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
                                        img.toComposeImageBitmap()
                                    }
                                    editingPixels = pixels.copyOf()
                                    editingWidth = w
                                    editingHeight = h
                                    editingPalette = pal
                                    editingReference = refBitmap
                                },
                                modifier = Modifier.height(28.dp),
                                contentPadding = ButtonDefaults.ContentPadding.let {
                                    androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                }
                            ) {
                                Text("Edit Tiles", fontSize = 10.sp)
                            }
                            if (hasCustom) {
                                Button(
                                    onClick = {
                                        editorState.resetEnemyTiles(entry.speciesId)
                                        refreshKey++
                                    },
                                    modifier = Modifier.height(28.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    ),
                                    contentPadding = ButtonDefaults.ContentPadding.let {
                                        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    }
                                ) {
                                    Text("Reset", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    val checkerSize = 4
                    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp))) {
                        Canvas(modifier = Modifier.size((w * 4).dp, (h * 4).dp)) {
                            for (cy in 0 until (h * 4) step checkerSize) {
                                for (cx in 0 until (w * 4) step checkerSize) {
                                    val isLight = ((cx / checkerSize) + (cy / checkerSize)) % 2 == 0
                                    drawRect(
                                        color = if (isLight) Color(0xFF3A3A4A) else Color(0xFF2A2A3A),
                                        topLeft = Offset(cx.toFloat(), cy.toFloat()),
                                        size = Size(checkerSize.toFloat(), checkerSize.toFloat())
                                    )
                                }
                            }
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = "${entry.name} Tile Sheet",
                            modifier = Modifier.size((w * 4).dp, (h * 4).dp),
                            filterQuality = FilterQuality.None
                        )
                    }
                    Text("${w}×${h}px • ${(w / 8) * (h / 8)} tiles",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Could not load tile data",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("The GRAPHADR field in this enemy's species header may be invalid.",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        lineHeight = 14.sp)
                }
            }
        }
    }
}

// ─── Enemy export helpers ────────────────────────────────────────────

private fun enemyExportFramePng(frame: SpriteAnimationFrame, defaultName: String) {
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

private fun enemyExportAnimationGif(animation: SpriteAnimation, defaultName: String) {
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

private fun enemyExportSheet(animation: SpriteAnimation, defaultName: String) {
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp))
        Text(value, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}
