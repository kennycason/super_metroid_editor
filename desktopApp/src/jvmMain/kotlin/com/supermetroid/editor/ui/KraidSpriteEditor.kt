package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.KraidSpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage

private enum class KraidTab { COMPONENTS, TILE_SHEET, OAM_TILES }

private sealed class KraidComponent(val displayName: String) {
    object FullBody : KraidComponent("Full Body (nametable)")
    data class Body(val def: KraidSpritemap.BodyTilemapDef) : KraidComponent(def.name)
    data class BigSprmap(val def: KraidSpritemap.ComponentDef) : KraidComponent(def.name)
    data class OamEntity(val speciesId: Int, val label: String) : KraidComponent(label)
}

/** OAM sub-entities that share the $AB:CC00 tile sheet (claws, spikes, etc.) */
private val KRAID_OAM_ENTITIES = listOf(
    KraidComponent.OamEntity(0xE33F, "Belly Spike 1"),
    KraidComponent.OamEntity(0xE37F, "Belly Spike 2"),
    KraidComponent.OamEntity(0xE3BF, "Belly Spike 3"),
    KraidComponent.OamEntity(0xE3FF, "Flying Claw 1"),
    KraidComponent.OamEntity(0xE43F, "Flying Claw 2"),
    KraidComponent.OamEntity(0xE47F, "Flying Claw 3"),
)

private val BODY_COMPONENTS: List<KraidComponent> = buildList {
    add(KraidComponent.FullBody)
    KraidSpritemap.BODY_TILEMAPS.forEach { add(KraidComponent.Body(it)) }
    KraidSpritemap.BIGSPRMAP_COMPONENTS.forEach { add(KraidComponent.BigSprmap(it)) }
}

