package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.supermetroid.editor.rom.DraygonSpritemap
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.image.BufferedImage

private enum class DraygonEditorTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }
private fun defaultDraygonEditorTab(): DraygonEditorTab = when {
    DraygonSpritemap.ANIMATIONS.isNotEmpty() -> DraygonEditorTab.ANIMATIONS
    DraygonSpritemap.COMPOSITIONS.isNotEmpty() -> DraygonEditorTab.COMPOSITIONS
    else -> DraygonEditorTab.COMPONENTS
}
private enum class DraygonPose(val keySuffix: String, val displayName: String) {
    IDLE("idle", "Idle"),
    LOOK_LEFT("look-left", "Look left"),
    LOOK_RIGHT("look-right", "Look right"),
    LOOK_UP("look-up", "Look up"),
    LOOK_DOWN("look-down", "Look down"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraygonSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(defaultDraygonEditorTab()) }
    var selectedPalette by remember { mutableStateOf(DraygonSpritemap.PALETTE_STAGES.first()) }
    val palettes = remember { DraygonSpritemap.PALETTE_STAGES + DraygonSpritemap.WHITE_FLASH }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<DraygonSpriteEditorState.EditableObjSheet?>(null) }
    var editingPalette by remember { mutableStateOf(DraygonSpritemap.PALETTE_STAGES.first()) }

    editingSheet?.let { sheet ->
        val parser = romParser
        SpritePixelEditor(
            label = "Draygon OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                if (parser == null) emptyList()
                else editorState.renderEditedDraygonObjCompositions(
                    parser,
                    pixels,
                    width,
                    height,
                    editingPalette,
                ).map { it.toBitmap() }
            },
            onApply = { pixels ->
                if (parser != null) {
                    editorState.applyDraygonObjSheetEdits(
                        parser,
                        pixels,
                        sheet.width,
                        sheet.height,
                    )
                    refreshKey++
                }
            },
            onClose = { editingSheet = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Draygon",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                DraygonEditorTab.values().forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = {
                            Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp)
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
                if (romParser == null) {
                    Text(
                        "(load a ROM to view source poses)",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when (activeTab) {
            DraygonEditorTab.COMPONENTS -> DraygonComponentsTab(
                editorState,
                romParser,
                selectedPalette,
                refreshKey,
                { activeTab = DraygonEditorTab.SOURCES },
                Modifier.weight(1f),
            )
            DraygonEditorTab.COMPOSITIONS -> DraygonCompositionsTab(
                editorState,
                romParser,
                selectedPalette,
                palettes,
                { selectedPalette = it },
                refreshKey,
                Modifier.weight(1f),
            )
            DraygonEditorTab.ANIMATIONS -> DraygonAnimationsTab(
                editorState,
                romParser,
                selectedPalette,
                palettes,
                { selectedPalette = it },
                refreshKey,
                Modifier.weight(1f),
            )
            DraygonEditorTab.SOURCES -> DraygonSourcesTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                onEdit = { sheet ->
                    editingPalette = selectedPalette
                    editingSheet = sheet
                },
                onReset = {
                    editorState.resetDraygonObjSheet()
                    refreshKey++
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraygonComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: DraygonSpritemap.PaletteStageDef,
    refreshKey: Int,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(DraygonSpritemap.COMPONENTS.first()) }
    var selectedSide by remember { mutableStateOf(DraygonSpritemap.Side.LEFT) }
    val bitmap by produceState<ImageBitmap?>(null, selected, selectedSide, romParser, palette, refreshKey) {
        value = romParser?.let {
            editorState.renderDraygonComponent(it, selected, selectedSide, palette)?.toBitmap()
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(235.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("Runtime components", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Select one independently positioned runtime slot.",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Facing", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DraygonSpritemap.Side.values().forEach { side ->
                    FilterChip(
                        selected = selectedSide == side,
                        onClick = { selectedSide = side },
                        label = { Text(side.displayName, fontSize = 9.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Divider(modifier = Modifier.padding(vertical = 3.dp))
            DraygonSpritemap.COMPONENTS.forEach { component ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selected = component },
                    color = if (selected == component) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Text(component.name, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "\$${component.speciesId.toString(16).uppercase()} · " +
                                "\$${component.frameSnes(selectedSide).toString(16).uppercase()}",
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "${selected.name} · \$${selected.speciesId.toString(16).uppercase()}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(selected.detail, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Isolated ${selectedSide.displayName.lowercase()} runtime frame · " +
                    "\$${selected.frameSnes(selectedSide).toString(16).uppercase()}",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = Color(0xFF252519),
                shape = RoundedCornerShape(7.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "This frame can combine BG2 and OBJ children, so its flattened pixels are preview-only.",
                        modifier = Modifier.weight(1f),
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        color = Color(0xFFFFDD88),
                    )
                    Button(onClick = onOpenSources) { Text("Open OBJ Source", fontSize = 9.sp) }
                }
            }
            DraygonPreview(bitmap, romParser != null, "Draygon ${selected.name} component", Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraygonCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: DraygonSpritemap.PaletteStageDef,
    palettes: List<DraygonSpritemap.PaletteStageDef>,
    onPaletteSelected: (DraygonSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selectedSide by remember { mutableStateOf(DraygonSpritemap.Side.LEFT) }
    var selectedPose by remember { mutableStateOf(DraygonPose.IDLE) }
    val selected = DraygonSpritemap.COMPOSITIONS.first {
        it.side == selectedSide && it.key.endsWith(selectedPose.keySuffix)
    }
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { parser ->
            editorState.renderDraygonComposition(parser, selected, palette)?.toBitmap()
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(235.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Complete source poses", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Body + eye + tail + arms",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Facing", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DraygonSpritemap.Side.values().forEach { side ->
                    FilterChip(
                        selected = selectedSide == side,
                        onClick = { selectedSide = side },
                        label = { Text(side.displayName, fontSize = 9.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Text("Pose", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            DraygonPose.values().forEach { pose ->
                val isSelected = selectedPose == pose
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selectedPose = pose },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        pose.displayName,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
            Divider(modifier = Modifier.padding(vertical = 5.dp))
            DraygonPalettePicker(palette, palettes, onPaletteSelected)
        }
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Four independent enemy slots assembled at their runtime coordinates",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DraygonPreview(bitmap, romParser != null, "Complete Draygon composition", Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraygonPalettePicker(
    selectedPalette: DraygonSpritemap.PaletteStageDef,
    palettes: List<DraygonSpritemap.PaletteStageDef>,
    onSelected: (DraygonSpritemap.PaletteStageDef) -> Unit,
) {
    Text("Runtime palette", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        palettes.forEach { palette ->
            FilterChip(
                selected = selectedPalette == palette,
                onClick = { onSelected(palette) },
                label = { Text(palette.name, fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DraygonAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: DraygonSpritemap.PaletteStageDef,
    palettes: List<DraygonSpritemap.PaletteStageDef>,
    onPaletteSelected: (DraygonSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selectedSide by remember { mutableStateOf(DraygonSpritemap.Side.LEFT) }
    var selectedPart by remember { mutableStateOf<DraygonSpritemap.AnimationPart?>(null) }
    var selected by remember { mutableStateOf(DraygonSpritemap.ANIMATIONS.first()) }
    val visible = DraygonSpritemap.ANIMATIONS.filter { definition ->
        definition.side == selectedSide &&
            (selectedPart == null || definition.part == selectedPart)
    }
    LaunchedEffect(selectedSide, selectedPart) {
        if (selected !in visible) visible.firstOrNull()?.let { selected = it }
    }
    val animation by produceState<com.supermetroid.editor.rom.SpriteAnimation?>(
        null,
        selected,
        palette,
        romParser,
        refreshKey,
    ) {
        value = romParser?.let { editorState.renderDraygonAnimation(it, selected, palette) }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Facing", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DraygonSpritemap.Side.values().forEach { side ->
                    FilterChip(
                        selected = selectedSide == side,
                        onClick = { selectedSide = side },
                        label = { Text(side.displayName, fontSize = 9.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Text("Animated part", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (listOf<DraygonSpritemap.AnimationPart?>(null) + DraygonSpritemap.AnimationPart.values()).forEach { part ->
                    FilterChip(
                        selected = selectedPart == part,
                        onClick = { selectedPart = part },
                        label = { Text(part?.displayName() ?: "All", fontSize = 8.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            DraygonPalettePicker(palette, palettes, onPaletteSelected)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Source instruction lists · ${visible.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            visible.forEach { definition ->
                val isSelected = selected == definition
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { selected = definition },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(definition.name, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${definition.side.displayName} · ${definition.part.displayName()} · " +
                                "\$${definition.snesAddr.toString(16).uppercase()}",
                            fontSize = 8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "${animation?.frames?.size ?: 0} source frame(s) · " +
                    if (selected.loop) "loops" else "one-shot",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "The selected slot animates while the other three remain in source-valid resting poses.",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimationPlayer(
                animation = animation,
                previewSize = 440,
                showExportButtons = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DraygonSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEdit: (DraygonSpriteEditorState.EditableObjSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheet by produceState<DraygonSpriteEditorState.EditableObjSheet?>(
        null,
        romParser,
        refreshKey,
    ) {
        value = romParser?.let { editorState.loadDraygonObjSheet(it) }
    }
    val hasCustom = editorState.hasCustomDraygonObjSheet()

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pixel sources", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Edit the safely owned OBJ sheet here. Draygon's assembled previews update live while you paint.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Divider()
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceCard(
                    "Enemy OBJ source · editable",
                    "Tiles_Draygon · \$B0:C800 · \$2000 bytes",
                    "The body header owns the full transfer; eye, tail, and arms share these pixels.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { sheet?.let(onEdit) },
                        enabled = sheet != null,
                    ) { Text("Edit OBJ Pixels", fontSize = 10.sp) }
                    if (hasCustom) {
                        OutlinedButton(onClick = onReset) { Text("Reset", fontSize = 10.sp) }
                    }
                }
                if (hasCustom) {
                    Text(
                        "Project override active",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                SourceCard(
                    "Room BG2 source",
                    "Tileset \$1C · Tiles_1C_Draygon · \$BF:9DEA",
                    "Owned by the room tileset and edited through the tileset tools, not this OBJ sheet.",
                )
                SourceCard(
                    "Placement and timing · read-only",
                    "AI bank \$A5 · extended maps and instruction lists",
                    "Compositions and Animations preserve these source-backed coordinates and timings.",
                )
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Enemy OBJ source", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "16 tiles per row · stable full-health editing palette",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        sheet != null -> Image(
                            bitmap = sheet!!.toBitmap(),
                            contentDescription = "Draygon OBJ source tiles",
                            modifier = Modifier.fillMaxSize().padding(10.dp),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                        )
                        romParser != null -> CircularProgressIndicator(Modifier.size(24.dp))
                        else -> Text(
                            "Load a ROM to view and edit this source",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, source: String, detail: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(source, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(detail, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DraygonPreview(
    bitmap: ImageBitmap?,
    romLoaded: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(0.92f).padding(8.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            romLoaded -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text(
                "Load a ROM to view Draygon",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun EnemySpritemap.AssembledSprite.toBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun DraygonSpriteEditorState.EditableObjSheet.toBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun DraygonSpritemap.AnimationPart.displayName(): String = when (this) {
    DraygonSpritemap.AnimationPart.BODY_BASE -> "Body"
    DraygonSpritemap.AnimationPart.BODY_FACE -> "Face overlay"
    DraygonSpritemap.AnimationPart.EYE -> "Eye"
    DraygonSpritemap.AnimationPart.TAIL -> "Tail"
    DraygonSpritemap.AnimationPart.ARMS -> "Arms"
}
