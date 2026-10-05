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
import com.supermetroid.editor.rom.CrocomireSpritemap
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import java.awt.image.BufferedImage

private enum class CrocomireTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }
private enum class CrocomireAnimationGroup(val label: String) {
    FIGHT("Fight"), TONGUE("Tongue"), MELTING("Melting"), SKELETON("Skeleton")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrocomireSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(CrocomireTab.ANIMATIONS) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<CrocomireSpriteEditorState.SourceSheet?>(null) }

    editingSheet?.let { sheet ->
        val parser = romParser
        SpritePixelEditor(
            label = "Crocomire Enemy OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                if (parser == null) emptyList()
                else editorState.renderEditedCrocomireCompositions(parser, pixels, width, height)
                    .map { it.toCrocomireBitmap() }
            },
            onApply = { pixels ->
                if (parser != null) {
                    editorState.applyCrocomireObjSheetEdits(
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
                    "Crocomire",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                CrocomireTab.values().forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp) },
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
            CrocomireTab.ANIMATIONS -> CrocomireAnimationsTab(
                editorState, romParser, refreshKey, Modifier.weight(1f),
            )
            CrocomireTab.COMPOSITIONS -> CrocomireCompositionsTab(
                editorState, romParser, refreshKey, Modifier.weight(1f),
            )
            CrocomireTab.COMPONENTS -> CrocomireComponentsTab(
                editorState, romParser, refreshKey, Modifier.weight(1f),
            )
            CrocomireTab.SOURCES -> CrocomireSourcesTab(
                editorState = editorState,
                romParser = romParser,
                refreshKey = refreshKey,
                onEdit = { editingSheet = it },
                onReset = {
                    editorState.resetCrocomireObjSheet()
                    refreshKey++
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CrocomireAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var group by remember { mutableStateOf(CrocomireAnimationGroup.FIGHT) }
    var selected by remember { mutableStateOf(CrocomireSpritemap.ANIMATIONS.first()) }
    val visible = CrocomireSpritemap.ANIMATIONS.filter { it.animationGroup() == group }
    LaunchedEffect(group) {
        if (selected !in visible) visible.firstOrNull()?.let { selected = it }
    }
    val animation by produceState<SpriteAnimation?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.renderCrocomireAnimation(it, selected) }
    }

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(280.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Phase", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CrocomireAnimationGroup.values().forEach { candidate ->
                    FilterChip(
                        selected = group == candidate,
                        onClick = { group = candidate },
                        label = { Text(candidate.label, fontSize = 8.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Source instruction lists · ${visible.size}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            visible.forEach { definition ->
                CrocomireChoice(
                    title = definition.name,
                    detail = "${definition.expectedFrameCount} frames · \$${definition.snesAddress.hex6()}",
                    selected = selected == definition,
                    onClick = { selected = definition },
                )
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
                "${animation?.frames?.size ?: 0} source frame(s) · ${if (selected.loop) "loops" else "one-shot"}",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "BG2 body, OBJ details, melting swaps, and skeleton DMA use their exact runtime owners.",
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
private fun CrocomireCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(CrocomireSpritemap.COMPOSITIONS.first()) }
    val bitmap by produceState<ImageBitmap?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.renderCrocomireComposition(it, selected)?.toCrocomireBitmap() }
    }
    CrocomireSelectionPreview(
        title = "Compositions",
        subtitle = "Representative complete runtime states.",
        choices = CrocomireSpritemap.COMPOSITIONS,
        selected = selected,
        choiceName = { it.name },
        choiceDetail = { "${it.phase.displayName()} · \$${it.snesAddress.hex6()}" },
        onSelected = { selected = it },
        bitmap = bitmap,
        romLoaded = romParser != null,
        modifier = modifier,
    )
}

@Composable
private fun CrocomireComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(CrocomireSpritemap.COMPONENTS.first()) }
    val bitmap by produceState<ImageBitmap?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.renderCrocomireComponent(it, selected)?.toCrocomireBitmap() }
    }
    CrocomireSelectionPreview(
        title = "Runtime components",
        subtitle = "Inspect each independently owned layer or phase.",
        choices = CrocomireSpritemap.COMPONENTS,
        selected = selected,
        choiceName = { it.name },
        choiceDetail = { "${it.layer.name} · \$${it.snesAddress.hex6()}" },
        onSelected = { selected = it },
        bitmap = bitmap,
        romLoaded = romParser != null,
        modifier = modifier,
    )
}

