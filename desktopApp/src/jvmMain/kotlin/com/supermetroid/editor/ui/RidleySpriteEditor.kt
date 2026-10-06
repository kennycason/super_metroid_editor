@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.supermetroid.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.supermetroid.editor.rom.RidleySpritemap
import com.supermetroid.editor.rom.RomParser
import java.awt.image.BufferedImage

private enum class RidleyTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }

private sealed interface RidleyComponent {
    val name: String
    data class Body(val definition: RidleySpritemap.BodyDef) : RidleyComponent {
        override val name = definition.name
    }
    data class Wing(val definition: RidleySpritemap.WingDef) : RidleyComponent {
        override val name = definition.name
    }
    data class Tail(val definition: RidleySpritemap.OamComponentDef) : RidleyComponent {
        override val name = definition.name
    }
}

@Composable
fun RidleySpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(RidleyTab.ANIMATIONS) }
    var selectedPalette by remember { mutableStateOf(RidleySpritemap.PALETTE_STAGES.first()) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<RidleySpriteEditorState.SourceSheet?>(null) }

    val edit = editingSheet
    if (edit != null) {
        SpritePixelEditor(
            label = "Ridley OBJ Source",
            initialPixels = edit.pixels,
            imageWidth = edit.width,
            imageHeight = edit.height,
            fixedPalette = edit.palette,
            liveReferenceFrames = { pixels, width, height ->
                val parser = romParser
                if (parser == null) emptyList() else {
                    editorState.renderEditedRidleyCompositions(
                        parser, pixels, width, height, selectedPalette,
                    ).map { it.toRidleyBitmap() }
                }
            },
            onApply = { pixels ->
                val parser = romParser
                if (parser != null) {
                    editorState.applyRidleySourceEdits(parser, pixels, edit.width, edit.height)
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
                Text("Ridley", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("Norfair + Ceres · shared graphics", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                RidleyTab.values().forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp)
                                if (tab == RidleyTab.SOURCES && editorState.hasCustomRidleySource()) {
                                    Text("●", fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }
                if (romParser == null) Text("(load a ROM to view)", fontSize = 9.sp)
            }
        }

        when (activeTab) {
            RidleyTab.ANIMATIONS -> RidleyAnimationsTab(
                editorState, romParser, selectedPalette, { selectedPalette = it }, refreshKey,
                Modifier.weight(1f),
            )
            RidleyTab.COMPOSITIONS -> RidleyCompositionsTab(
                editorState, romParser, selectedPalette, { selectedPalette = it }, refreshKey,
                Modifier.weight(1f),
            )
            RidleyTab.COMPONENTS -> RidleyComponentsTab(
                editorState, romParser, selectedPalette, { selectedPalette = it }, refreshKey,
                Modifier.weight(1f),
            )
            RidleyTab.SOURCES -> RidleySourcesTab(
                editorState, romParser, refreshKey,
                onEdit = { editingSheet = it },
                onReset = { editorState.resetRidleySource(); refreshKey++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RidleyAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: RidleySpritemap.PaletteStageDef,
    onPalette: (RidleySpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var side by remember { mutableStateOf(RidleySpritemap.Side.LEFT) }
    val visible = RidleySpritemap.ANIMATIONS.filter { it.side == side }
    var selectedKey by remember { mutableStateOf(visible.first().key) }
    val selected = visible.firstOrNull { it.key == selectedKey } ?: visible.first()
    val animation by produceState<com.supermetroid.editor.rom.SpriteAnimation?>(
        null, romParser, selected, palette, refreshKey,
    ) { value = romParser?.let { editorState.renderRidleyAnimation(it, selected, palette) } }

    Row(modifier.fillMaxSize()) {
        RidleySelectionColumn(
            "Animations",
            "Complete assembly; encounter-specific actions are labeled.",
            palette,
            onPalette,
        ) {
            RidleySidePicker(side) { newSide -> side = newSide; selectedKey = RidleySpritemap.ANIMATIONS.first { it.side == newSide }.key }
            visible.forEach { definition ->
                RidleyChoice(
                    definition.name,
                    "${definition.frames.size} frames · ${if (definition.loop) "loop" else "one-shot"}",
                    definition == selected,
                ) { selectedKey = definition.key }
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("${side.displayName} · ${selected.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(selected.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected.key.startsWith("turn-")) {
                Text(
                    "Frames 2–3 are vanilla's forward-facing transition at ${formatSnes(0xA6EAD7)}. " +
                        "The engine suppresses wings and aligns the articulated tail vertically for this state.",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimationPlayer(animation, previewSize = 440, showExportButtons = false, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RidleyCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: RidleySpritemap.PaletteStageDef,
    onPalette: (RidleySpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(RidleySpritemap.COMPOSITIONS.first()) }
    val sprite by produceState<EnemySpritemap.AssembledSprite?>(null, romParser, selected, palette, refreshKey) {
        value = romParser?.let { editorState.renderRidleyComposition(it, selected, palette) }
    }
    Row(modifier.fillMaxSize()) {
        RidleySelectionColumn("Compositions", "Representative assembled encounter states.", palette, onPalette) {
            RidleySpritemap.COMPOSITIONS.forEach { definition ->
                RidleyChoice(
                    definition.name,
                    when {
                        definition.runtimeTiles.claws == RidleySpritemap.ClawStage.CLENCHED -> "runtime DMA · clenched claws"
                        definition.tailPose == RidleySpritemap.TailPose.POGO -> "straight pogo tail"
                        else -> "neutral articulated tail"
                    },
                    definition == selected,
                ) { selected = definition }
            }
        }
        RidleyPreview(sprite?.toRidleyBitmap(), romParser != null, selected.name,
            Modifier.weight(1f).fillMaxHeight().padding(16.dp))
    }
}

@Composable
private fun RidleyComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: RidleySpritemap.PaletteStageDef,
    onPalette: (RidleySpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var group by remember { mutableStateOf(RidleySpritemap.ComponentGroup.BODY) }
    val components: List<RidleyComponent> = when (group) {
        RidleySpritemap.ComponentGroup.BODY -> RidleySpritemap.BODY_COMPONENTS.map { RidleyComponent.Body(it) }
        RidleySpritemap.ComponentGroup.WINGS -> RidleySpritemap.WING_COMPONENTS.map { RidleyComponent.Wing(it) }
        RidleySpritemap.ComponentGroup.TAIL -> RidleySpritemap.TAIL_COMPONENTS.filter { it.group == group }.map { RidleyComponent.Tail(it) }
        RidleySpritemap.ComponentGroup.TAIL_TIPS -> RidleySpritemap.TAIL_COMPONENTS.filter { it.group == group }.map { RidleyComponent.Tail(it) }
    }
    var selectedName by remember { mutableStateOf(components.first().name) }
    val selected = components.firstOrNull { it.name == selectedName } ?: components.first()
    val sprite by produceState<EnemySpritemap.AssembledSprite?>(null, romParser, selected, palette, refreshKey) {
        value = romParser?.let { parser ->
            when (selected) {
                is RidleyComponent.Body -> editorState.renderRidleyBody(parser, selected.definition, palette)
                is RidleyComponent.Wing -> editorState.renderRidleyWing(parser, selected.definition, palette)
                is RidleyComponent.Tail -> editorState.renderRidleyTail(parser, selected.definition, palette)
            }
        }
    }
    Row(modifier.fillMaxSize()) {
        RidleySelectionColumn("Components", "ROM structures before runtime assembly.", palette, onPalette) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RidleySpritemap.ComponentGroup.values().toList().chunked(2).forEach { options ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        options.forEach { option ->
                            FilterChip(
                                selected = group == option,
                                onClick = { group = option; selectedName = when (option) {
                                    RidleySpritemap.ComponentGroup.BODY -> RidleySpritemap.BODY_COMPONENTS.first().name
                                    RidleySpritemap.ComponentGroup.WINGS -> RidleySpritemap.WING_COMPONENTS.first().name
                                    else -> RidleySpritemap.TAIL_COMPONENTS.first { it.group == option }.name
                                } },
                                label = { Text(option.displayName, fontSize = 9.sp) },
                                modifier = Modifier.weight(1f).height(28.dp),
                            )
                        }
                        if (options.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            components.forEach { component ->
                RidleyChoice(component.name, component.addressDetail(), component.name == selected.name) {
                    selectedName = component.name
                }
            }
        }
        RidleyPreview(sprite?.toRidleyBitmap(), romParser != null, selected.name,
            Modifier.weight(1f).fillMaxHeight().padding(16.dp))
    }
}

@Composable
private fun RidleySourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEdit: (RidleySpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    var readOnlySource by remember { mutableStateOf<RidleySpritemap.RuntimeSourceDef?>(null) }
    val source by produceState<RidleySpriteEditorState.SourceSheet?>(null, romParser, readOnlySource, refreshKey) {
        value = romParser?.let { parser ->
            if (readOnlySource == null) editorState.loadRidleyBaseSource(parser)
            else editorState.loadRidleyRuntimeSource(parser, readOnlySource!!)
        }
    }
    Row(modifier.fillMaxSize()) {
        Column(
            Modifier.width(270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Sources", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("The base species sheet is editable. Other encounter and DMA graphics are shared read-only sources.",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RidleyChoice("Ridley OBJ · 256 tiles", "species GRAPHADR · editable", readOnlySource == null) {
                readOnlySource = null
            }
            Text("Shared OBJ page", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            RidleySpritemap.SHARED_VRAM_SOURCES.forEach { definition ->
                RidleyChoice(definition.name, "${formatSnes(definition.snesAddress)} · tiles \$E0–FF", readOnlySource == definition) {
                    readOnlySource = definition
                }
            }
            Text("Runtime DMA", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            RidleySpritemap.RUNTIME_SOURCES.forEach { definition ->
                RidleyChoice(definition.name, formatSnes(definition.snesAddress), readOnlySource == definition) {
                    readOnlySource = definition
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(source?.name ?: "Source", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (source?.editable == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { source?.let(onEdit) }) { Text("Edit pixels") }
                    if (editorState.hasCustomRidleySource()) OutlinedButton(onClick = onReset) { Text("Reset") }
                }
            } else if (source != null) {
                Text("Read-only shared runtime source", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RidleyPreview(source?.toRidleyBitmap(), romParser != null, source?.name ?: "Source", Modifier.weight(1f))
        }
    }
}

@Composable
private fun RidleySelectionColumn(
    title: String,
    subtitle: String,
    palette: RidleySpritemap.PaletteStageDef,
    onPalette: (RidleySpritemap.PaletteStageDef) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.width(270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Runtime palette", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        RidleySpritemap.PALETTE_STAGES.forEach { stage ->
            FilterChip(
                selected = palette == stage,
                onClick = { onPalette(stage) },
                label = { Text(stage.name, fontSize = 10.sp) },
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
        }
        Text(
            "Norfair selects these stages by HP; Ceres advances them by hit counter.",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun RidleySidePicker(side: RidleySpritemap.Side, onSide: (RidleySpritemap.Side) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(RidleySpritemap.Side.LEFT, RidleySpritemap.Side.RIGHT).forEach { option ->
            FilterChip(
                selected = side == option,
                onClick = { onSide(option) },
                label = { Text(option.displayName, fontSize = 9.sp) },
                modifier = Modifier.height(28.dp),
            )
        }
    }
}

@Composable
private fun RidleyChoice(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
            Text(name, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            Text(detail, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RidleyPreview(bitmap: ImageBitmap?, romLoaded: Boolean, description: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(description, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF292D3E), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap, description, Modifier.fillMaxSize().padding(12.dp),
                    contentScale = ContentScale.Fit, filterQuality = FilterQuality.None,
                )
                romLoaded -> Text("Unable to render source", fontSize = 10.sp)
                else -> Text("Load a ROM to view", fontSize = 10.sp)
            }
        }
    }
}

private fun RidleyComponent.addressDetail(): String = when (this) {
    is RidleyComponent.Body -> "extended body · ${formatSnes(definition.snesAddress)}"
    is RidleyComponent.Wing -> "independent OAM · ${formatSnes(definition.snesAddress)}"
    is RidleyComponent.Tail -> "independent OAM · ${formatSnes(definition.snesAddress)}"
}

private fun formatSnes(address: Int): String =
    "$${(address ushr 16).toString(16).uppercase().padStart(2, '0')}:" +
        (address and 0xFFFF).toString(16).uppercase().padStart(4, '0')

private fun EnemySpritemap.AssembledSprite.toRidleyBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun RidleySpriteEditorState.SourceSheet.toRidleyBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}
