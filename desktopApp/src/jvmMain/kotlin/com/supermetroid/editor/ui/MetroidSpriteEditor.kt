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
import com.supermetroid.editor.rom.MetroidSpritemap
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import java.awt.image.BufferedImage

private enum class MetroidTab { ANIMATIONS, COMPOSITIONS, COMPONENTS, SOURCES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroidSpriteEditor(
    editorState: EditorState,
    romParser: RomParser?,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(MetroidTab.ANIMATIONS) }
    var refreshKey by remember { mutableStateOf(0) }
    var editingSheet by remember { mutableStateOf<MetroidSpriteEditorState.SourceSheet?>(null) }

    editingSheet?.let { sheet ->
        val parser = romParser
        SpritePixelEditor(
            label = "Metroid Shared OBJ Source",
            initialPixels = sheet.pixels,
            imageWidth = sheet.width,
            imageHeight = sheet.height,
            fixedPalette = sheet.palette,
            liveReferenceFrames = { pixels, width, height ->
                if (parser == null) emptyList()
                else editorState.renderEditedMetroidCompositions(parser, pixels, width, height)
                    .map { it.toMetroidBitmap() }
            },
            onApply = { pixels ->
                if (parser != null) {
                    editorState.applyMetroidObjSheetEdits(parser, pixels, sheet.width, sheet.height)
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
                Text("Metroid", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                MetroidTab.values().forEach { tab ->
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
            MetroidTab.ANIMATIONS -> MetroidAnimationsTab(
                editorState, romParser, refreshKey, Modifier.weight(1f),
            )
            MetroidTab.COMPOSITIONS -> MetroidCompositionsTab(
                editorState, romParser, refreshKey, Modifier.weight(1f),
            )
            MetroidTab.COMPONENTS -> MetroidComponentsTab(
                editorState, romParser, refreshKey, Modifier.weight(1f),
            )
            MetroidTab.SOURCES -> MetroidSourcesTab(
                editorState, romParser, refreshKey,
                onEdit = { editingSheet = it },
                onReset = { editorState.resetMetroidObjSheet(); refreshKey++ },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetroidAnimationsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(MetroidSpritemap.ANIMATIONS.first()) }
    val animation by produceState<SpriteAnimation?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.renderMetroidAnimation(it, selected) }
    }
    MetroidWorkspace(
        modifier,
        left = {
            Text("Source animations", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            MetroidSpritemap.ANIMATIONS.forEach { choice ->
                val detail = when (choice.kind) {
                    MetroidSpritemap.AnimationKind.RUNTIME_INTRO -> "3 synchronized tracks · 93 ticks"
                    MetroidSpritemap.AnimationKind.RUNTIME_STEADY -> "3 synchronized tracks · 115 ticks"
                    MetroidSpritemap.AnimationKind.DRAINING -> "Drain insides + live companions"
                    MetroidSpritemap.AnimationKind.SHELL_INTRO -> "Sprite object \$34 → \$35"
                    MetroidSpritemap.AnimationKind.SHELL_STEADY -> "Object \$35 loop"
                    MetroidSpritemap.AnimationKind.ELECTRICITY_INTRO -> "Sprite object \$32 → \$33"
                    MetroidSpritemap.AnimationKind.ELECTRICITY_STEADY -> "Object \$33 loop"
                }
                MetroidChoice(choice.name, detail, selected == choice) { selected = choice }
            }
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Exact enemy + shell + electricity timing from banks \$A3/\$B4",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimationPlayer(animation, previewSize = 440, showExportButtons = false,
                modifier = Modifier.weight(1f))
        },
    )
}

@Composable
private fun MetroidCompositionsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier,
) {
    var selected by remember { mutableStateOf(MetroidSpritemap.COMPOSITIONS.first()) }
    val bitmap by produceState<ImageBitmap?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.renderMetroidComposition(it, selected)?.toMetroidBitmap() }
    }
    MetroidWorkspace(
        modifier,
        left = {
            Text("Complete runtime states", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            MetroidSpritemap.COMPOSITIONS.forEach { choice ->
                val layers = listOfNotNull(
                    "inside \$${choice.insideSnes.metroidHex6()}",
                    choice.shellSnes?.let { "shell \$${it.metroidHex6()}" },
                    choice.electricitySnes?.let { "electricity \$${it.metroidHex6()}" },
                ).joinToString(" · ")
                MetroidChoice(choice.name, layers, selected == choice) { selected = choice }
            }
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Layer order follows the runtime OAM allocation order.", fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MetroidPreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetroidComponentsTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    modifier: Modifier,
) {
    var kind by remember { mutableStateOf(MetroidSpritemap.ComponentKind.INSIDES) }
    val choices = MetroidSpritemap.COMPONENTS.filter { it.kind == kind }
    var selectedKey by remember { mutableStateOf(choices.first().key) }
    val selected = choices.firstOrNull { it.key == selectedKey } ?: choices.first()
    val bitmap by produceState<ImageBitmap?>(null, selected, romParser, refreshKey) {
        value = romParser?.let { editorState.renderMetroidComponent(it, selected)?.toMetroidBitmap() }
    }
    MetroidWorkspace(
        modifier,
        left = {
            Text("Runtime components", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MetroidSpritemap.ComponentKind.values().forEach { candidate ->
                    FilterChip(
                        selected = kind == candidate,
                        onClick = {
                            kind = candidate
                            selectedKey = MetroidSpritemap.COMPONENTS.first { it.kind == candidate }.key
                        },
                        label = { Text(candidate.displayName, fontSize = 9.sp) },
                        modifier = Modifier.height(27.dp),
                    )
                }
            }
            choices.forEach { choice ->
                MetroidChoice(choice.name, "\$${choice.snesAddress.metroidHex6()}", selected == choice) {
                    selectedKey = choice.key
                }
            }
        },
        right = {
            Text(selected.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                when (selected.kind) {
                    MetroidSpritemap.ComponentKind.INSIDES -> "Enemy-owned OAM · bank \$A3"
                    MetroidSpritemap.ComponentKind.SHELL -> "Shell sprite object · bank \$B4"
                    MetroidSpritemap.ComponentKind.ELECTRICITY -> "Electricity sprite object · bank \$B4"
                },
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetroidPreview(bitmap, romParser != null, selected.name, Modifier.weight(1f))
        },
    )
}

@Composable
private fun MetroidSourcesTab(
    editorState: EditorState,
    romParser: RomParser?,
    refreshKey: Int,
    onEdit: (MetroidSpriteEditorState.SourceSheet) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier,
) {
    val source = MetroidSpritemap.PIXEL_SOURCES.first()
    val sheet by produceState<MetroidSpriteEditorState.SourceSheet?>(null, romParser, refreshKey) {
        value = romParser?.let { editorState.loadMetroidSource(it, source) }
    }
    val hasCustom = editorState.hasCustomMetroidObjSheet()
    MetroidWorkspace(
        modifier,
        left = {
            Text("Pixel source", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            MetroidChoice(source.name, "Editable · \$${source.snesAddress.metroidHex6()}", true) {}
            Text(source.sourceLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "One \$1000-byte transfer supplies all three layers. Pixel edits update complete Metroid references live.",
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
            Divider(Modifier.padding(vertical = 4.dp))
            Text("Placement + timing · read-only", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                "Insides: enemy instructions in bank \$A3\nShell + electricity: sprite-object instructions in bank \$B4",
                fontSize = 9.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        right = {
            Text(source.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    sheet != null -> Image(sheet!!.toMetroidBitmap(), source.name,
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
private fun MetroidWorkspace(
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

@Composable
private fun MetroidChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
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
private fun MetroidPreview(bitmap: ImageBitmap?, romLoaded: Boolean, description: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Color(0xFF111122), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, description, Modifier.fillMaxSize(0.92f).padding(8.dp),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            romLoaded -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Text("Load a ROM to view Metroid", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun EnemySpritemap.AssembledSprite.toMetroidBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun MetroidSpriteEditorState.SourceSheet.toMetroidBitmap(): ImageBitmap {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

private fun Int.metroidHex6(): String = toString(16).uppercase().padStart(6, '0')