private val ALL_COMPONENTS: List<KraidComponent> = BODY_COMPONENTS + KRAID_OAM_ENTITIES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KraidSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    showOamComponents: Boolean = false,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(KraidTab.COMPONENTS) }
    var selectedComponent by remember { mutableStateOf<KraidComponent>(
        KraidComponent.Body(KraidSpritemap.BODY_TILEMAPS[0])
    ) }

    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingWidth by remember { mutableStateOf(0) }
    var editingHeight by remember { mutableStateOf(0) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var editingIsTileSheet by remember { mutableStateOf(false) }
    var editingAssembledSprite by remember { mutableStateOf<KraidSpritemap.AssembledSprite?>(null) }
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
                if (editingIsTileSheet) {
                    editorState.applyKraidTileSheetEdits(pixels, editingWidth, editingHeight)
                } else {
                    val sprite = editingAssembledSprite
                    if (sprite != null) {
                        editorState.applyKraidComponentEdits(sprite, pixels)
                    }
                }
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingPalette = null
                editingIsTileSheet = false
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
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Kraid", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                FilterChip(
                    selected = activeTab == KraidTab.COMPONENTS,
                    onClick = { activeTab = KraidTab.COMPONENTS },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Components", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
                if (!showOamComponents) FilterChip(
                    selected = activeTab == KraidTab.TILE_SHEET,
                    onClick = { activeTab = KraidTab.TILE_SHEET },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Tile Sheet", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (editorState.hasCustomKraidTileSheet()) {
                                Text("\u25CF", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
                if (showOamComponents) {
                    FilterChip(
                        selected = activeTab == KraidTab.OAM_TILES,
                        onClick = { activeTab = KraidTab.OAM_TILES },
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("OAM Tiles", fontSize = 10.sp)
                                Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                    Text("ROM", fontSize = 7.sp, color = Color(0xFF88FF88),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
                if (romParser == null) {
                    Text("(load a ROM to enable editing)",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (activeTab) {
            KraidTab.COMPONENTS -> KraidComponentsTab(
                editorState = editorState,
                romParser = romParser,
                selectedComponent = selectedComponent,
                onSelectComponent = { selectedComponent = it },
                showOamComponents = showOamComponents,
                refreshKey = refreshKey,
                onEditPixels = { comp ->
                    val rp = romParser ?: return@KraidComponentsTab
                    val sprite = renderKraidComponent(editorState, rp, comp) ?: return@KraidComponentsTab
                    editingPixels = sprite.pixels.copyOf()
                    editingWidth = sprite.width
                    editingHeight = sprite.height
                    editingPalette = editorState.getKraidPalette(rp)
                    editingLabel = "Kraid ${comp.displayName}"
                    editingIsTileSheet = false
                    editingAssembledSprite = if (comp is KraidComponent.OamEntity) null else sprite
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )

            KraidTab.TILE_SHEET -> KraidTileSheetTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                onEditTiles = {
                    val rp = romParser ?: return@KraidTileSheetTab
                    val result = editorState.loadKraidTileSheet(rp) ?: return@KraidTileSheetTab
                    val (pixels, w, h) = result
                    editingPixels = pixels
                    editingWidth = w
                    editingHeight = h
                    editingPalette = editorState.getKraidSheetPalette()
                    editingLabel = "Kraid Tile Sheet"
                    editingIsTileSheet = true
                    editingAssembledSprite = null
                },
                onRefresh = { refreshKey++ },
                modifier = Modifier.weight(1f)
            )

            KraidTab.OAM_TILES -> KraidOamTileSheetTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun renderKraidComponent(
    editorState: EditorState,
    romParser: RomParser,
    comp: KraidComponent
): KraidSpritemap.AssembledSprite? = when (comp) {
    is KraidComponent.FullBody -> editorState.renderKraidFullBody(romParser)
    is KraidComponent.Body -> editorState.renderKraidBodyTilemap(romParser, comp.def)
    is KraidComponent.BigSprmap -> editorState.renderKraidBigSprmap(romParser, comp.def)
    is KraidComponent.OamEntity -> renderKraidOamEntity(editorState, romParser, comp)
}

private fun renderKraidOamEntity(
    editorState: EditorState,
    romParser: RomParser,
    comp: KraidComponent.OamEntity
): KraidSpritemap.AssembledSprite? {
    val palette = EnemySpriteGraphics.readEnemyPalette(romParser, comp.speciesId) ?: return null
    val tileData = editorState.loadEnemyTileData(romParser, comp.speciesId) ?: return null
    val smap = EnemySpritemap(romParser)
    val defaultSmap = smap.findDefaultSpritemap(comp.speciesId) ?: return null
    val oam = smap.renderSpritemap(defaultSmap, tileData, palette) ?: return null
    return KraidSpritemap.AssembledSprite(
        name = comp.label,
        width = oam.width,
        height = oam.height,
        pixels = oam.pixels,
        entries = emptyList(),
        tilesCols = oam.width / 8,
        tilesRows = oam.height / 8
    )
}

@Composable
private fun KraidComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    selectedComponent: KraidComponent,
    onSelectComponent: (KraidComponent) -> Unit,
    showOamComponents: Boolean = false,
    refreshKey: Int,
    onEditPixels: (KraidComponent) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showOamComponents) {
                Text("Species: \$E2BF \u00b7 AI: \$A7", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
                Text("Body: 128 BG2 tiles from \$B9:FA38\nOAM: 240 tiles from \$AB:CC00",
                    fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                Divider()
                Spacer(Modifier.height(4.dp))

                Text("Body (BG2 Tilemaps)", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))

                KraidSpritemap.BODY_TILEMAPS.forEach { def ->
                    KraidComponentItem(KraidComponent.Body(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
                }

                Spacer(Modifier.height(8.dp))
                Divider()
                Spacer(Modifier.height(4.dp))
                Text("OAM Sprites", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))

                KRAID_OAM_ENTITIES.forEach { entity ->
                    KraidComponentItem(entity, selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
                }

                if (KraidSpritemap.BIGSPRMAP_COMPONENTS.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(4.dp))
                    Text("Belly Details (BG2)", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))

                    KraidSpritemap.BIGSPRMAP_COMPONENTS.forEach { def ->
                        KraidComponentItem(KraidComponent.BigSprmap(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
                    }
                }
            } else {
                Text("Room: \$A59F \u00b7 Tileset: 27 \u00b7 AI: \$A7", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
                Text("128 tiles from \$B9:FA38. Body rendered via BG2 " +
                    "nametable + body tilemaps for rising states.",
                    fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                Divider()
                Spacer(Modifier.height(4.dp))

                Text("Body Views", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))

                KraidComponentItem(KraidComponent.FullBody, selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
                KraidSpritemap.BODY_TILEMAPS.forEach { def ->
                    KraidComponentItem(KraidComponent.Body(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
                }

                if (KraidSpritemap.BIGSPRMAP_COMPONENTS.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(4.dp))
                    Text("Detail Components", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))

                    KraidSpritemap.BIGSPRMAP_COMPONENTS.forEach { def ->
                        KraidComponentItem(KraidComponent.BigSprmap(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
                    }
                }
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        KraidComponentDetail(
            comp = selectedComponent,
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
private fun KraidComponentItem(
    comp: KraidComponent,
    selected: KraidComponent,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onSelect: (KraidComponent) -> Unit
) {
    val isSelected = comp.displayName == selected.displayName
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(comp) },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KraidThumb(comp, editorState, romParser, refreshKey, size = 28)
            Column(modifier = Modifier.weight(1f)) {
                Text(comp.displayName, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface)
                val addrText = when (comp) {
                    is KraidComponent.FullBody -> "\$B9:FE3E"
                    is KraidComponent.Body -> "\$${comp.def.snesAddr.toString(16).uppercase()}"
                    is KraidComponent.BigSprmap -> "\$${comp.def.tilemapSnes.toString(16).uppercase()}"
                    is KraidComponent.OamEntity -> "\$A0:${comp.speciesId.toString(16).uppercase()}"
                }
                Text(addrText, fontSize = 9.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KraidThumb(
    comp: KraidComponent,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    size: Int
) {
    val bitmap by produceState<ImageBitmap?>(null, comp.displayName, refreshKey) {
        value = try {
            val rp = romParser ?: return@produceState
            val sprite = renderKraidComponent(editorState, rp, comp) ?: return@produceState
            val img = BufferedImage(sprite.width, sprite.height, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, sprite.width, sprite.height, sprite.pixels, 0, sprite.width)
            img.toComposeImageBitmap()
        } catch (_: Exception) { null }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = comp.displayName,
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

@Composable
private fun KraidComponentDetail(
    comp: KraidComponent,
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditPixels: (KraidComponent) -> Unit,
    @Suppress("UNUSED_PARAMETER") onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KraidThumb(comp, editorState, romParser, refreshKey, size = 56)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(comp.displayName, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                val addrText = when (comp) {
                    is KraidComponent.FullBody -> "BG2 Nametable: \$B9:FE3E (32\u00d764)"
                    is KraidComponent.Body -> "Tilemap: \$${comp.def.snesAddr.toString(16).uppercase()} (${comp.def.cols}\u00d7${comp.def.rows})"
                    is KraidComponent.BigSprmap -> "Tilemap: \$${comp.def.tilemapSnes.toString(16).uppercase()}"
                    is KraidComponent.OamEntity -> {
                        val hexId = comp.speciesId.toString(16).uppercase().padStart(4, '0')
                        "OAM Species: \$A0:$hexId"
                    }
                }
                Text(addrText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val speciesText = if (comp is KraidComponent.OamEntity) {
                    val stats = romParser?.let { EnemySpriteGraphics.readSpeciesStats(it, comp.speciesId) }
                    val hp = stats?.second ?: 0
                    val dmg = stats?.third ?: 0
                    "HP: $hp \u00b7 Damage: $dmg \u00b7 OAM tile sheet \$AB:CC00"
                } else {
                    "Species: \$E2BF \u00b7 8 sub-entities"
                }
                Text(speciesText, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Divider()
        Text("Actions", fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (comp !is KraidComponent.OamEntity) {
                Button(
                    onClick = { onEditPixels(comp) },
                    enabled = romParser != null
                ) { Text("Edit Pixels", fontSize = 11.sp) }
            }

            OutlinedButton(
                onClick = {
                    val rp = romParser ?: return@OutlinedButton
                    val sprite = renderKraidComponent(editorState, rp, comp) ?: return@OutlinedButton
                    val dialog = FileDialog(null as Frame?, "Export ${comp.displayName} PNG", FileDialog.SAVE)
                    dialog.file = "kraid_${comp.displayName.lowercase().replace(" ", "_").replace("(", "").replace(")", "")}.png"
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

        if (comp is KraidComponent.FullBody) {
            Surface(
                color = Color(0xFF2A1A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Raw initial BG2 state. In-game, the engine clears this and " +
                    "rebuilds from body tilemaps. Use Body views for accurate renders.",
                    fontSize = 9.sp, color = Color(0xFFCC8888),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        if (comp is KraidComponent.Body && comp.def.name == "Body (full height)") {
            Surface(
                color = Color(0xFF2A2A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Some tiles in this view are loaded dynamically during the " +
                    "fight as Kraid rises. Tiles not present in the static tileset " +
                    "may render incorrectly. Other body views are fully accurate.",
                    fontSize = 9.sp, color = Color(0xFFCCCC88),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        if (comp is KraidComponent.OamEntity) {
            Surface(
                color = Color(0xFF1A1A2A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("OAM sub-entity rendered via spritemap tracing. " +
                    "Uses shared tile sheet at \$AB:CC00 (240 tiles). " +
                    "Edit tiles via the OAM Tiles tab.",
                    fontSize = 9.sp, color = Color(0xFF8888CC),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        } else {
            Surface(
                color = Color(0xFF1A2A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kraid body tiles from \$B9:FA38 (128 tiles, tile index base 0x100). " +
                    "Pixel edits write to the 4bpp tile data and affect all views.",
                    fontSize = 9.sp, color = Color(0xFF88CC88),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        Divider()

        val rp = romParser
        if (rp != null) {
            var spriteBitmap by remember(comp.displayName, refreshKey) { mutableStateOf<ImageBitmap?>(null) }
            var spriteLoaded by remember(comp.displayName, refreshKey) { mutableStateOf(false) }
            LaunchedEffect(comp.displayName, refreshKey) {
                spriteLoaded = false
                val sprite = renderKraidComponent(editorState, rp, comp)
                spriteBitmap = sprite?.let {
                    val img = BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_ARGB)
                    img.setRGB(0, 0, it.width, it.height, it.pixels, 0, it.width)
                    img.toComposeImageBitmap()
                }
                spriteLoaded = true
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF111122), RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopStart
            ) {
                val bm = spriteBitmap
                if (bm != null) {
                    val scale = if (comp is KraidComponent.BigSprmap || comp is KraidComponent.OamEntity) 6 else 2
                    Image(
                        bitmap = bm,
                        contentDescription = "Kraid ${comp.displayName}",
                        modifier = Modifier
                            .padding(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .size((bm.width * scale).dp, (bm.height * scale).dp)
                    )
                } else if (spriteLoaded) {
                    Box(modifier = Modifier.fillMaxSize().defaultMinSize(minHeight = 100.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("No assembly found", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (comp is KraidComponent.OamEntity) {
                                Text("This sub-entity's init AI doesn't follow the standard " +
                                    "spritemap pattern. View tiles in the OAM Tiles tab.",
                                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    lineHeight = 12.sp)
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().defaultMinSize(minHeight = 100.dp),
                        contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
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
private fun KraidTileSheetTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEditTiles: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCustom = editorState.hasCustomKraidTileSheet()

    var sheetBitmap by remember(refreshKey) { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember(refreshKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey, romParser) {
        val rp = romParser
        if (rp == null) {
            loadError = "Load a ROM to view tile sheet"
            return@LaunchedEffect
        }
        sheetBitmap = try {
            val result = editorState.loadKraidTileSheet(rp)
            if (result != null) {
                val (pixels, w, h) = result
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, w, h, pixels, 0, w)
                img.toComposeImageBitmap()
            } else null
        } catch (e: Exception) { loadError = e.message; null }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Kraid Sprite Tile Sheet", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("128 tiles \u00b7 Bank \$B9: FA38 \u00b7 VRAM base 0x0100",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("These tiles form Kraid's body on BG2. Edits are written to the ROM on export.",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasCustom) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)) {
                        Text("Custom tile data active \u2014 will patch ROM on export",
                            fontSize = 9.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End) {
                Button(
                    onClick = onEditTiles,
                    enabled = romParser != null
                ) { Text("Edit Tiles", fontSize = 11.sp) }

                if (hasCustom) {
                    OutlinedButton(
                        onClick = { editorState.resetKraidTileSheet(); onRefresh() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reset to ROM Default", fontSize = 11.sp) }
                }
            }
        }

        Divider()

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFF111122), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.TopStart
        ) {
            when {
                loadError != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(loadError ?: "Error", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                sheetBitmap == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
                else -> {
                    Image(
                        bitmap = sheetBitmap!!,
                        contentDescription = "Kraid tile sheet",
                        modifier = Modifier
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .let { m ->
                                val bm = sheetBitmap!!
                                m.size((bm.width * 4).dp, (bm.height * 4).dp)
                            }
                    )
                }
            }
        }

        val palette = editorState.getKraidSheetPalette()
        if (palette != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Palette:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                palette.forEachIndexed { _, argb ->
                    val alpha = (argb ushr 24) and 0xFF
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (alpha == 0) Color(0xFF444444) else Color(argb))
                            .border(0.5.dp, Color(0x40FFFFFF), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun KraidOamTileSheetTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier
) {
    // Use the first OAM sub-entity to get the shared tile sheet
    val speciesId = KRAID_OAM_ENTITIES.first().speciesId

    var sheetBitmap by remember(refreshKey) { mutableStateOf<ImageBitmap?>(null) }
    var oamPalette by remember(refreshKey) { mutableStateOf<IntArray?>(null) }
    var tileInfo by remember(refreshKey) { mutableStateOf("") }

    LaunchedEffect(refreshKey, romParser) {
        val rp = romParser ?: return@LaunchedEffect
        val palette = EnemySpriteGraphics.readEnemyPalette(rp, speciesId)
        oamPalette = palette
        val tileData = editorState.loadEnemyTileData(rp, speciesId)
        if (palette != null && tileData != null) {
            val tileCount = tileData.size / 32
            tileInfo = "${tileData.size} bytes \u00b7 $tileCount tiles"
            val gfx = EnemySpriteGraphics(rp)
            gfx.loadFromRaw(listOf(tileData))
            val result = gfx.renderSheet(palette, 16)
            if (result != null) {
                val (pixels, w, h) = result
                val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                img.setRGB(0, 0, w, h, pixels, 0, w)
                sheetBitmap = img.toComposeImageBitmap()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Kraid OAM Sprite Tiles", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface)
            Text("GFX Address: \$AB:CC00 \u00b7 $tileInfo",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Shared by all Kraid OAM sub-entities (belly spikes, flying claws).",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Divider()

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFF111122), RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopStart
        ) {
            val bm = sheetBitmap
            if (bm != null) {
                Image(
                    bitmap = bm,
                    contentDescription = "Kraid OAM tile sheet",
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .size((bm.width * 4).dp, (bm.height * 4).dp)
                )
            } else if (romParser == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Load a ROM to view tiles", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().defaultMinSize(minHeight = 100.dp),
                    contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }

        val pal = oamPalette
        if (pal != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Palette:", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                pal.forEachIndexed { _, argb ->
                    val alpha = (argb ushr 24) and 0xFF
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (alpha == 0) Color(0xFF444444) else Color(argb))
                            .border(0.5.dp, Color(0x40FFFFFF), RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}
