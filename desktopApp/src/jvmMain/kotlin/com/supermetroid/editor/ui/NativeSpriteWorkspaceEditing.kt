package com.supermetroid.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.supermetroid.editor.rom.*
import java.awt.image.BufferedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class NativeSpriteTarget(val sourceKey: String, val paletteBase: Int, val rows: Int, val defaultRow: Int = 0) {
    val name: String get() = NativeSpriteAssets.source(sourceKey).name
}

@Composable
internal fun NativeSpriteSourceControls(
    parser: RomParser, editor: EditorState, target: NativeSpriteTarget,
    onEdit: (NativeSpriteEditorState.Sheet) -> Unit,
    onPaletteRowChanged: (Int) -> Unit = {},
) {
    var row by remember(target) { mutableStateOf(target.defaultRow) }
    var colorIndex by remember(target, row) { mutableStateOf(-1) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    var status by remember(target) { mutableStateOf("") }
    var transferring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val paletteAddress = target.paletteBase + row * 32
    val asmRevision = editor.asmVisualSourceRevision
    val sourceStatus = remember(target.sourceKey, asmRevision, editor.spriteVersion) {
        editor.nativeSpriteEditingStatus(target.sourceKey)
    }
    val paletteStatus = remember(paletteAddress, asmRevision, editor.paletteVersion) {
        editor.nativeSpritePaletteEditingStatus(paletteAddress)
    }
    val palette = remember(parser, target, row, editor.editVersion, editor.paletteVersion, asmRevision) {
        editor.nativeSprites.palette(parser, paletteAddress)
    }
    val fs = LocalEditorTheme.current.fontSize.value
    Text("Source: ${target.name}", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpriteWorkspaceAction("Edit source pixels", {
            error = runCatching { onEdit(editor.nativeSprites.loadSheet(parser, target.sourceKey,
                if (NativeSpriteAssets.source(target.sourceKey).mode7) target.paletteBase else paletteAddress)) }.exceptionOrNull()?.message
        }, enabled = sourceStatus.editable)
        SpriteWorkspaceAction("Export source PNG", {
            val sheet = runCatching { editor.nativeSprites.loadSheet(parser, target.sourceKey,
                if (NativeSpriteAssets.source(target.sourceKey).mode7) target.paletteBase else paletteAddress) }
                .getOrElse { error = it.message; return@SpriteWorkspaceAction }
            val file = choosePngFile("Export Sprite Source PNG", "${exportSafeName(target.name)}_${target.sourceKey}_source.png") ?: return@SpriteWorkspaceAction
            scope.launch {
                transferring = true
                val result = runCatching { withContext(Dispatchers.IO) { exportNativeSpritePng(file, sheet) } }
                error = result.exceptionOrNull()?.message
                status = if (result.isSuccess) "Exported ${file.name}" else ""
                transferring = false
            }
        }, enabled = sourceStatus.editable && !transferring)
        SpriteWorkspaceAction("Import source PNG", {
            val sheet = runCatching { editor.nativeSprites.loadSheet(parser, target.sourceKey,
                if (NativeSpriteAssets.source(target.sourceKey).mode7) target.paletteBase else paletteAddress) }
                .getOrElse { error = it.message; return@SpriteWorkspaceAction }
            val file = chooseSpriteSourcePng() ?: return@SpriteWorkspaceAction
            val owner = editor.project
            scope.launch {
                transferring = true
                val result = runCatching {
                    val pixels = withContext(Dispatchers.IO) { importNativeSpritePng(file, sheet) }
                    require(editor.project === owner) { "Project changed during import" }
                    editor.nativeSprites.applyPixels(parser, sheet, pixels)
                }
                error = result.exceptionOrNull()?.message
                status = if (result.isSuccess) "Imported ${file.name} · colors matched to the selected palette" else ""
                transferring = false
            }
        }, enabled = sourceStatus.editable && !transferring)
        if (sourceStatus.editable && editor.nativeSprites.hasTiles(target.sourceKey)) {
            SpriteWorkspaceAction("Restore source pixels", { editor.nativeSprites.resetTiles(target.sourceKey) })
        }
        if (paletteStatus.editable && editor.nativeSprites.hasPalette(parser, paletteAddress)) {
            SpriteWorkspaceAction("Restore palette", { editor.nativeSprites.resetPalette(parser, paletteAddress) })
        }
    }
    if (target.rows > 1) SpriteOptionSelector((1..target.rows).map { "Palette $it" }, row, { row = it; onPaletteRowChanged(it) }, Modifier.fillMaxWidth())
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        palette.forEachIndexed { index, color ->
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(3.dp))
                .clickable(enabled = index != 0 && paletteStatus.editable) { colorIndex = if (colorIndex == index) -1 else index }
                .background(if (index == 0) MaterialTheme.colorScheme.surface else Color(color))
                .border(if (colorIndex == index) 2.dp else 1.dp,
                    if (colorIndex == index) Color.White else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(3.dp)))
        }
    }
    if (colorIndex in 1..15 && paletteStatus.editable) HsvColorPicker(
        bgr555 = EnemySpriteGraphics.argbToSnesColor(palette[colorIndex]),
        onColorChanged = { bgr ->
            editor.nativeSprites.savePalette(parser, paletteAddress, palette.copyOf().also { it[colorIndex] = EnemySpriteGraphics.snesColorToArgb(bgr) })
        },
    )
    sourceStatus.message?.let {
        Text(it, fontSize = fs.detail, color = if (sourceStatus.editable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
    paletteStatus.message?.let {
        Text(it, fontSize = fs.detail, color = if (paletteStatus.editable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
    if (NativeSpriteAssets.source(target.sourceKey).compressed && !sourceStatus.sourceManaged) {
        Text("Compressed source: Apply must fit its original ROM space.", fontSize = fs.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = fs.detail) }
    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = fs.detail)
}

private fun SpriteAnimation.bitmaps() = frames.take(32).map { frame ->
    BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB).apply {
        setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
    }.toComposeImageBitmap()
}

@Composable
internal fun NativeSpritePixelEditing(
    parser: RomParser, editor: EditorState, sheet: NativeSpriteEditorState.Sheet,
    renderPreview: (RomParser) -> SpriteAnimation?, onClose: () -> Unit, modifier: Modifier,
) {
    var error by remember(sheet) { mutableStateOf<String?>(null) }
    val references = remember(sheet, editor.spriteVersion, editor.paletteVersion, editor.asmVisualSourceRevision) {
        renderPreview(editor.nativeSprites.previewParser(parser))?.bitmaps().orEmpty()
    }
    Column(modifier.fillMaxSize()) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        SpritePixelEditor(
            label = "${sheet.source.name} · source ${sheet.source.key}", initialPixels = sheet.pixels,
            imageWidth = sheet.width, imageHeight = sheet.height, fixedPalette = sheet.palette,
            referenceFrames = references,
            liveReferenceFrames = if (sheet.source.compressed) null else { pixels, _, _ ->
                runCatching { renderPreview(editor.nativeSprites.previewWithPixels(parser, sheet, pixels))?.bitmaps().orEmpty() }.getOrDefault(emptyList())
            },
            onApply = { pixels -> error = runCatching { editor.nativeSprites.applyPixels(parser, sheet, pixels) }.exceptionOrNull()?.message },
            onClose = onClose, modifier = Modifier.weight(1f),
        )
    }
}
