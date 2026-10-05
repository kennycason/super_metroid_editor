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
import com.supermetroid.editor.rom.SporeSpawnSpritemap
import com.supermetroid.editor.rom.SpriteAnimation
import java.awt.image.BufferedImage

private enum class SporeSpawnTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }
private enum class SporePaletteGroup { HEALTH, DEATH }
private enum class SporeAnimationKind { BOSS, SPAWNER, SPORE }
private data class SporeAnimationChoice(
    val name: String,
    val kind: SporeAnimationKind,
    val source: SporeSpawnSpritemap.InstructionListDef? = null,
)

private val SPORE_ANIMATIONS = SporeSpawnSpritemap.ANIMATIONS.map {
    SporeAnimationChoice(it.name, SporeAnimationKind.BOSS, it)
} + listOf(
    SporeAnimationChoice("Spore spawner", SporeAnimationKind.SPAWNER),
    SporeAnimationChoice("Spore", SporeAnimationKind.SPORE),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SporeSpawnSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(SporeSpawnTab.ANIMATIONS) }
    var palette by remember { mutableStateOf(SporeSpawnSpritemap.PALETTE_STAGES.first()) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<SporeSpawnSpriteEditorState.SourceSheet?>(null) }

    editingSheet?.let { sheet ->
        val parser = romParser
        SpritePixelEditor(
            label = "Spore Spawn Shared OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                if (parser == null) emptyList()
                else editorState.renderEditedSporeSpawnCompositions(parser, pixels, width, height, palette)
                    .map { it.toSporeBitmap() }
            },
            onApply = { pixels ->
                if (parser != null) {
                    editorState.applySporeSpawnObjSheetEdits(parser, pixels, sheet.width, sheet.height, palette)
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
                Text("Spore Spawn", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                SporeSpawnTab.values().forEach { tab ->
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
            SporeSpawnTab.ANIMATIONS -> SporeSpawnAnimationsTab(
                editorState, romParser, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            SporeSpawnTab.COMPOSITIONS -> SporeSpawnCompositionsTab(
                editorState, romParser, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            SporeSpawnTab.COMPONENTS -> SporeSpawnComponentsTab(
                editorState, romParser, palette, { palette = it }, refreshKey, Modifier.weight(1f),
            )
            SporeSpawnTab.SOURCES -> SporeSpawnSourcesTab(
                editorState, romParser, palette, refreshKey,
                onEdit = { editingSheet = it },
                onReset = { editorState.resetSporeSpawnObjSheet(); refreshKey++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SporeSpawnAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: SporeSpawnSpritemap.PaletteStageDef,
    onPalette: (SporeSpawnSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(SPORE_ANIMATIONS.first()) }
    val animation by produceState<SpriteAnimation?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { parser ->
            when (selected.kind) {
                SporeAnimationKind.BOSS -> editorState.renderSporeSpawnAnimation(parser, selected.source!!, palette)
                SporeAnimationKind.SPAWNER -> editorState.renderSporeSpawnSpawnerAnimation(parser, palette)
                SporeAnimationKind.SPORE -> editorState.renderSporeSpawnSporeAnimation(parser)
            }
        }
    }
    SporeSpawnWorkspace(
        modifier = modifier,
        left = {
            Text("Source animations", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            SPORE_ANIMATIONS.forEach { choice ->
                SporeChoice(
                    choice.name,
                    choice.source?.let { "${it.expectedFrameCount} frames · \$${it.snesAddress.hex6()}" }
                        ?: "Bank \$86 projectile",
                    selected == choice,
                ) { selected = choice }
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            SporePalettePicker(palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (selected.kind == SporeAnimationKind.BOSS)
                    "Body + four runtime-positioned stalk projectiles"
                else "Projectile-owned component animation",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimationPlayer(animation, previewSize = 440, showExportButtons = false, modifier = Modifier.weight(1f))
        },
    )
}

@Composable
private fun SporeSpawnCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: SporeSpawnSpritemap.PaletteStageDef,
    onPalette: (SporeSpawnSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(SporeSpawnSpritemap.COMPOSITIONS.first()) }
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { editorState.renderSporeSpawnComposition(it, selected, palette)?.toSporeBitmap() }
    }
    SporeSpawnWorkspace(
        modifier,
        left = {
            Text("Complete runtime states", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Body + interpolated stalk", fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SporeSpawnSpritemap.COMPOSITIONS.forEach { choice ->
                SporeChoice(choice.name, "Body (${choice.bodyX.hex4()}, ${choice.bodyY.hex4()})",
                    selected == choice) { selected = choice }
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            SporePalettePicker(palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Stalk positions use the exact \$A5:EC49 interpolation.", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SporePreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
private fun SporeSpawnComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: SporeSpawnSpritemap.PaletteStageDef,
    onPalette: (SporeSpawnSpritemap.PaletteStageDef) -> Unit,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(SporeSpawnSpritemap.COMPONENTS.first()) }
    val bitmap by produceState<ImageBitmap?>(null, selected, palette, romParser, refreshKey) {
        value = romParser?.let { editorState.renderSporeSpawnComponent(it, selected, palette)?.toSporeBitmap() }
    }
    SporeSpawnWorkspace(
        modifier,
        left = {
            Text("Runtime components", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            SporeSpawnSpritemap.COMPONENTS.forEach { choice ->
                SporeChoice(choice.name, "${choice.kind.name.lowercase()} · \$${choice.snesAddress.hex6()}",
                    selected == choice) { selected = choice }
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            SporePalettePicker(palette, onPalette)
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (selected.kind == SporeSpawnSpritemap.ComponentKind.BODY) "Bank \$A5 extended OAM"
                else "Bank \$86 projectile using bank \$8D OAM",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SporePreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
private fun SporeSpawnSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    palette: SporeSpawnSpritemap.PaletteStageDef,
    refreshKey: Int,
    onEdit: (SporeSpawnSpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val source = SporeSpawnSpritemap.PIXEL_SOURCES.first()
    val sheet by produceState<SporeSpawnSpriteEditorState.SourceSheet?>(null, romParser, palette, refreshKey) {
        value = romParser?.let { editorState.loadSporeSpawnSource(it, source, palette) }
    }
    val hasCustom = editorState.hasCustomSporeSpawnObjSheet()
    SporeSpawnWorkspace(
        modifier,
        left = {
            Text("Pixel source", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            SporeChoice(source.name, "Editable · \$${source.snesAddress.hex6()}", true) {}
            Text(source.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "The \$DF3F and \$DF7F headers share this \$0E00-byte transfer. It also supplies the stalk, spawners, and spores.",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { sheet?.let(onEdit) }, enabled = sheet != null) {
                    Text("Edit Pixels", fontSize = 10.sp)
                }
                if (hasCustom) OutlinedButton(onClick = onReset) { Text("Reset", fontSize = 10.sp) }
            }
            if (hasCustom) Text("Project override active", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Text("Placement · read-only", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Body: bank \$A5 extended OAM\nStalk: bank \$86 projectile AI\nProjectile maps: bank \$8D",
                fontSize = 9.sp, lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        right = {
            Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sheet != null -> Image(sheet!!.toSporeBitmap(), source.name,
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
private fun SporeSpawnWorkspace(
    modifier: Modifier,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        Column(
            Modifier.width(280.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            content = left,
        )
        Divider(Modifier.fillMaxHeight().width(1.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = right,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SporePalettePicker(
    selected: SporeSpawnSpritemap.PaletteStageDef,
    onSelected: (SporeSpawnSpritemap.PaletteStageDef) -> Unit,
) {
    var group by remember(selected.deathPaletteIndex) {
        mutableStateOf(if (selected.deathPaletteIndex == null) SporePaletteGroup.HEALTH else SporePaletteGroup.DEATH)
    }
    Text("Runtime palette", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        SporePaletteGroup.values().forEach { candidate ->
            FilterChip(
                selected = group == candidate,
                onClick = { group = candidate },
                label = { Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 9.sp) },
                modifier = Modifier.height(27.dp),
            )
        }
    }
    val choices = SporeSpawnSpritemap.PALETTE_STAGES.filter {
        (group == SporePaletteGroup.DEATH) == (it.deathPaletteIndex != null)
    }
    choices.forEach { choice ->
        FilterChip(
            selected = selected == choice,
            onClick = { onSelected(choice) },
            label = { Text(choice.name, fontSize = 9.sp) },
            modifier = Modifier.fillMaxWidth().height(29.dp),
        )
    }
}

@Composable
private fun SporeChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SporePreview(bitmap: ImageBitmap?, romLoaded: Boolean, description: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, description, Modifier.fillMaxSize(0.92f).padding(8.dp),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            romLoaded -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text("Load a ROM to view Spore Spawn", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun EnemySpritemap.AssembledSprite.toSporeBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun SporeSpawnSpriteEditorState.SourceSheet.toSporeBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun Int.hex6(): String = toString(16).uppercase().padStart(6, '0')
private fun Int.hex4(): String = "\$" + toString(16).uppercase().padStart(4, '0')
