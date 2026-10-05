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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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

private enum class DraygonEditorTab { COMPOSITIONS, ANIMATIONS, SOURCES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraygonSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(DraygonEditorTab.COMPOSITIONS) }
    var selectedPalette by remember { mutableStateOf(DraygonSpritemap.PALETTE_STAGES.first()) }
    val palettes = remember { DraygonSpritemap.PALETTE_STAGES + DraygonSpritemap.WHITE_FLASH }

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

        if (activeTab != DraygonEditorTab.SOURCES) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Runtime palette", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                palettes.forEach { palette ->
                    FilterChip(
                        selected = selectedPalette == palette,
                        onClick = { selectedPalette = palette },
                        label = { Text(palette.name, fontSize = 8.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Divider()
        }

        when (activeTab) {
            DraygonEditorTab.COMPOSITIONS -> DraygonCompositionsTab(
                editorState,
                romParser,
                selectedPalette,
                Modifier.weight(1f),
            )
            DraygonEditorTab.ANIMATIONS -> DraygonAnimationsTab(
                editorState,
                romParser,
                selectedPalette,
                Modifier.weight(1f),
            )
            DraygonEditorTab.SOURCES -> DraygonSourcesTab(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DraygonCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: DraygonSpritemap.PaletteStageDef,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(DraygonSpritemap.COMPOSITIONS.first()) }
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser) {
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
            DraygonSpritemap.Side.values().forEach { side ->
                Text(
                    side.displayName,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 5.dp),
                )
                DraygonSpritemap.COMPOSITIONS.filter { it.side == side }.forEach { definition ->
                    val isSelected = selected == definition
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { selected = definition },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            definition.name.substringAfter("· "),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
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
private fun DraygonAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: DraygonSpritemap.PaletteStageDef,
    modifier: Modifier = Modifier,
) {
    var selectedSide by remember { mutableStateOf<DraygonSpritemap.Side?>(null) }
    var selectedPart by remember { mutableStateOf<DraygonSpritemap.AnimationPart?>(null) }
    var selected by remember { mutableStateOf(DraygonSpritemap.ANIMATIONS.first()) }
    val visible = DraygonSpritemap.ANIMATIONS.filter { definition ->
        (selectedSide == null || definition.side == selectedSide) &&
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
    ) {
        value = romParser?.let { editorState.renderDraygonAnimation(it, selected, palette) }
    }

    Column(modifier = modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            (listOf<DraygonSpritemap.Side?>(null) + DraygonSpritemap.Side.values()).forEach { side ->
                FilterChip(
                    selected = selectedSide == side,
                    onClick = { selectedSide = side },
                    label = { Text(side?.displayName ?: "Both sides", fontSize = 8.sp) },
                    modifier = Modifier.height(27.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            (listOf<DraygonSpritemap.AnimationPart?>(null) + DraygonSpritemap.AnimationPart.values()).forEach { part ->
                FilterChip(
                    selected = selectedPart == part,
                    onClick = { selectedPart = part },
                    label = { Text(part?.displayName() ?: "All parts", fontSize = 8.sp) },
                    modifier = Modifier.height(27.dp),
                )
            }
        }
        Divider()
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
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
}

@Composable
private fun DraygonSourcesTab(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Two graphics owners, one runtime composition", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Draygon is not a single sprite sheet. BG2 body regions come from the room tileset; movable parts " +
                "also use enemy OBJ tiles. The preview deliberately combines both sources.",
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceCard(
            "Room BG2 tiles",
            "Tileset \$1C · Tiles_1C_Draygon · \$BF:9DEA",
            "Decompresses to \$4800 bytes. BG2 extended tilemaps use this owner.",
        )
        SourceCard(
            "Enemy OBJ tiles",
            "Tiles_Draygon · \$B0:C800 · \$2000 bytes",
            "Loaded once by the body header at physical tile \$100 and shared by all four slots.",
        )
        SourceCard(
            "Placement and timing",
            "AI bank \$A5 · 103 extended maps · 39 rendered lists",
            "94 standard OAM maps, 48 BG2 tilemaps, and 250 production frame occurrences.",
        )
        Divider()
        Text("Runtime enemy slots", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "Body" to "\$DE3F · full \$2000-byte transfer",
            "Eye" to "\$DE7F · \$1800-byte view of shared tiles",
            "Tail" to "\$DEBF · \$1800-byte view of shared tiles",
            "Arms" to "\$DEFF · \$1800-byte view of shared tiles",
        ).forEach { (name, detail) ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, modifier = Modifier.width(54.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Surface(
            color = Color(0xFF252519),
            shape = RoundedCornerShape(7.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Placement is source-backed and read-only for now. Editing flattened composite pixels would " +
                    "hide which BG2 or OBJ owner receives the change.",
                modifier = Modifier.padding(10.dp),
                fontSize = 9.sp,
                lineHeight = 13.sp,
                color = Color(0xFFFFDD88),
            )
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

private fun DraygonSpritemap.AnimationPart.displayName(): String = when (this) {
    DraygonSpritemap.AnimationPart.BODY_BASE -> "Body"
    DraygonSpritemap.AnimationPart.BODY_FACE -> "Face overlay"
    DraygonSpritemap.AnimationPart.EYE -> "Eye"
    DraygonSpritemap.AnimationPart.TAIL -> "Tail"
    DraygonSpritemap.AnimationPart.ARMS -> "Arms"
}
