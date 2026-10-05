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
import com.supermetroid.editor.rom.BotwoonSpritemap
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import java.awt.image.BufferedImage

private enum class BotwoonTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }
private enum class BotwoonAnimationMode(val label: String) { SWIM("Swim"), SPIT("Spit"), PROJECTILE("Spit projectile") }
private enum class BotwoonComponentGroup(val label: String) { HEAD("Head"), BODY("Body"), TAIL("Tail"), SPIT("Spit") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotwoonSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(BotwoonTab.ANIMATIONS) }
    var palette by remember { mutableStateOf(BotwoonSpritemap.PALETTE_STAGES.first()) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<BotwoonSpriteEditorState.SourceSheet?>(null) }

    editingSheet?.let { sheet ->
        val parser = romParser
        SpritePixelEditor(
            label = "Botwoon Shared OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                if (parser == null) emptyList()
                else editorState.renderEditedBotwoonCompositions(parser, pixels, width, height, palette)
                    .map { it.toBotwoonBitmap() }
            },
            onApply = { pixels ->
                if (parser != null) {
                    editorState.applyBotwoonObjSheetEdits(parser, pixels, sheet.width, sheet.height, palette)
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
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Botwoon", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                BotwoonTab.values().forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
                if (romParser == null) Text("(load a ROM)", fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when (activeTab) {
            BotwoonTab.ANIMATIONS -> BotwoonAnimationsTab(
                editorState, romParser, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            BotwoonTab.COMPOSITIONS -> BotwoonCompositionsTab(
                editorState, romParser, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            BotwoonTab.COMPONENTS -> BotwoonComponentsTab(
                editorState, romParser, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            BotwoonTab.SOURCES -> BotwoonSourcesTab(
                editorState, romParser, palette, refreshKey,
                onEdit = { editingSheet = it },
                onReset = { editorState.resetBotwoonObjSheet(); refreshKey++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BotwoonAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: BotwoonSpritemap.PaletteStageDef,
    onPalette: (BotwoonSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var mode by remember { mutableStateOf(BotwoonAnimationMode.SWIM) }
    var direction by remember { mutableStateOf(BotwoonSpritemap.DIRECTIONS[2]) }
    val animation by produceState<SpriteAnimation?>(null, mode, direction, palette, romParser, refreshKey) {
        value = romParser?.let { parser ->
            when (mode) {
                BotwoonAnimationMode.SWIM -> editorState.renderBotwoonSwimAnimation(parser, direction, palette)
                BotwoonAnimationMode.SPIT -> editorState.renderBotwoonSpitAnimation(parser, direction, palette)
                BotwoonAnimationMode.PROJECTILE -> editorState.renderBotwoonSpitProjectileAnimation(parser, palette)
            }
        }
    }
    BotwoonWorkspace(
        modifier,
        left = {
            Text("Runtime animation", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            BotwoonAnimationMode.values().forEach { choice ->
                BotwoonChoice(choice.label, when (choice) {
                    BotwoonAnimationMode.SWIM -> "4 body phases · 8 ticks each"
                    BotwoonAnimationMode.SPIT -> "Head timing + independent body loop"
                    BotwoonAnimationMode.PROJECTILE -> "5 frames · bank \$86/\$8D"
                }, mode == choice) { mode = choice }
            }
            if (mode != BotwoonAnimationMode.PROJECTILE) {
                Divider(Modifier.padding(vertical = 4.dp))
                Text("Direction", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                BotwoonSpritemap.DIRECTIONS.forEach { choice ->
                    BotwoonChoice(choice.name, "Head \$${choice.closedHeadMap.hex6()}", direction == choice) {
                        direction = choice
                    }
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            BotwoonPalettePicker(palette, onPalette)
        },
        right = {
            Text(
                if (mode == BotwoonAnimationMode.PROJECTILE) mode.label else "${mode.label} · ${direction.name}",
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                if (mode == BotwoonAnimationMode.SPIT)
                    "The body keeps its 4×8-tick loop while the head stays closed for 32 ticks, then opens."
                else "Head + 13 history-following enemy projectiles",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimationPlayer(animation, previewSize = 440, showExportButtons = false, modifier = Modifier.weight(1f))
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BotwoonCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: BotwoonSpritemap.PaletteStageDef,
    onPalette: (BotwoonSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(BotwoonSpritemap.COMPOSITIONS.first()) }
    var mouthOpen by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, selected, mouthOpen, palette, romParser, refreshKey) {
        value = romParser?.let {
            editorState.renderBotwoonComposition(it, selected, palette, mouthOpen)?.toBotwoonBitmap()
        }
    }
    BotwoonWorkspace(
        modifier,
        left = {
            Text("Position-history snapshots", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Each segment is oriented from its own link vector.", fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotwoonSpritemap.COMPOSITIONS.forEach { choice ->
                BotwoonChoice(choice.name, if (choice.points == null) "12 px segment cadence" else "Curved history",
                    selected == choice) { selected = choice }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            Text("Head", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!mouthOpen, { mouthOpen = false }, { Text("Closed", fontSize = 9.sp) }, Modifier.height(28.dp))
                FilterChip(mouthOpen, { mouthOpen = true }, { Text("Open", fontSize = 9.sp) }, Modifier.height(28.dp))
            }
            Divider(Modifier.padding(vertical = 4.dp))
            BotwoonPalettePicker(palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Placement follows \$B3:9C7B–9DBF; pixels remain owned by Tiles_Botwoon.",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BotwoonPreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BotwoonComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: BotwoonSpritemap.PaletteStageDef,
    onPalette: (BotwoonSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var group by remember { mutableStateOf(BotwoonComponentGroup.HEAD) }
    val visible = BotwoonSpritemap.COMPONENTS.filter { definition ->
        when (group) {
            BotwoonComponentGroup.HEAD -> definition.kind == BotwoonSpritemap.ComponentKind.HEAD
            BotwoonComponentGroup.BODY -> definition.kind == BotwoonSpritemap.ComponentKind.BODY
            BotwoonComponentGroup.TAIL -> definition.kind == BotwoonSpritemap.ComponentKind.TAIL
            BotwoonComponentGroup.SPIT -> definition.kind == BotwoonSpritemap.ComponentKind.SPIT
        }
    }
    var selectedKey by remember { mutableStateOf(visible.first().key) }
    val selected = visible.firstOrNull { it.key == selectedKey } ?: visible.first()
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { editorState.renderBotwoonComponent(it, selected, palette)?.toBotwoonBitmap() }
    }
    BotwoonWorkspace(
        modifier,
        left = {
            Text("Runtime components", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BotwoonComponentGroup.values().forEach { choice ->
                    FilterChip(group == choice, { group = choice; selectedKey = BotwoonSpritemap.COMPONENTS.first {
                        when (choice) {
                            BotwoonComponentGroup.HEAD -> it.kind == BotwoonSpritemap.ComponentKind.HEAD
                            BotwoonComponentGroup.BODY -> it.kind == BotwoonSpritemap.ComponentKind.BODY
                            BotwoonComponentGroup.TAIL -> it.kind == BotwoonSpritemap.ComponentKind.TAIL
                            BotwoonComponentGroup.SPIT -> it.kind == BotwoonSpritemap.ComponentKind.SPIT
                        }
                    }.key }, { Text(choice.label, fontSize = 8.sp) }, Modifier.height(26.dp))
                }
            }
            visible.forEach { choice ->
                BotwoonChoice(choice.name, "\$${choice.snesAddress.hex6()}", selected == choice) { selectedKey = choice.key }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            BotwoonPalettePicker(palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (selected.kind == BotwoonSpritemap.ComponentKind.HEAD) "Enemy OAM · bank \$B3"
                else "Enemy projectile OAM · bank \$8D",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BotwoonPreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
private fun BotwoonSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: BotwoonSpritemap.PaletteStageDef,
    refreshKey: Int,
    onEdit: (BotwoonSpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val source = BotwoonSpritemap.PIXEL_SOURCES.first()
    val sheet by produceState<BotwoonSpriteEditorState.SourceSheet?>(null, romParser, palette, refreshKey) {
        value = romParser?.let { editorState.loadBotwoonSource(it, source, palette) }
    }
    val hasCustom = editorState.hasCustomBotwoonObjSheet()
    BotwoonWorkspace(
        modifier,
        left = {
            Text("Pixel source", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            BotwoonChoice(source.name, "Editable · \$${source.snesAddress.hex6()}", true) {}
            Text(source.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text("One \$1800-byte transfer supplies the enemy head and all bank-\$86 body/spit projectiles.",
                fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sheet?.let(onEdit) }, enabled = sheet != null) { Text("Edit Pixels", fontSize = 10.sp) }
                if (hasCustom) OutlinedButton(onClick = onReset) { Text("Reset", fontSize = 10.sp) }
            }
            if (hasCustom) Text("Project override active", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Divider(Modifier.padding(vertical = 4.dp))
            Text("Placement · read-only", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Head: bank \$B3 enemy OAM\nBody/tail/spit: bank \$86 AI + bank \$8D OAM\nTrail: 256-record circular history",
                fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        right = {
            Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                when {
                    sheet != null -> Image(sheet!!.toBotwoonBitmap(), source.name,
                        Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None)
                    romParser != null -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> Text("Load a ROM to inspect this source", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
private fun BotwoonPalettePicker(
    selected: BotwoonSpritemap.PaletteStageDef,
    onSelected: (BotwoonSpritemap.PaletteStageDef) -> Unit,
) {
    Text("Runtime health palette", fontSize = 10.sp, fontWeight = FontWeight.Bold)
    BotwoonSpritemap.PALETTE_STAGES.forEach { choice ->
        BotwoonChoice(choice.name, "\$${choice.snesAddress.hex6()}", selected == choice) { onSelected(choice) }
    }
    val speed = when {
        selected.thresholdHp > 1500 -> BotwoonSpritemap.SPEED_STAGES[0]
        selected.thresholdHp > 750 -> BotwoonSpritemap.SPEED_STAGES[1]
        else -> BotwoonSpritemap.SPEED_STAGES[2]
    }
    Text("${speed.name}: ${speed.speed} px/frame · ${speed.historyFrames} history frames/segment",
        fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BotwoonWorkspace(
    modifier: Modifier,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.width(290.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp), content = left)
        Divider(Modifier.fillMaxHeight().width(1.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp), content = right)
    }
}

@Composable
private fun BotwoonChoice(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
        RoundedCornerShape(5.dp),
    ).clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 4.dp)) {
        Text(title, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(detail, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BotwoonPreview(bitmap: ImageBitmap?, romLoaded: Boolean, label: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, label, Modifier.fillMaxSize().padding(10.dp),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            romLoaded -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text("Load a ROM to view Botwoon", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun EnemySpritemap.AssembledSprite.toBotwoonBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun BotwoonSpriteEditorState.SourceSheet.toBotwoonBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun Int.hex6(): String = toString(16).uppercase().padStart(6, '0')
