package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.MotherBrainSpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.image.BufferedImage

private enum class MotherBrainTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }
private enum class MotherBrainSource { HEAD, BODY }

private sealed interface MotherBrainComponent {
    val name: String
    data class Head(val definition: MotherBrainSpritemap.HeadDef) : MotherBrainComponent {
        override val name: String = definition.name
    }
    data class Body(val definition: MotherBrainSpritemap.BodyDef) : MotherBrainComponent {
        override val name: String = definition.name
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MotherBrainSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(MotherBrainTab.ANIMATIONS) }
    var selectedPalette by remember { mutableStateOf(MotherBrainSpritemap.PALETTE_STAGES.first()) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<MotherBrainSpriteEditorState.SourceSheet?>(null) }

    val sheet = editingSheet
    if (sheet != null) {
        SpritePixelEditor(
            label = "Mother Brain Head OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                val parser = romParser
                if (parser == null) emptyList() else {
                    editorState.renderEditedMotherBrainHeadCompositions(
                        parser, pixels, width, height, selectedPalette,
                    ).map { it.toMotherBrainBitmap() }
                }
            },
            onApply = { pixels ->
                val parser = romParser
                if (parser != null) {
                    editorState.applyMotherBrainHeadSourceEdits(
                        parser, pixels, sheet.width, sheet.height,
                    )
                    refreshKey++
                }
            },
            onClose = { editingSheet = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mother Brain", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                MotherBrainTab.values().forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp)
                                if (tab == MotherBrainTab.SOURCES && editorState.hasCustomMotherBrainHeadSource()) {
                                    Text("●", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
                if (romParser == null) {
                    Text("(load a ROM to view)", fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (activeTab) {
            MotherBrainTab.ANIMATIONS -> MotherBrainAnimationsTab(
                editorState, romParser, selectedPalette, { selectedPalette = it }, refreshKey,
                Modifier.weight(1f),
            )
            MotherBrainTab.COMPOSITIONS -> MotherBrainCompositionsTab(
                editorState, romParser, selectedPalette, { selectedPalette = it }, refreshKey,
                Modifier.weight(1f),
            )
            MotherBrainTab.COMPONENTS -> MotherBrainComponentsTab(
                editorState, romParser, selectedPalette, { selectedPalette = it }, refreshKey,
                Modifier.weight(1f),
            )
            MotherBrainTab.SOURCES -> MotherBrainSourcesTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                onEdit = { editingSheet = it },
                onReset = {
                    editorState.resetMotherBrainHeadSource()
                    refreshKey++
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MotherBrainAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: MotherBrainSpritemap.PaletteStageDef,
    onPalette: (MotherBrainSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(MotherBrainSpritemap.ANIMATIONS.first()) }
    val animation by produceState<com.supermetroid.editor.rom.SpriteAnimation?>(
        null, romParser, selected, palette, refreshKey,
    ) {
        value = romParser?.let { editorState.renderMotherBrainAnimation(it, selected, palette) }
    }
    Row(modifier.fillMaxSize()) {
        MotherBrainSelectionColumn(
            title = "Animations",
            subtitle = "Source-timed phase 1, phase 2, and phase 3 states.",
            palette = palette,
            onPalette = onPalette,
        ) {
            MotherBrainSpritemap.ANIMATIONS.forEach { definition ->
                MotherBrainChoice(
                    name = definition.name,
                    detail = "${definition.headFrames.size} frames · ${if (definition.loop) "loop" else "one-shot"}",
                    selected = selected == definition,
                    onClick = { selected = definition },
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(selected.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (selected.phase == MotherBrainSpritemap.Phase.PHASE_1)
                    "Phase 1 enemy OAM. The glass case and machinery are room-owned art."
                else "Complete runtime composition: BG2 torso, OAM limbs, five neck segments, and the independent head.",
                fontSize = 10.sp,
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
private fun MotherBrainCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: MotherBrainSpritemap.PaletteStageDef,
    onPalette: (MotherBrainSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(MotherBrainSpritemap.COMPOSITIONS.first { it.key == "phase-2" }) }
    val sprite by produceState<EnemySpritemap.AssembledSprite?>(null, romParser, selected, palette, refreshKey) {
        value = romParser?.let { editorState.renderMotherBrainComposition(it, selected, palette) }
    }
    Row(modifier.fillMaxSize()) {
        MotherBrainSelectionColumn(
            title = "Compositions",
            subtitle = "Representative assembled encounter states.",
            palette = palette,
            onPalette = onPalette,
        ) {
            MotherBrainSpritemap.COMPOSITIONS.forEach { definition ->
                MotherBrainChoice(
                    name = definition.name,
                    detail = if (definition.phase == MotherBrainSpritemap.Phase.PHASE_1)
                        "enemy head · room case excluded" else "full runtime assembly",
                    selected = selected == definition,
                    onClick = { selected = definition },
                )
            }
        }
        MotherBrainPreview(
            bitmap = sprite?.toMotherBrainBitmap(),
            romLoaded = romParser != null,
            description = selected.name,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
        )
    }
}

@Composable
private fun MotherBrainComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: MotherBrainSpritemap.PaletteStageDef,
    onPalette: (MotherBrainSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    val components = remember {
        buildList<MotherBrainComponent> {
            MotherBrainSpritemap.HEAD_COMPONENTS.forEach { add(MotherBrainComponent.Head(it)) }
            MotherBrainSpritemap.BODY_COMPONENTS.forEach { add(MotherBrainComponent.Body(it)) }
        }
    }
    var selected by remember { mutableStateOf<MotherBrainComponent>(components.first()) }
    val sprite by produceState<EnemySpritemap.AssembledSprite?>(null, romParser, selected, palette, refreshKey) {
        value = romParser?.let { parser ->
            when (val component = selected) {
                is MotherBrainComponent.Head -> editorState.renderMotherBrainHead(parser, component.definition, palette)
                is MotherBrainComponent.Body -> editorState.renderMotherBrainBody(parser, component.definition, palette)
            }
        }
    }
    Row(modifier.fillMaxSize()) {
        MotherBrainSelectionColumn(
            title = "Components",
            subtitle = "Independent draw units before runtime assembly.",
            palette = palette,
            onPalette = onPalette,
        ) {
            Text("Head + neck OAM", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            components.filterIsInstance<MotherBrainComponent.Head>()
                .filter { it.definition.index !in 0x0B..0x17 }
                .forEach { component ->
                MotherBrainChoice(component.name, "bank \$A9", selected == component) { selected = component }
            }
            Spacer(Modifier.height(4.dp))
            Text("Body OBJ subparts", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            components.filterIsInstance<MotherBrainComponent.Head>()
                .filter { it.definition.index in 0x0B..0x17 }
                .forEach { component ->
                MotherBrainChoice(component.name, "used inside extended body", selected == component) {
                    selected = component
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Body BG2 + OAM", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            components.filterIsInstance<MotherBrainComponent.Body>().forEach { component ->
                MotherBrainChoice(component.name, "extended spritemap", selected == component) { selected = component }
            }
        }
        MotherBrainPreview(
            bitmap = sprite?.toMotherBrainBitmap(),
            romLoaded = romParser != null,
            description = selected.name,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MotherBrainSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEdit: (MotherBrainSpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(MotherBrainSource.HEAD) }
    val source by produceState<MotherBrainSpriteEditorState.SourceSheet?>(null, romParser, selected, refreshKey) {
        value = romParser?.let {
            if (selected == MotherBrainSource.HEAD) editorState.loadMotherBrainHeadSource(it)
            else editorState.loadMotherBrainBodySource(it)
        }
    }
    val hasCustom = editorState.hasCustomMotherBrainHeadSource()

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pixel sources", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Head OBJ is a safely owned editable source. Phase 2 body is a read-only diagnostic sheet because it combines four owners.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected == MotherBrainSource.HEAD, { selected = MotherBrainSource.HEAD },
                label = { Text("Head OBJ", fontSize = 10.sp) })
            FilterChip(selected == MotherBrainSource.BODY, { selected = MotherBrainSource.BODY },
                label = { Text("Body runtime sources", fontSize = 10.sp) })
            if (selected == MotherBrainSource.HEAD) {
                Button(onClick = { source?.let(onEdit) }, enabled = source != null) {
                    Text("Edit Head Pixels", fontSize = 10.sp)
                }
                if (hasCustom) OutlinedButton(onClick = onReset) { Text("Reset", fontSize = 10.sp) }
            }
        }
        Divider()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected == MotherBrainSource.HEAD) {
                    MotherBrainSourceCard(
                        "Mother Brain head · editable",
                        "Tiles_MotherBrainHead · \$B7:8000 · \$1000 bytes",
                        "Shared by both phases. Pixel edits update phase 1, phase 2, neck, attacks, and corpse frames.",
                    )
                    if (hasCustom) Text("Project override active", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                } else {
                    MotherBrainSourceCard(
                        "Room torso BG · read-only here",
                        "Tileset \$0E · physical tiles \$160..\$1FF",
                        "Owned by Mother Brain's room tileset; edit it with the tileset tools.",
                    )
                    MotherBrainSourceCard(
                        "Head + legs DMA · split ownership",
                        "\$B7:8000 head · \$B7:9000 legs",
                        "Runtime transfers place these banks into separate OBJ pages.",
                    )
                    MotherBrainSourceCard(
                        "Body supplement · enemy-owned",
                        "Tiles_MotherBrainBody · \$B0:E800",
                        "Only one part of the final body; editing the combined diagnostic sheet would corrupt ownership.",
                    )
                }
                MotherBrainSourceCard(
                    "Placement + timing · read-only",
                    "AI bank \$A9",
                    "Extended body maps, head lists, neck geometry, and palette stages drive the assembled views.",
                )
            }
            MotherBrainPreview(
                bitmap = source?.toMotherBrainBitmap(),
                romLoaded = romParser != null,
                description = if (selected == MotherBrainSource.HEAD) "Mother Brain head source" else "Mother Brain runtime sources",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun MotherBrainSelectionColumn(
    title: String,
    subtitle: String,
    palette: MotherBrainSpritemap.PaletteStageDef,
    onPalette: (MotherBrainSpritemap.PaletteStageDef) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.width(250.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("Room \$DD58 · Tileset \$0E · AI \$A9", fontSize = 9.sp,
            color = MaterialTheme.colorScheme.primary)
        Text(subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Divider(Modifier.padding(vertical = 4.dp))
        MotherBrainPalettePicker(palette, onPalette)
        Divider(Modifier.padding(vertical = 4.dp))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MotherBrainPalettePicker(
    selected: MotherBrainSpritemap.PaletteStageDef,
    onSelected: (MotherBrainSpritemap.PaletteStageDef) -> Unit,
) {
    Text("Runtime palette", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        MotherBrainSpritemap.PALETTE_STAGES.forEach { stage ->
            FilterChip(
                selected = selected == stage,
                onClick = { onSelected(stage) },
                label = { Text(stage.name, fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth().height(30.dp),
            )
        }
    }
}

@Composable
private fun MotherBrainChoice(
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(5.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(name, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MotherBrainSourceCard(title: String, source: String, detail: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(source, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(detail, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MotherBrainPreview(
    bitmap: ImageBitmap?,
    romLoaded: Boolean,
    description: String,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap, description,
                modifier = Modifier.fillMaxSize(0.92f).padding(8.dp),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
            romLoaded -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text("Load a ROM to view Mother Brain", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun EnemySpritemap.AssembledSprite.toMotherBrainBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun MotherBrainSpriteEditorState.SourceSheet.toMotherBrainBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}
