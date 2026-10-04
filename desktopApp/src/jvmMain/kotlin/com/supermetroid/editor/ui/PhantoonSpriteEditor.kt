package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.BossSpriteExportSafety
import com.supermetroid.editor.rom.PhantoonSpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage

private enum class SpriteEditorTab { COMPONENTS, COMPOSITIONS, ANIMATIONS, TILE_SHEET }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhantoonSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(SpriteEditorTab.COMPONENTS) }
    var selectedDef by remember { mutableStateOf(PhantoonSpritemap.COMPONENT_TILEMAPS.first()) }

    // Pixel editor state
    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingWidth by remember { mutableStateOf(0) }
    var editingHeight by remember { mutableStateOf(0) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var editingAssembledSprite by remember { mutableStateOf<PhantoonSpritemap.AssembledSprite?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    val ep = editingPixels
    if (ep != null) {
        SpritePixelEditor(
            label = editingLabel,
            initialPixels = ep,
            imageWidth = editingWidth,
            imageHeight = editingHeight,
            fixedPalette = editingPalette,
            referenceImage = null,
            onApply = { pixels ->
                val rp = romParser
                val sprite = editingAssembledSprite
                if (rp != null && sprite != null) {
                    editorState.applyPhantoonComponentEdits(rp, sprite, pixels)
                }
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingPalette = null
                editingAssembledSprite = null
            },
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Phantoon", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = activeTab == SpriteEditorTab.COMPONENTS,
                    onClick = { activeTab = SpriteEditorTab.COMPONENTS },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Components", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTab == SpriteEditorTab.COMPOSITIONS,
                    onClick = { activeTab = SpriteEditorTab.COMPOSITIONS },
                    label = { Text("Compositions", fontSize = 10.sp) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTab == SpriteEditorTab.ANIMATIONS,
                    onClick = { activeTab = SpriteEditorTab.ANIMATIONS },
                    label = { Text("Animations", fontSize = 10.sp) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTab == SpriteEditorTab.TILE_SHEET,
                    onClick = { activeTab = SpriteEditorTab.TILE_SHEET },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Tile Sheet", fontSize = 10.sp)
                            Surface(color = Color(0xFF665522), shape = RoundedCornerShape(3.dp)) {
                                Text("PAUSED", fontSize = 7.sp, color = Color(0xFFFFDD88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (editorState.hasCustomPhantoonTileSheet()) {
                                Text("●", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
                if (romParser == null) {
                    Text("(load a ROM to enable editing)",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (activeTab) {
            SpriteEditorTab.COMPONENTS -> ComponentsTab(
                editorState = editorState,
                romParser = romParser,
                selectedDef = selectedDef,
                onSelectDef = { selectedDef = it },
                refreshKey = refreshKey,
                onEditPixels = { def ->
                    val rp = romParser ?: return@ComponentsTab
                    val sprite = editorState.renderPhantoonComponent(rp, def) ?: return@ComponentsTab
                    editingPixels = sprite.pixels.copyOf()
                    editingWidth = sprite.width
                    editingHeight = sprite.height
                    editingPalette = editorState.getPhantoonPalette(rp)
                    editingLabel = "Phantoon ${def.name}"
                    editingAssembledSprite = sprite
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )

            SpriteEditorTab.COMPOSITIONS -> CompositionsTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                modifier = Modifier.weight(1f),
            )

            SpriteEditorTab.ANIMATIONS -> AnimationsTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                modifier = Modifier.weight(1f),
            )

            SpriteEditorTab.TILE_SHEET -> TileSheetTab(
                editorState = editorState,
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    selectedDef: PhantoonSpritemap.ComponentDef,
    onSelectDef: (PhantoonSpritemap.ComponentDef) -> Unit,
    refreshKey: Int,
    onEditPixels: (PhantoonSpritemap.ComponentDef) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(230.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Room: \$CD13 · Tileset: \$05 · AI: \$A7", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            Text("All 22 active BG2 tilemaps. Editing a tile updates every component that shares it.",
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

            PhantoonSpritemap.ComponentGroup.values().forEach { group ->
                Text(group.displayName, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
                PhantoonSpritemap.COMPONENT_TILEMAPS.filter { it.group == group }.forEach { def ->
                    val isSelected = selectedDef.tilemapSnes == def.tilemapSnes
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onSelectDef(def) },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ComponentThumb(def, editorState, romParser, refreshKey, size = 32)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(def.name.substringAfter("· "), fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface)
                                Text("\$${def.tilemapSnes.toString(16).uppercase()}", fontSize = 9.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        ComponentDetailPanel(
            def = selectedDef,
            editorState = editorState,
            romParser = romParser,
            refreshKey = refreshKey,
            onEditPixels = onEditPixels,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun ComponentDetailPanel(
    def: PhantoonSpritemap.ComponentDef,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditPixels: (PhantoonSpritemap.ComponentDef) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ComponentThumb(def, editorState, romParser, refreshKey, size = 72)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(def.name, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Tilemap: \$${def.tilemapSnes.toString(16).uppercase()}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${def.sourceLabel} · Species \$${def.speciesId}", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Divider()
        Text("Actions", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onEditPixels(def) },
                enabled = romParser != null
            ) { Text("Edit Pixels", fontSize = 11.sp) }

            OutlinedButton(
                onClick = {
                    val rp = romParser ?: return@OutlinedButton
                    val sprite = editorState.renderPhantoonComponent(rp, def) ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, "Export ${def.name} PNG", FileDialog.SAVE)
                    dialog.file = "phantoon_${def.name.lowercase().replace(" ", "_")}.png"
                    dialog.isVisible = true
                    val file = dialog.file
                    if (file != null) {
                        val path = if (file.endsWith(".png", ignoreCase = true)) "${dialog.directory}$file"
                                   else "${dialog.directory}$file.png"
                        val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
                        img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
                        javax.imageio.ImageIO.write(img, "PNG", java.io.File(path))
                    }
                },
                enabled = romParser != null
            ) { Text("Export PNG", fontSize = 11.sp) }
        }

        Surface(
            color = Color(0xFF1A2A1A),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Assembled from ROM tileset tiles via BG2 tilemap. " +
                "Pixel edits write back to the underlying 8x8 tiles \u2014 " +
                "shared tiles update everywhere simultaneously.",
                fontSize = 9.sp, color = Color(0xFF88CC88),
                lineHeight = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        Divider()

        // Large preview of the assembled sprite
        val rp = romParser
        if (rp != null) {
            var spriteBitmap by remember(def.tilemapSnes, refreshKey) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(def.tilemapSnes, refreshKey) {
                val sprite = editorState.renderPhantoonComponent(rp, def)
                spriteBitmap = sprite?.let {
                    val img = BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_ARGB)
                    img.setRGB(0, 0, it.width, it.height, it.pixels, 0, it.width)
                    img.toComposeImageBitmap()
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val bm = spriteBitmap
                if (bm != null) {
                    Image(
                        bitmap = bm,
                        contentDescription = "Phantoon ${def.name}",
                        modifier = Modifier
                            .fillMaxSize(0.86f)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("Load a ROM to view sprites", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ComponentThumb(
    def: PhantoonSpritemap.ComponentDef,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    size: Int
) {
    val bitmap by produceState<ImageBitmap?>(null, def.tilemapSnes, refreshKey) {
        value = try {
            val rp = romParser ?: return@produceState
            val sprite = editorState.renderPhantoonComponent(rp, def) ?: return@produceState
            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            img.toComposeImageBitmap()
        } catch (_: Exception) { null }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "Sprite ${def.name}",
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222244))
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF222244)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = (size / 3).sp, color = Color(0xFF666688))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selectedEyeball by remember { mutableStateOf<PhantoonSpritemap.ComponentDef?>(null) }
    var selectedPalette by remember { mutableStateOf(PhantoonSpritemap.PALETTE_STAGES.last()) }
    val spriteBitmap by produceState<ImageBitmap?>(
        initialValue = null,
        selectedEyeball,
        selectedPalette,
        refreshKey,
        romParser,
    ) {
        value = romParser?.let { parser ->
            editorState.renderPhantoonFullBody(parser, selectedPalette, selectedEyeball)?.let { sprite ->
                val image = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
                image.toComposeImageBitmap()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Runtime Palette", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PhantoonSpritemap.PALETTE_STAGES.forEach { stage ->
                FilterChip(
                    selected = selectedPalette == stage,
                    onClick = { selectedPalette = stage },
                    label = { Text(stage.name, fontSize = 9.sp) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        Divider()

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Complete BG2 Poses", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                (listOf<PhantoonSpritemap.ComponentDef?>(null) + PhantoonSpritemap.EYEBALL_TILEMAPS)
                    .forEach { eyeball ->
                        val selected = selectedEyeball == eyeball
                        val label = eyeball?.name?.substringAfter("· ") ?: "Eye closed"
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { selectedEyeball = eyeball },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            )
                        }
                    }
            }

            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    selectedEyeball?.name?.let { "Full body · ${it.substringAfter("· ")}" }
                        ?: "Full body · eye closed",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Complete 80×112 composition using the parts' shared runtime BG2 coordinates",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = spriteBitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Complete Phantoon composition",
                            modifier = Modifier.fillMaxSize(0.9f).padding(8.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                        )
                    } else if (romParser != null) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    } else {
                        Text(
                            "Load a ROM to view compositions",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selectedAnimation by remember { mutableStateOf(PhantoonSpritemap.ANIMATIONS.first()) }
    var selectedPalette by remember { mutableStateOf(PhantoonSpritemap.PALETTE_STAGES.last()) }
    val animation by produceState<com.supermetroid.editor.rom.SpriteAnimation?>(
        initialValue = null,
        selectedAnimation,
        selectedPalette,
        refreshKey,
        romParser,
    ) {
        value = romParser?.let {
            editorState.renderPhantoonAnimation(it, selectedAnimation, selectedPalette)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Runtime Palette", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            PhantoonSpritemap.PALETTE_STAGES.forEach { stage ->
                FilterChip(
                    selected = selectedPalette == stage,
                    onClick = { selectedPalette = stage },
                    label = { Text(stage.name, fontSize = 9.sp) },
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        Divider()

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(230.dp).fillMaxHeight().padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Source Instruction Lists", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                PhantoonSpritemap.ANIMATIONS.forEach { definition ->
                    val selected = definition == selectedAnimation
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selectedAnimation = definition },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                            Text(definition.name, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "\$${definition.snesAddr.toString(16).uppercase()}–\$${definition.endSnesAddrExclusive.toString(16).uppercase()}",
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(selectedAnimation.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Complete 80×112 BG2 composition · ${animation?.frames?.size ?: 0} source frame(s) · " +
                        if (selectedAnimation.loop) "loops" else "one-shot",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Body, eye, tentacles, and mouth are independent enemy slots; this animates the selected " +
                        "source list while holding the other slots in source-valid resting poses.",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimationPlayer(
                    animation = animation,
                    previewSize = 420,
                    showExportButtons = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TileSheetTab(
    editorState: EditorState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCustom = editorState.hasCustomPhantoonTileSheet()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Phantoon Legacy Tile Sheet",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Incorrect legacy mapping quarantined",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The Components editor remains available and writes through Phantoon's room tileset.",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasCustom) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "A legacy Phantoon tile-sheet edit is present and will block ROM export",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (hasCustom) {
                OutlinedButton(
                    onClick = { editorState.resetPhantoonTileSheet(); onRefresh() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reset Legacy Edit", fontSize = 11.sp) }
            }
        }

        Divider()

        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                BossSpriteExportSafety.PHANTOON_LEGACY_TILE_SHEET_REASON,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                lineHeight = 14.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
