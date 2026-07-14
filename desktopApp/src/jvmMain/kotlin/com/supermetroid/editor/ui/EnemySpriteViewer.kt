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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.GifEncoder
import com.supermetroid.editor.rom.BossPoseScanner
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import com.supermetroid.editor.rom.renderSpriteSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var editingReference by remember { mutableStateOf<ImageBitmap?>(null) }
    var editingReferenceFrames by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var editingTileData by remember { mutableStateOf<ByteArray?>(null) }
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
            referenceFrames = editingReferenceFrames,
            liveReferenceFrames = { pixels, width, height ->
                val pal = editingPalette
                val baseTileData = editingTileData
                if (pal == null || baseTileData == null) {
                    emptyList()
                } else {
                    enemyLiveReferenceFrames(
                        rp = rp,
                        speciesId = entry.speciesId,
                        speciesName = entry.name,
                        palette = pal,
                        baseTileData = baseTileData,
                        pixels = pixels,
                        width = width,
                        height = height
                    )
                }
            },
            onApply = { pixels ->
                editorState.applyEnemyTileSheetEdits(rp, entry.speciesId, pixels, editingWidth, editingHeight)
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingPalette = null
                editingReference = null
                editingReferenceFrames = emptyList()
                editingTileData = null
            },
            modifier = modifier
        )
        return
    }

    var paletteRefreshKey by remember { mutableStateOf(0) }
    var editingColorIdx by remember { mutableStateOf(-1) }
    var exportStatus by remember { mutableStateOf("") }

    val stats = remember(entry.speciesId) {
        EnemySpriteGraphics.readSpeciesStats(rp, entry.speciesId)
    }
    val palette = remember(entry.speciesId, paletteRefreshKey) {
        editorState.loadEnemyPalette(rp, entry.speciesId)
    }
    val gfxBlock = remember(entry.speciesId) {
        EnemySpriteGraphics.readGraphicsBlock(rp, entry.speciesId)
    }
    val isMotherBrainBody = entry.speciesId == 0xEC7F
    val usesSpecialAssembledPreview = BossPoseScanner.usesSpecialAssembledPreview(entry.speciesId)
    val usesStaticAssembledPreviewOnly = BossPoseScanner.usesStaticAssembledPreviewOnly(entry.speciesId)

    val tileData = remember(entry.speciesId, refreshKey) {
        editorState.loadEnemyTileData(rp, entry.speciesId)
    }
    val tileValidation = remember(entry.speciesId, refreshKey, tileData) {
        tileData?.let { EnemySpriteGraphics.validateEnemyTileEdit(rp, entry.speciesId, it) }
    }

    val assembledSprite = remember(entry.speciesId, refreshKey, paletteRefreshKey) {
        val pal = palette ?: return@remember null
        val td = tileData ?: return@remember null
        if (usesSpecialAssembledPreview) {
            val renderTileData = EnemySpriteGraphics.loadEnemyRenderTileData(rp, entry.speciesId, td) ?: td
            val scanner = BossPoseScanner(rp)
            val pose = scanner.scanPoses(entry.speciesId, minEntries = 3).firstOrNull() ?: return@remember null
            return@remember scanner.renderPose(pose, renderTileData, pal)
        }

        val smap = EnemySpritemap(rp)
        smap.renderSpecialEnemyPreview(entry.speciesId, td, pal)?.let { return@remember it }
        val defaultSmap = smap.findDefaultSpritemap(entry.speciesId) ?: return@remember null
        val renderTileData = EnemySpriteGraphics.loadStandardOamRenderTileData(rp, entry.speciesId, td) ?: td
        val result = smap.renderSpritemap(defaultSmap, renderTileData, pal) ?: return@remember null
        val totalPixels = result.width * result.height
        val nonTransparent = result.pixels.count { (it ushr 24) > 0 }
        if (totalPixels > 64 && nonTransparent < totalPixels * 5 / 100) return@remember null
        result
    }

    val tileSheet = remember(entry.speciesId, refreshKey, paletteRefreshKey) {
        val pal = palette ?: return@remember null
        val td = tileData ?: return@remember null
        val gfx = EnemySpriteGraphics(rp)
        val sheetTiles = if (isMotherBrainBody) {
            EnemySpriteGraphics.loadMotherBrainBodySourceTileData(rp, td) ?: td
        } else {
            td
        }
        gfx.loadFromRaw(listOf(sheetTiles))
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
        if (exportStatus.isNotEmpty()) {
            Text(
                exportStatus,
                fontSize = 10.sp,
                color = Color(0xFFFFD54F)
            )
        }

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

        // Palette display + editor
        if (palette != null) {
            val hasCustomPal = editorState.hasCustomEnemyPalette(entry.speciesId)
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
                            Text("Palette", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Surface(
                                color = if (hasCustomPal) Color(0xFF333366) else Color(0xFF336633),
                                shape = RoundedCornerShape(3.dp)
                            ) {
                                Text(
                                    if (hasCustomPal) "CUSTOM" else "ROM",
                                    fontSize = 7.sp,
                                    color = if (hasCustomPal) Color(0xFF8888FF) else Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        if (hasCustomPal) {
                            Button(
                                onClick = {
                                    editorState.resetEnemyPalette(entry.speciesId)
                                    editingColorIdx = -1
                                    paletteRefreshKey++
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
                    Divider(modifier = Modifier.padding(vertical = 2.dp))
                    Text("Click a color to edit", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (i in 0 until 16) {
                            val argb = palette[i]
                            val r = (argb shr 16) and 0xFF
                            val g = (argb shr 8) and 0xFF
                            val b = argb and 0xFF
                            val a = (argb ushr 24) and 0xFF
                            val isSelected = editingColorIdx == i
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .clickable {
                                        editingColorIdx = if (editingColorIdx == i) -1 else i
                                    }
                                    .background(
                                        if (a < 128) MaterialTheme.colorScheme.surface
                                        else Color(r / 255f, g / 255f, b / 255f)
                                    )
                                    .border(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) Color.White else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }

                    // HSV color picker for selected color
                    if (editingColorIdx in 1 until 16) {
                        Divider(modifier = Modifier.padding(vertical = 2.dp))
                        Text("Edit Color #$editingColorIdx", fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface)
                        val currentArgb = palette[editingColorIdx]
                        val currentBgr = EnemySpriteGraphics.argbToSnesColor(currentArgb)
                        HsvColorPicker(
                            bgr555 = currentBgr,
                            onColorChanged = { newBgr ->
                                val updatedPal = palette.copyOf()
                                updatedPal[editingColorIdx] = EnemySpriteGraphics.snesColorToArgb(newBgr)
                                editorState.applyEnemyPalette(entry.speciesId, updatedPal)
                                paletteRefreshKey++
                            },
                            modifier = Modifier.width(250.dp)
                        )
                    }
                }
            }
        }

        // Enemy animation (OAM instruction list frames)
        val scope = rememberCoroutineScope()
        val enemyAnimation = remember(entry.speciesId, refreshKey, paletteRefreshKey) {
            val pal = palette ?: return@remember null
            val td = tileData ?: return@remember null
            if (usesStaticAssembledPreviewOnly) {
                null
            } else if (BossPoseScanner.hasKnownPoses(entry.speciesId)) {
                val renderTileData = EnemySpriteGraphics.loadEnemyRenderTileData(rp, entry.speciesId, td) ?: td
                val scanner = BossPoseScanner(rp)
                val frames = scanner.scanPoses(entry.speciesId, minEntries = 3).mapNotNull { pose ->
                    val assembled = scanner.renderPose(pose, renderTileData, pal) ?: return@mapNotNull null
                    SpriteAnimationFrame(
                        pixels = assembled.pixels,
                        width = assembled.width,
                        height = assembled.height,
                        // Static boss instruction-list sleeps can be $7FFF; keep previews responsive.
                        durationTicks = pose.durationTicks.takeIf { it in 1..120 } ?: 8,
                        label = pose.name
                    )
                }
                if (frames.size > 1) {
                    SpriteAnimation("${entry.name} Body Poses", frames, loop = true)
                } else {
                    null
                }
            } else {
                val smap = EnemySpritemap(rp)
                smap.buildSpecialEnemyAnimation(entry.speciesId, td, pal, entry.name)?.let { return@remember it }
                val renderTileData = EnemySpriteGraphics.loadStandardOamRenderTileData(rp, entry.speciesId, td) ?: td
                smap.buildAnimation(entry.speciesId, renderTileData, pal, entry.name)
            }
        }

        if (enemyAnimation != null && enemyAnimation.frames.size > 1) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Animation Preview (${enemyAnimation.frames.size} frames)", fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    AnimationPlayer(
                        animation = enemyAnimation,
                        previewSize = 192,
                        onExportPng = { frame, idx ->
                            val file = chooseEnemyPngFile(
                                dialogTitle = "Save Frame as PNG",
                                defaultName = "${enemyExportSafeName(entry.name)}_frame$idx.png"
                            ) ?: return@AnimationPlayer
                            scope.launch {
                                exportStatus = "Exporting ${file.name}..."
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { enemyExportFramePng(frame, file) }
                                }
                                exportStatus = result.fold(
                                    onSuccess = { "Exported ${file.name}" },
                                    onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" }
                                )
                            }
                        },
                        onExportGif = { anim ->
                            val file = chooseEnemyGifFile(
                                dialogTitle = "Save Animation as GIF",
                                defaultName = "${enemyExportSafeName(entry.name)}.gif"
                            ) ?: return@AnimationPlayer
                            scope.launch {
                                exportStatus = "Exporting ${file.name}..."
                                val result = runCatching {
                                    withContext(Dispatchers.Default) { enemyExportAnimationGif(anim, file) }
                                }
                                exportStatus = result.fold(
                                    onSuccess = { "Exported ${file.name}" },
                                    onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" }
                                )
                            }
                        },
                        onExportSheet = { anim ->
                            val file = chooseEnemyPngFile(
                                dialogTitle = "Save Sprite Sheet as PNG",
                                defaultName = "${enemyExportSafeName(entry.name)}_sheet.png"
                            ) ?: return@AnimationPlayer
                            scope.launch {
                                exportStatus = "Exporting ${file.name}..."
                                val result = runCatching {
                                    withContext(Dispatchers.IO) { enemyExportSheet(anim, file) }
                                }
                                exportStatus = result.fold(
                                    onSuccess = { "Exported ${file.name}" },
                                    onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" }
                                )
                            }
                        }
                    )
                }
            }
        }

        // Boss body poses (scan AI bank for large OAM spritemaps)
        // Show for species with known instruction lists OR enough tiles to be a boss
        val isBoss = !usesStaticAssembledPreviewOnly &&
            !EnemySpritemap.hasSpecialEnemyPreview(entry.speciesId) &&
            (BossPoseScanner.hasKnownPoses(entry.speciesId) ||
                (stats?.let { (tileSize, _, _) -> (tileSize and 0x7FFF) > 2048 } == true))
        if (isBoss) {
            val bossPoses = remember(entry.speciesId, refreshKey, paletteRefreshKey) {
                val pal = palette ?: return@remember emptyList()
                val td = tileData ?: return@remember emptyList()
                val renderTileData = EnemySpriteGraphics.loadEnemyRenderTileData(rp, entry.speciesId, td) ?: td
                val scanner = BossPoseScanner(rp)
                val poses = scanner.scanPoses(entry.speciesId, minEntries = 3)
                poses.mapNotNull { pose ->
                    val rendered = scanner.renderPose(pose, renderTileData, pal) ?: return@mapNotNull null
                    pose to rendered
                }
            }

            if (bossPoses.isNotEmpty()) {
                var selectedPoseIdx by remember { mutableStateOf(0) }
                val safePoseIdx = selectedPoseIdx.coerceIn(0, bossPoses.size - 1)

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Body Pose Preview (${bossPoses.size} found)", fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Divider(modifier = Modifier.padding(vertical = 2.dp))

                        // Pose selector thumbnails
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            for ((pIdx, poseData) in bossPoses.withIndex()) {
                                val (pose, assembled) = poseData
                                val thumbBitmap = remember(assembled) {
                                    val img = BufferedImage(assembled.width, assembled.height, BufferedImage.TYPE_INT_ARGB)
                                    img.setRGB(0, 0, assembled.width, assembled.height, assembled.pixels, 0, assembled.width)
                                    img.toComposeImageBitmap()
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable { selectedPoseIdx = pIdx }
                                        .border(
                                            if (pIdx == safePoseIdx) 2.dp else 1.dp,
                                            if (pIdx == safePoseIdx) Color(0xFFFFD54F) else Color(0xFF3A3F5C),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .background(Color(0xFF2A2A3A), RoundedCornerShape(4.dp))
                                        .padding(2.dp)
                                ) {
                                    Image(
                                        bitmap = thumbBitmap,
                                        contentDescription = pose.name,
                                        modifier = Modifier.size(48.dp),
                                        filterQuality = FilterQuality.None
                                    )
                                    Text(pose.name, fontSize = 6.sp, color = Color(0xFF6A6F88),
                                        maxLines = 1)
                                }
                                if (pIdx >= 11) break // Show max 12 thumbnails
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        // Selected pose preview
                        val (selectedPose, selectedAssembled) = bossPoses[safePoseIdx]
                        val scale = maxOf(2, minOf(6, 256 / maxOf(selectedAssembled.width, selectedAssembled.height)))
                        val dispW = selectedAssembled.width * scale
                        val dispH = selectedAssembled.height * scale
                        val poseBitmap = remember(selectedAssembled) {
                            val img = BufferedImage(selectedAssembled.width, selectedAssembled.height, BufferedImage.TYPE_INT_ARGB)
                            img.setRGB(0, 0, selectedAssembled.width, selectedAssembled.height, selectedAssembled.pixels, 0, selectedAssembled.width)
                            img.toComposeImageBitmap()
                        }

                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            val checkerSize = 4
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
                                bitmap = poseBitmap,
                                contentDescription = selectedPose.name,
                                modifier = Modifier.size(dispW.dp, dispH.dp),
                                filterQuality = FilterQuality.None
                            )
                        }
                        val poseDetailText = if (selectedPose.tilemapCount > 0) {
                            "${selectedPose.entryCount} render tiles, ${selectedPose.childCount} children, " +
                                "${selectedPose.tilemapCount} BG2 tilemaps"
                        } else {
                            "${selectedPose.entryCount} OAM entries"
                        }
                        Text("${selectedPose.name} — $poseDetailText, ${selectedAssembled.width}x${selectedAssembled.height}px",
                            fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    Text("Sprite Preview [Assembled]", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
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
                            Text(
                                if (isMotherBrainBody) "Runtime Tile Sources" else "Tile Sheet",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface)
                            Surface(
                                color = when {
                                    isMotherBrainBody -> Color(0xFF3A3A33)
                                    hasCustom -> Color(0xFF333366)
                                    else -> Color(0xFF336633)
                                },
                                shape = RoundedCornerShape(3.dp)
                            ) {
                                Text(
                                    when {
                                        isMotherBrainBody -> "MULTI-SOURCE"
                                        hasCustom -> "CUSTOM"
                                        else -> "ROM"
                                    },
                                    fontSize = 7.sp,
                                    color = when {
                                        isMotherBrainBody -> Color(0xFFE6DFA6)
                                        hasCustom -> Color(0xFF8888FF)
                                        else -> Color(0xFF88FF88)
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            if (!isMotherBrainBody && tileValidation != null) {
                                Surface(
                                    color = if (tileValidation.isExportable) Color(0xFF2F5C48) else Color(0xFF663333),
                                    shape = RoundedCornerShape(3.dp)
                                ) {
                                    Text(
                                        if (tileValidation.isExportable) "ROM-EXPORT" else "EXPORT BLOCKED",
                                        fontSize = 7.sp,
                                        color = if (tileValidation.isExportable) Color(0xFFA8F0C8) else Color(0xFFFFB4A8),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        if (!isMotherBrainBody) {
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
                                        val refFrames = enemyAnimation?.frames
                                            ?.takeIf { it.size > 1 }
                                            ?.map(::enemyFrameToImageBitmap)
                                            .orEmpty()
                                        editingPixels = pixels.copyOf()
                                        editingWidth = w
                                        editingHeight = h
                                        editingPalette = pal
                                        editingReference = refBitmap ?: refFrames.firstOrNull()
                                        editingReferenceFrames = refFrames
                                        editingTileData = tileData?.copyOf()
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = ButtonDefaults.ContentPadding.let {
                                        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    }
                                ) {
                                    Text("Edit Tiles", fontSize = 10.sp)
                                }
                                Button(
                                    onClick = {
                                        val file = chooseEnemyPngFile(
                                            dialogTitle = "Export Tile Sheet PNG",
                                            defaultName = "${enemyExportSafeName(entry.name)}_tiles.png"
                                        ) ?: return@Button
                                        scope.launch {
                                            exportStatus = "Exporting ${file.name}..."
                                            val result = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    enemyExportPixelsPng(pixels, w, h, file)
                                                }
                                            }
                                            exportStatus = result.fold(
                                                onSuccess = { "Exported ${file.name}" },
                                                onFailure = { err -> "Export failed: ${err.message ?: err::class.simpleName}" }
                                            )
                                        }
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = ButtonDefaults.ContentPadding.let {
                                        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    }
                                ) {
                                    Text("Export Tile PNG", fontSize = 9.sp)
                                }
                                Button(
                                    onClick = {
                                        val file = chooseEnemyOpenPngFile("Import Tile Sheet PNG") ?: return@Button
                                        scope.launch {
                                            exportStatus = "Importing ${file.name}..."
                                            val result = runCatching {
                                                val importedPixels = withContext(Dispatchers.IO) {
                                                    enemyImportPixelsPng(file, expectedWidth = w, expectedHeight = h)
                                                }
                                                editorState.applyEnemyTileSheetEdits(
                                                    rp,
                                                    entry.speciesId,
                                                    importedPixels,
                                                    w,
                                                    h
                                                )
                                            }
                                            exportStatus = result.fold(
                                                onSuccess = {
                                                    refreshKey++
                                                    "Imported ${file.name}"
                                                },
                                                onFailure = { err -> "Import failed: ${err.message ?: err::class.simpleName}" }
                                            )
                                        }
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = ButtonDefaults.ContentPadding.let {
                                        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    }
                                ) {
                                    Text("Import Tile PNG", fontSize = 9.sp)
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
                        } else if (hasCustom) {
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
                                Text("Reset Raw", fontSize = 10.sp)
                            }
                        }
                    }
                    if (isMotherBrainBody) {
                        Text(
                            "MB2 body tiles are split across room tileset \$0E, head DMA \$B7:8000, leg DMA \$B7:9000, and the raw \$EC7F supplement. Generic enemy tile editing is disabled here so it does not patch only the supplement.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            lineHeight = 13.sp
                        )
                    } else if (tileValidation != null) {
                        val statusColor = if (tileValidation.isExportable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                        val address = tileValidation.snesAddress?.let(::enemyFormatSnesAddress) ?: "unknown GRAPHADR"
                        val expectedSize = tileValidation.expectedSize?.let { "$it bytes" } ?: "unknown size"
                        Text(
                            "Raw 4bpp source: ${tileValidation.actualSize} bytes (${tileValidation.tileCount} tiles), expected $expectedSize at $address.",
                            fontSize = 10.sp,
                            color = statusColor,
                            lineHeight = 13.sp
                        )
                        tileValidation.errors.take(2).forEach { message ->
                            Text("Export issue: $message", fontSize = 10.sp, color = MaterialTheme.colorScheme.error, lineHeight = 13.sp)
                        }
                        tileValidation.warnings.take(1).forEach { message ->
                            Text("Note: $message", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f), lineHeight = 13.sp)
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

private fun enemyExportSafeName(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "enemy" }

private fun enemyFormatSnesAddress(address: Int): String =
    "\$${(address ushr 16).toString(16).uppercase().padStart(2, '0')}:" +
        (address and 0xFFFF).toString(16).uppercase().padStart(4, '0')

private fun enemyExportFramePng(frame: SpriteAnimationFrame, file: File) {
    val bi = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
    bi.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    ImageIO.write(bi, "png", file)
}

private fun enemyFrameToImageBitmap(frame: SpriteAnimationFrame): ImageBitmap {
    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    return image.toComposeImageBitmap()
}

private fun enemyAssembledToImageBitmap(sprite: EnemySpritemap.AssembledSprite): ImageBitmap {
    val image = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
    return image.toComposeImageBitmap()
}

private fun enemyLiveReferenceFrames(
    rp: RomParser,
    speciesId: Int,
    speciesName: String,
    palette: IntArray,
    baseTileData: ByteArray,
    pixels: IntArray,
    width: Int,
    height: Int
): List<ImageBitmap> {
    if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()

    val gfx = EnemySpriteGraphics(rp)
    gfx.loadFromRaw(listOf(baseTileData))
    gfx.importFromArgb(pixels, width, height, palette, cols = 16)
    val editedTileData = gfx.getRawBlocks()?.firstOrNull() ?: return emptyList()

    val smap = EnemySpritemap(rp)
    val specialAnimationFrames = smap.buildSpecialEnemyAnimation(speciesId, editedTileData, palette, speciesName)
        ?.frames
        ?.takeIf { it.size > 1 }
        ?.map(::enemyFrameToImageBitmap)
    if (!specialAnimationFrames.isNullOrEmpty()) return specialAnimationFrames

    smap.renderSpecialEnemyPreview(speciesId, editedTileData, palette)
        ?.let { return listOf(enemyAssembledToImageBitmap(it)) }

    if (BossPoseScanner.hasKnownPoses(speciesId)) {
        val renderTileData = EnemySpriteGraphics.loadEnemyRenderTileData(rp, speciesId, editedTileData) ?: editedTileData
        val scanner = BossPoseScanner(rp)
        val poseFrames = scanner.scanPoses(speciesId, minEntries = 3).mapNotNull { pose ->
            scanner.renderPose(pose, renderTileData, palette)?.let(::enemyAssembledToImageBitmap)
        }
        if (poseFrames.isNotEmpty()) return poseFrames
    }

    val renderTileData = EnemySpriteGraphics.loadStandardOamRenderTileData(rp, speciesId, editedTileData) ?: editedTileData
    val animationFrames = smap.buildAnimation(speciesId, renderTileData, palette, speciesName)
        ?.frames
        ?.takeIf { it.size > 1 }
        ?.map(::enemyFrameToImageBitmap)
    if (!animationFrames.isNullOrEmpty()) return animationFrames

    val defaultSmap = smap.findDefaultSpritemap(speciesId) ?: return emptyList()
    val assembled = smap.renderSpritemap(defaultSmap, renderTileData, palette) ?: return emptyList()
    return listOf(enemyAssembledToImageBitmap(assembled))
}

private fun enemyExportPixelsPng(pixels: IntArray, width: Int, height: Int, file: File) {
    require(pixels.size == width * height) {
        "Pixel buffer has ${pixels.size} pixels, expected ${width * height}"
    }
    val bi = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    bi.setRGB(0, 0, width, height, pixels, 0, width)
    ImageIO.write(bi, "png", file)
}

private fun enemyImportPixelsPng(file: File, expectedWidth: Int, expectedHeight: Int): IntArray {
    val image = ImageIO.read(file) ?: error("Could not read PNG")
    if (image.width != expectedWidth || image.height != expectedHeight) {
        error("Expected ${expectedWidth}x${expectedHeight}px, got ${image.width}x${image.height}px")
    }
    return image.getRGB(0, 0, expectedWidth, expectedHeight, null, 0, expectedWidth)
}

private fun enemyExportAnimationGif(animation: SpriteAnimation, file: File) {
    val gifBytes = GifEncoder.encode(animation.frames)
    file.writeBytes(gifBytes)
}

private fun enemyExportSheet(animation: SpriteAnimation, file: File) {
    val (pixels, w, h) = renderSpriteSheet(animation.frames, columns = 8)
    if (w > 0 && h > 0) {
        val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        bi.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(bi, "png", file)
    }
}

private fun chooseEnemyPngFile(dialogTitle: String, defaultName: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        selectedFile = File(defaultName)
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.let {
        if (!it.name.endsWith(".png", ignoreCase = true)) File(it.parentFile, "${it.name}.png") else it
    }
}

private fun chooseEnemyOpenPngFile(dialogTitle: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        fileFilter = FileNameExtensionFilter("PNG Images", "png")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun chooseEnemyGifFile(dialogTitle: String, defaultName: String): File? {
    val chooser = JFileChooser().apply {
        this.dialogTitle = dialogTitle
        selectedFile = File(defaultName)
        fileFilter = FileNameExtensionFilter("GIF Images", "gif")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.let {
        if (!it.name.endsWith(".gif", ignoreCase = true)) File(it.parentFile, "${it.name}.gif") else it
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
