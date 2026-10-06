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
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.TorizoSpritemap
import java.awt.image.BufferedImage

private enum class TorizoTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }
private enum class TorizoAnimationGroup(val label: String) { BODY("Body"), PROJECTILES("Projectiles") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorizoSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(TorizoTab.ANIMATIONS) }
    var encounter by remember { mutableStateOf(TorizoSpritemap.Encounter.BOMB) }
    var palette by remember { mutableStateOf(defaultTorizoPalette(encounter)) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<TorizoSpriteEditorState.SourceSheet?>(null) }

    editingSheet?.let { sheet ->
        val parser = romParser
        SpritePixelEditor(
            label = "Bomb + Golden Torizo OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                if (parser == null) emptyList()
                else editorState.renderEditedTorizoCompositions(parser, pixels, width, height, palette)
                    .map { it.toTorizoBitmap() }
            },
            onApply = { pixels ->
                if (parser != null) {
                    editorState.applyTorizoObjSheetEdits(parser, pixels, sheet.width, sheet.height, palette)
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
                Text("Torizo", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                TorizoSpritemap.Encounter.values().forEach { choice ->
                    FilterChip(
                        selected = encounter == choice,
                        onClick = {
                            encounter = choice
                            palette = defaultTorizoPalette(choice)
                        },
                        label = { Text(choice.displayName.removeSuffix(" Torizo"), fontSize = 9.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TorizoTab.values().forEach { tab ->
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
            TorizoTab.ANIMATIONS -> TorizoAnimationsTab(
                editorState, romParser, encounter, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            TorizoTab.COMPOSITIONS -> TorizoCompositionsTab(
                editorState, romParser, encounter, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            TorizoTab.COMPONENTS -> TorizoComponentsTab(
                editorState, romParser, encounter, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            TorizoTab.SOURCES -> TorizoSourcesTab(
                editorState, romParser, palette, refreshKey,
                onEdit = { editingSheet = it },
                onReset = { editorState.resetTorizoObjSheet(); refreshKey++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TorizoAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    encounter: TorizoSpritemap.Encounter,
    palette: TorizoSpritemap.PaletteStageDef,
    onPalette: (TorizoSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var group by remember { mutableStateOf(TorizoAnimationGroup.BODY) }
    var facing by remember { mutableStateOf(TorizoSpritemap.Facing.LEFT) }
    val bodyAnimations = TorizoSpritemap.ANIMATIONS.filter { it.encounter == encounter && it.facing == facing }
    val projectileAnimations = TorizoSpritemap.PROJECTILE_ANIMATIONS.filter {
        encounter == TorizoSpritemap.Encounter.GOLDEN || !it.key.startsWith("egg-")
    }
    var selectedBodyKey by remember { mutableStateOf("") }
    var selectedProjectileKey by remember { mutableStateOf("") }
    val selectedBody = bodyAnimations.firstOrNull { it.key == selectedBodyKey } ?: bodyAnimations.first()
    val selectedProjectile = projectileAnimations.firstOrNull { it.key == selectedProjectileKey } ?: projectileAnimations.first()
    val animation by produceState<SpriteAnimation?>(null, group, selectedBody, selectedProjectile, palette, romParser, refreshKey) {
        value = romParser?.let { parser ->
            when (group) {
                TorizoAnimationGroup.BODY -> editorState.renderTorizoAnimation(parser, selectedBody, palette)
                TorizoAnimationGroup.PROJECTILES -> editorState.renderTorizoProjectileAnimation(parser, selectedProjectile, palette)
            }
        }
    }
    TorizoWorkspace(
        modifier,
        left = {
            Text("Runtime animations", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TorizoAnimationGroup.values().forEach { choice ->
                    FilterChip(group == choice, { group = choice }, { Text(choice.label, fontSize = 9.sp) }, Modifier.height(28.dp))
                }
            }
            if (group == TorizoAnimationGroup.BODY) {
                Text("Facing", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TorizoSpritemap.Facing.LEFT, TorizoSpritemap.Facing.RIGHT).forEach { choice ->
                        FilterChip(facing == choice, { facing = choice; selectedBodyKey = "" },
                            { Text(choice.displayName, fontSize = 9.sp) }, Modifier.height(28.dp))
                    }
                }
                bodyAnimations.forEach { choice ->
                    TorizoChoice(choice.name, "${choice.frames.size} frames · ${if (choice.loop) "loop" else "one-shot"}", choice == selectedBody) {
                        selectedBodyKey = choice.key
                    }
                }
            } else {
                projectileAnimations.forEach { choice ->
                    TorizoChoice(choice.name, "${choice.addresses.size} source frames", choice == selectedProjectile) {
                        selectedProjectileKey = choice.key
                    }
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            TorizoPalettePicker(encounter, palette, onPalette)
        },
        right = {
            val title = if (group == TorizoAnimationGroup.BODY) selectedBody.name else selectedProjectile.name
            Text("${encounter.displayName} · $title", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (group == TorizoAnimationGroup.BODY) selectedBody.sourceLabel
                else "Bank \$86 instruction timing + bank \$8D projectile OAM",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimationPlayer(animation, previewSize = 440, showExportButtons = false, modifier = Modifier.weight(1f))
        },
    )
}

@Composable
private fun TorizoCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    encounter: TorizoSpritemap.Encounter,
    palette: TorizoSpritemap.PaletteStageDef,
    onPalette: (TorizoSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    val visible = TorizoSpritemap.COMPOSITIONS.filter { it.encounter == encounter }
    var selectedKey by remember { mutableStateOf("") }
    val selected = visible.firstOrNull { it.key == selectedKey } ?: visible.first()
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { editorState.renderTorizoComposition(it, selected, palette)?.toTorizoBitmap() }
    }
    TorizoWorkspace(
        modifier,
        left = {
            Text("Assembled runtime states", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            visible.forEach { choice ->
                TorizoChoice(choice.name, if (choice.runtimeTiles == TorizoSpritemap.RuntimeTiles.BASE) "Shared extended OAM" else "Runtime VRAM overlay", choice == selected) {
                    selectedKey = choice.key
                }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            TorizoPalettePicker(encounter, palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Extended spritemap \$${selected.snesAddress.torizoHex6()} · ${selected.runtimeTiles.name.lowercase().replace('_', ' ')}",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TorizoPreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TorizoComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    encounter: TorizoSpritemap.Encounter,
    palette: TorizoSpritemap.PaletteStageDef,
    onPalette: (TorizoSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var group by remember { mutableStateOf(TorizoSpritemap.ComponentGroup.BODY) }
    val visible = TorizoSpritemap.COMPONENTS.filter { it.group == group }
    var selectedKey by remember { mutableStateOf("") }
    val selected = visible.firstOrNull { it.key == selectedKey } ?: visible.first()
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { editorState.renderTorizoComponent(it, selected, palette)?.toTorizoBitmap() }
    }
    TorizoWorkspace(
        modifier,
        left = {
            Text("Source components", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TorizoSpritemap.ComponentGroup.values().forEach { choice ->
                    FilterChip(group == choice, { group = choice; selectedKey = "" },
                        { Text(choice.displayName, fontSize = 8.sp) }, Modifier.height(28.dp))
                }
            }
            visible.forEach { choice ->
                TorizoChoice(choice.name, "\$${choice.snesAddress.torizoHex6()}", choice == selected) { selectedKey = choice.key }
            }
            Divider(Modifier.padding(vertical = 4.dp))
            TorizoPalettePicker(encounter, palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(if (selected.projectile) "Bank \$8D projectile OAM" else "Bank \$AA extended body spritemap",
                fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TorizoPreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
private fun TorizoSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: TorizoSpritemap.PaletteStageDef,
    refreshKey: Int,
    onEdit: (TorizoSpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(TorizoSpritemap.PIXEL_SOURCES.first()) }
    val sheet by produceState<TorizoSpriteEditorState.SourceSheet?>(null, romParser, selected, palette, refreshKey) {
        value = romParser?.let { editorState.loadTorizoSource(it, selected, palette) }
    }
    val hasCustom = editorState.hasCustomTorizoObjSheet()
    TorizoWorkspace(
        modifier,
        left = {
            Text("Pixel ownership", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            TorizoSpritemap.PIXEL_SOURCES.forEach { choice ->
                TorizoChoice(choice.name, "${if (choice.editable) "Editable" else "Read-only runtime owner"} · \$${choice.snesAddress.torizoHex6()}", choice == selected) {
                    selected = choice
                }
            }
            Text(selected.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(when (selected.key) {
                "shared-obj" -> "The shared \$2000-byte sheet supplies both encounters."
                "runtime-overlays" -> "Blinking eyes, destroyed face/gut, and egg-release belly patches copied by bank \$AA."
                "golden-egg" -> "Room-loaded \$600-byte source for Golden Torizo eggs and hatchlings."
                else -> "Room-loaded \$400-byte source used by the sixteen Bomb Torizo statue fragments."
            }, fontSize = 9.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected.editable) Button(onClick = { sheet?.let(onEdit) }, enabled = sheet != null) {
                    Text("Edit Pixels", fontSize = 10.sp)
                }
                if (selected.editable && hasCustom) OutlinedButton(onClick = onReset) { Text("Reset", fontSize = 10.sp) }
            }
            if (selected.editable && hasCustom) Text("Project override active", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                when {
                    sheet != null -> Image(sheet!!.toTorizoBitmap(), selected.name,
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
private fun TorizoPalettePicker(
    encounter: TorizoSpritemap.Encounter,
    selected: TorizoSpritemap.PaletteStageDef,
    onSelected: (TorizoSpritemap.PaletteStageDef) -> Unit,
) {
    Text("Runtime palette", fontSize = 10.sp, fontWeight = FontWeight.Bold)
    TorizoSpritemap.PALETTE_STAGES.filter { it.encounter == encounter }.forEach { choice ->
        TorizoChoice(choice.name, "rows 1 + 2 · \$${choice.palette1Snes.torizoHex6()}", selected == choice) { onSelected(choice) }
    }
}

@Composable
private fun TorizoWorkspace(
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
private fun TorizoChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
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
private fun TorizoPreview(bitmap: ImageBitmap?, romLoaded: Boolean, label: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, label, Modifier.fillMaxSize().padding(10.dp),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            romLoaded -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text("Load a ROM to view Torizo", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun defaultTorizoPalette(encounter: TorizoSpritemap.Encounter): TorizoSpritemap.PaletteStageDef =
    TorizoSpritemap.PALETTE_STAGES.first {
        if (encounter == TorizoSpritemap.Encounter.BOMB) it.key == "bomb-normal" else it.key == "gold-active"
    }

private fun EnemySpritemap.AssembledSprite.toTorizoBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun TorizoSpriteEditorState.SourceSheet.toTorizoBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun Int.torizoHex6(): String = toString(16).uppercase().padStart(6, '0')