@Composable
private fun <T> CrocomireSelectionPreview(
    title: String,
    subtitle: String,
    choices: List<T>,
    selected: T,
    choiceName: (T) -> String,
    choiceDetail: (T) -> String,
    onSelected: (T) -> Unit,
    bitmap: ImageBitmap?,
    romLoaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(270.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divider(modifier = Modifier.padding(vertical = 3.dp))
            choices.forEach { choice ->
                CrocomireChoice(
                    title = choiceName(choice),
                    detail = choiceDetail(choice),
                    selected = selected == choice,
                    onClick = { onSelected(choice) },
                )
            }
        }
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(choiceName(selected), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            CrocomirePreview(bitmap, romLoaded, choiceName(selected), Modifier.weight(1f))
        }
    }
}

@Composable
private fun CrocomireSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEdit: (CrocomireSpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(CrocomireSpritemap.PIXEL_SOURCES.first()) }
    val sheet by produceState<CrocomireSpriteEditorState.SourceSheet?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.loadCrocomireSource(it, selected) }
    }
    val hasCustom = editorState.hasCustomCrocomireObjSheet()

    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.width(310.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Pixel sources", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                "Only Crocomire's enemy OBJ payload is edited here. Room, melting, and skeleton assets retain separate ownership.",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Divider()
            CrocomireSpritemap.PIXEL_SOURCES.forEach { definition ->
                CrocomireChoice(
                    title = definition.name,
                    detail = buildString {
                        append(if (definition.editable) "Editable" else "Read-only")
                        append(" · \$")
                        append(definition.snesAddress.hex6())
                    },
                    selected = selected == definition,
                    onClick = { selected = definition },
                )
            }
            Divider()
            Text(selected.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                selected.sourceDescription(),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected.editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sheet?.let(onEdit) }, enabled = sheet != null) {
                        Text("Edit OBJ Pixels", fontSize = 10.sp)
                    }
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
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Placement and timing · read-only", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("AI bank \$A4 · extended maps and instruction lists", fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Animations and Compositions preserve all source coordinates and durations.",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(selected.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "${if (selected.byteCount > 0) "\$${selected.byteCount.toString(16).uppercase()} bytes" else "decompressed room tileset"} · " +
                    if (selected.editable) "safe owned range" else "reference source",
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
                        bitmap = sheet!!.toCrocomireBitmap(),
                        contentDescription = selected.name,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                    romParser != null -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> Text(
                        "Load a ROM to inspect this source",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CrocomireChoice(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CrocomirePreview(
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
                "Load a ROM to view Crocomire",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun CrocomireSpritemap.InstructionListDef.animationGroup(): CrocomireAnimationGroup = when {
    phase == CrocomireSpritemap.Phase.SKELETON -> CrocomireAnimationGroup.SKELETON
    phase == CrocomireSpritemap.Phase.MELTING_1 || phase == CrocomireSpritemap.Phase.MELTING_2 || key == "tongue-melting" ->
        CrocomireAnimationGroup.MELTING
    "tongue" in key || key == "bridge-collapsed" -> CrocomireAnimationGroup.TONGUE
    else -> CrocomireAnimationGroup.FIGHT
}

private fun CrocomireSpritemap.Phase.displayName(): String = when (this) {
    CrocomireSpritemap.Phase.LIVING -> "Living"
    CrocomireSpritemap.Phase.MELTING_1 -> "Melting pass 1"
    CrocomireSpritemap.Phase.MELTING_2 -> "Melting pass 2"
    CrocomireSpritemap.Phase.SKELETON -> "Skeleton"
}

private fun CrocomireSpritemap.PixelSourceDef.sourceDescription(): String = when (key) {
    "base" -> "The \$DDBF header owns this \$2600-byte transfer; the tongue shares its first \$2000 bytes."
    "room" -> "Crocomire's BG2 body and tail come from room tileset \$1B and are edited through the tileset tools."
    "melt-1", "melt-2" -> "Bank \$A4 streams this dedicated payload over physical OBJ tiles \$130–18F during the acid sequence."
    else -> "Six \$200-byte bank-\$AD chunks replace physical OBJ tiles \$160–19F and \$1E0–1FF during the skeleton sequence."
}

private fun Int.hex6(): String = toString(16).uppercase().padStart(6, '0')

private fun EnemySpritemap.AssembledSprite.toCrocomireBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun CrocomireSpriteEditorState.SourceSheet.toCrocomireBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}
