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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.KraidSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage

private enum class KraidTab { COMPONENTS, ANIMATIONS, TILE_SHEET, OAM_TILES }

private sealed class KraidComponent(val displayName: String) {
    data class FullBody(val head: KraidSpritemap.HeadTilemapDef) :
        KraidComponent("Full body · ${head.name.substringAfter("· ")}")
    data class Head(val def: KraidSpritemap.HeadTilemapDef) :
        KraidComponent("Tilemap source · ${def.name.substringAfter("· ")}")
    data class OamEntity(
        val speciesId: Int,
        val label: String,
        val representativeSequenceKey: String,
    ) : KraidComponent(label)
}

private sealed class KraidAnimationChoice(val displayName: String) {
    data class Head(val def: KraidSpritemap.HeadSequenceDef) : KraidAnimationChoice(def.name)
    data class Oam(val def: KraidSpritemap.OamSequenceDef) : KraidAnimationChoice(def.name)
}

/** Source-named OAM entities that share the exact $AB:CC00..EA00 graphics range. */
private val KRAID_OAM_ENTITIES = listOf(
    KraidComponent.OamEntity(0xE2FF, "Arm", "arm-normal"),
    KraidComponent.OamEntity(0xE33F, "Lint · top", "lint-big"),
    KraidComponent.OamEntity(0xE37F, "Lint · middle", "lint-big"),
    KraidComponent.OamEntity(0xE3BF, "Lint · bottom", "lint-big"),
    KraidComponent.OamEntity(0xE3FF, "Foot", "foot-neutral"),
    KraidComponent.OamEntity(0xE43F, "Nail", "nail"),
    KraidComponent.OamEntity(0xE47F, "Nail · bad trajectory", "nail"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KraidSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(KraidTab.COMPONENTS) }
    var selectedComponent by remember { mutableStateOf<KraidComponent>(
        KraidComponent.FullBody(KraidSpritemap.HEAD_TILEMAPS[0])
    ) }

    var editingPixels by remember { mutableStateOf<IntArray?>(null) }
    var editingSprite by remember { mutableStateOf<KraidSpritemap.AssembledSprite?>(null) }
    var editingPalette by remember { mutableStateOf<IntArray?>(null) }
    var editingLabel by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    editingPixels?.let { pixels ->
        val sprite = editingSprite
        SpritePixelEditor(
            label = editingLabel,
            initialPixels = pixels,
            imageWidth = sprite?.width ?: 0,
            imageHeight = sprite?.height ?: 0,
            fixedPalette = editingPalette,
            referenceImage = null,
            onApply = { edited ->
                val rp = romParser
                if (rp != null && sprite != null) editorState.applyKraidHeadEdits(rp, sprite, edited)
                refreshKey++
            },
            onClose = {
                editingPixels = null
                editingSprite = null
                editingPalette = null
            },
            modifier = modifier,
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
                FilterChip(
                    selected = activeTab == KraidTab.ANIMATIONS,
                    onClick = { activeTab = KraidTab.ANIMATIONS },
                    label = { Text("Animations", fontSize = 10.sp) },
                    modifier = Modifier.height(28.dp)
                )
                FilterChip(
                    selected = activeTab == KraidTab.TILE_SHEET,
                    onClick = { activeTab = KraidTab.TILE_SHEET },
                    label = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("BG2 Source", fontSize = 10.sp)
                            Surface(color = Color(0xFF336633), shape = RoundedCornerShape(3.dp)) {
                                Text("EXACT", fontSize = 7.sp, color = Color(0xFF88FF88),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                            if (editorState.hasCustomKraidComponents() || editorState.hasCustomKraidTileSheet()) {
                                Text("\u25CF", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    modifier = Modifier.height(28.dp)
                )
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
                refreshKey = refreshKey,
                onEditHead = { component ->
                    val rp = romParser ?: return@KraidComponentsTab
                    val sprite = editorState.renderKraidHeadTilemap(rp, component.def)
                        ?: return@KraidComponentsTab
                    editingPixels = sprite.pixels.copyOf()
                    editingSprite = sprite
                    editingPalette = editorState.getKraidPalette(rp)
                    editingLabel = "Kraid · ${component.displayName}"
                },
                modifier = Modifier.weight(1f)
            )

            KraidTab.ANIMATIONS -> KraidAnimationsTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                modifier = Modifier.weight(1f),
            )

            KraidTab.TILE_SHEET -> KraidTileSheetTab(
                editorState = editorState,
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
    is KraidComponent.FullBody -> editorState.renderKraidFullBody(romParser, comp.head)
    is KraidComponent.Head -> editorState.renderKraidHeadTilemap(romParser, comp.def)
    is KraidComponent.OamEntity -> renderKraidOamEntity(editorState, romParser, comp)
}

private fun renderKraidOamEntity(
    editorState: EditorState,
    romParser: RomParser,
    comp: KraidComponent.OamEntity
): KraidSpritemap.AssembledSprite? {
    val tileData = editorState.loadEnemyTileData(romParser, comp.speciesId) ?: return null
    val definition = KraidSpritemap.OAM_SEQUENCES.firstOrNull {
        it.key == comp.representativeSequenceKey
    } ?: return null
    val frame = KraidSpritemap(romParser)
        .renderOamAnimation(definition, tileData)
        ?.frames
        ?.firstOrNull()
        ?: return null
    return KraidSpritemap.AssembledSprite(
        name = comp.label,
        width = frame.width,
        height = frame.height,
        pixels = frame.pixels,
        entries = emptyList(),
        tilesCols = (frame.width + 7) / 8,
        tilesRows = (frame.height + 7) / 8,
    )
}

@Composable
private fun KraidComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    selectedComponent: KraidComponent,
    onSelectComponent: (KraidComponent) -> Unit,
    refreshKey: Int,
    onEditHead: (KraidComponent.Head) -> Unit,
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
            Text("Room: \$A59F · Tileset: \$1A · AI: \$A7", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
            Text("BG2 is the full body. Arm, foot, lints, and nails are separately drawn OAM.",
                fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
            Divider()
            Spacer(Modifier.height(4.dp))

            Text("Full BG2 Body States", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            KraidSpritemap.HEAD_TILEMAPS.forEach { def ->
                KraidComponentItem(KraidComponent.FullBody(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
            }

            Spacer(Modifier.height(8.dp))
            Text("Editable Head Tilemaps", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            KraidSpritemap.HEAD_TILEMAPS.forEach { def ->
                KraidComponentItem(KraidComponent.Head(def), selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
            }

            Spacer(Modifier.height(8.dp))
            Divider()
            Text("Linked OAM Components", fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            KRAID_OAM_ENTITIES.forEach { entity ->
                KraidComponentItem(entity, selectedComponent, editorState, romParser, refreshKey, onSelectComponent)
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

        KraidComponentDetail(
            comp = selectedComponent,
            editorState = editorState,
            romParser = romParser,
            refreshKey = refreshKey,
            onEditHead = onEditHead,
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
                    is KraidComponent.FullBody -> "\$B9:FA38 + \$B9:FE3E + \$${comp.head.snesAddr.toString(16).uppercase()}"
                    is KraidComponent.Head -> "\$${comp.def.snesAddr.toString(16).uppercase()}"
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
            sprite.toPreviewBitmap()
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
    onEditHead: (KraidComponent.Head) -> Unit,
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
                    is KraidComponent.FullBody -> "BG2 maps: \$B9:FA38 + \$B9:FE3E (64×64); head \$${comp.head.snesAddr.toString(16).uppercase()}"
                    is KraidComponent.Head -> "Head tilemap: \$${comp.def.snesAddr.toString(16).uppercase()} (${comp.def.cols}×${comp.def.visibleRows} copied; ${comp.def.storedRows} stored)"
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
            if (comp is KraidComponent.Head) {
                Button(
                    onClick = { onEditHead(comp) },
                    enabled = romParser != null,
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
                Text("Source-backed full-body composition: the two upper and two lower BG2 screen " +
                    "blocks with ${comp.head.name} copied over the upper-left block exactly as the engine does.",
                    fontSize = 9.sp, color = Color(0xFF88CC88),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }

        if (comp is KraidComponent.Head) {
            Surface(
                color = Color(0xFF1A2A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Editing writes the complete 32 KiB Tiles_1A_Kraid resource through the normal " +
                    "tileset relocation path. Shared tiles update everywhere tileset \$1A uses them; " +
                    "the stored twelfth row is intentionally not rendered by the game.",
                    fontSize = 9.sp, color = Color(0xFF88CC88),
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
                    "Its representative pose comes from the exact ${comp.representativeSequenceKey} list. " +
                    "Uses shared tile sheet at \$AB:CC00 (240 tiles). " +
                    "This is separate from the BG2/tileset resource.",
                    fontSize = 9.sp, color = Color(0xFF8888CC),
                    lineHeight = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        } else if (comp is KraidComponent.FullBody) {
            Surface(
                color = Color(0xFF1A2A1A),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Preview framing trims transparent padding only; Export PNG keeps the exact " +
                    "512×512 canvas. Edit a named head frame or open tileset \$1A for room-wide work.",
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
                spriteBitmap = sprite?.toPreviewBitmap()
                spriteLoaded = true
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
                        contentDescription = "Kraid ${comp.displayName}",
                        modifier = Modifier
                            .fillMaxSize(0.86f)
                            .clip(RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KraidAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selectedChoice by remember {
        mutableStateOf<KraidAnimationChoice>(KraidAnimationChoice.Head(KraidSpritemap.HEAD_SEQUENCES.first()))
    }
    var selectedPalette by remember {
        mutableStateOf(KraidSpritemap.PALETTE_STAGES.first { it.key == "health-8" })
    }

    val sourceAnimation by produceState<SpriteAnimation?>(
        initialValue = null,
        selectedChoice,
        selectedPalette,
        refreshKey,
        romParser,
    ) {
        val rp = romParser
        value = if (rp == null) {
            null
        } else {
            when (val choice = selectedChoice) {
                is KraidAnimationChoice.Head ->
                    editorState.renderKraidFullBodyAnimation(rp, choice.def, selectedPalette)
                is KraidAnimationChoice.Oam ->
                    editorState.renderKraidOamAnimation(rp, choice.def, selectedPalette)
            }
        }
    }
    val previewAnimation = remember(sourceAnimation) {
        sourceAnimation?.cropToVisibleArtwork()
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
            KraidSpritemap.PALETTE_STAGES.forEach { stage ->
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
                modifier = Modifier.width(230.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Full BG2 Body", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                KraidSpritemap.HEAD_SEQUENCES.forEach { def ->
                    val choice = KraidAnimationChoice.Head(def)
                    KraidAnimationChoiceItem(choice, selectedChoice) { selectedChoice = choice }
                }

                Spacer(Modifier.height(8.dp))
                Text("Linked OAM", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                KraidSpritemap.OAM_SEQUENCES.forEach { def ->
                    val choice = KraidAnimationChoice.Oam(def)
                    KraidAnimationChoiceItem(choice, selectedChoice) { selectedChoice = choice }
                }
            }

            Divider(modifier = Modifier.fillMaxHeight().width(1.dp))

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(selectedChoice.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val sourceText = when (val choice = selectedChoice) {
                    is KraidAnimationChoice.Head ->
                        "Custom BG2 list \$${choice.def.snesAddr.toString(16).uppercase()}–\$${choice.def.endSnesAddrExclusive.toString(16).uppercase()}"
                    is KraidAnimationChoice.Oam ->
                        "${if (choice.def.extended) "Extended" else "Standard"} OAM list \$${choice.def.snesAddr.toString(16).uppercase()}–\$${choice.def.endSnesAddrExclusive.toString(16).uppercase()}"
                }
                Text(sourceText, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (selectedChoice is KraidAnimationChoice.Head) {
                        "Complete 512×512 BG2 body; only the engine-selected head tilemap changes per frame."
                    } else {
                        "Independently drawn OAM entity using the shared 240-tile \$AB:CC00 sheet."
                    },
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimationPlayer(
                    animation = previewAnimation,
                    previewSize = 420,
                    showExportButtons = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private data class PreviewBounds(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top
}

/** Crop transparent padding for display only; source/export coordinates remain untouched. */
private fun KraidSpritemap.AssembledSprite.toPreviewBitmap(padding: Int = 6): ImageBitmap {
    val bounds = visibleBounds(listOf(pixels), width, height, padding)
        ?: PreviewBounds(0, 0, width, height)
    val previewPixels = cropPixels(pixels, width, bounds)
    return BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB).also { image ->
        image.setRGB(0, 0, bounds.width, bounds.height, previewPixels, 0, bounds.width)
    }.toComposeImageBitmap()
}

/** Use one union crop for the whole animation so frames never jump or change scale. */
private fun SpriteAnimation.cropToVisibleArtwork(padding: Int = 6): SpriteAnimation {
    val first = frames.firstOrNull() ?: return this
    if (frames.any { it.width != first.width || it.height != first.height }) return this
    val bounds = visibleBounds(frames.map { it.pixels }, first.width, first.height, padding)
        ?: return this
    if (bounds.width == first.width && bounds.height == first.height) return this
    return copy(
        frames = frames.map { frame ->
            frame.copy(
                pixels = cropPixels(frame.pixels, frame.width, bounds),
                width = bounds.width,
                height = bounds.height,
            )
        }
    )
}

private fun visibleBounds(
    frames: List<IntArray>,
    width: Int,
    height: Int,
    padding: Int,
): PreviewBounds? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    frames.forEach { pixels ->
        pixels.forEachIndexed { index, pixel ->
            if ((pixel ushr 24) == 0) return@forEachIndexed
            val x = index % width
            val y = index / width
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return null
    return PreviewBounds(
        left = (minX - padding).coerceAtLeast(0),
        top = (minY - padding).coerceAtLeast(0),
        rightExclusive = (maxX + padding + 1).coerceAtMost(width),
        bottomExclusive = (maxY + padding + 1).coerceAtMost(height),
    )
}

private fun cropPixels(
    source: IntArray,
    sourceWidth: Int,
    bounds: PreviewBounds,
): IntArray = IntArray(bounds.width * bounds.height).also { destination ->
    for (row in 0 until bounds.height) {
        source.copyInto(
            destination = destination,
            destinationOffset = row * bounds.width,
            startIndex = (bounds.top + row) * sourceWidth + bounds.left,
            endIndex = (bounds.top + row) * sourceWidth + bounds.rightExclusive,
        )
    }
}

@Composable
private fun KraidAnimationChoiceItem(
    choice: KraidAnimationChoice,
    selected: KraidAnimationChoice,
    onClick: () -> Unit,
) {
    val isSelected = choice == selected
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(5.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(choice.displayName, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            val detail = when (choice) {
                is KraidAnimationChoice.Head -> "${choice.def.key} · BG2"
                is KraidAnimationChoice.Oam -> "${choice.def.key} · ${if (choice.def.extended) "extended" else "OAM"}"
            }
            Text(detail, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KraidTileSheetTab(
    editorState: EditorState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasLegacy = editorState.hasCustomKraidTileSheet()
    val hasTilesetEdit = editorState.hasCustomKraidComponents()

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
                    "Kraid BG2 Graphics Ownership",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Tiles_1A_Kraid · 32 KiB · 1024 tiles · no CRE overlay",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Head-frame edits use the normal tileset \$1A relocation/export path.",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasTilesetEdit) {
                    Text("● Project has a tileset \$1A graphics override", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (hasLegacy) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            "A legacy Kraid pixel edit is present and will block ROM export",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (hasLegacy) {
                OutlinedButton(
                    onClick = { editorState.resetKraidTileSheet(); onRefresh() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reset Legacy Edit", fontSize = 11.sp) }
            }
        }

        Divider()

        Surface(
            color = Color(0xFF1A2A1A),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Kraid has three distinct graphics resources: the complete tileset \$1A pixels used " +
                    "by BG2; the upper/lower compressed BG2 maps and four ROM head tilemaps that place " +
                    "those pixels; and the linked 240-tile OAM sheet at \$AB:CC00 for the arm, lints, " +
                    "foot, and nails. Component editing changes only the first resource.",
                fontSize = 10.sp,
                color = Color(0xFF88CC88),
                lineHeight = 14.sp,
                modifier = Modifier.padding(12.dp),
            )
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
    // Every source-named Kraid entity resolves to the same exact graphics range.
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
            Text("Shared by Kraid, arm, top/middle/bottom lints, foot, and both nail variants.",
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
