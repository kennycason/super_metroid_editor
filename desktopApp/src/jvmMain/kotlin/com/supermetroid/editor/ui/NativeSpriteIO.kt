package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.supermetroid.editor.rom.EnemySpritemap
import com.supermetroid.editor.rom.RomVisualAssets
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpriteAnimation
import com.supermetroid.editor.rom.SpriteAnimationFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** A flattened OAM image whose pixels retain their exact owner in the native source sheet. */
internal data class NativeOamComposition(
    val sheet: NativeSpriteEditorState.Sheet,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val sourcePixelIndices: IntArray,
)

/** OAM uses the upper eight rows of the 256-colour cinematic palette. */
internal fun nativeOamPaletteRows(parser: RomParser, asset: RomVisualAssets.Asset): Set<Int> {
    require(asset.format == RomVisualAssets.Format.OAM) { "${asset.name} is not an OAM image" }
    val map = requireNotNull(EnemySpritemap(parser).parseSpritemap(asset.map)) {
        "Could not read ${asset.name} OAM layout"
    }
    return map.entries.mapTo(linkedSetOf()) { 8 + (it.palRow or asset.paletteBase) }
}

/**
 * Build an editable assembled image. OAM pieces must not overlap or reuse source pixels because
 * either case would make one edited PNG pixel ambiguous. The title logo is deliberately a clean,
 * one-to-one composition, including all transparent pixels inside its source tile rectangles.
 */
internal fun createNativeOamComposition(
    parser: RomParser,
    asset: RomVisualAssets.Asset,
    sheet: NativeSpriteEditorState.Sheet,
    paletteRow: Int,
): NativeOamComposition {
    require(asset.format == RomVisualAssets.Format.OAM) { "${asset.name} is not an OAM image" }
    require(asset.graphics == sheet.source.address) { "${asset.name} does not use ${sheet.source.name}" }
    require(!sheet.source.mode7) { "OAM images require 4bpp source tiles" }
    val rows = nativeOamPaletteRows(parser, asset)
    require(rows == setOf(paletteRow)) {
        "${asset.name} uses ${rows.size} palette rows and cannot be imported as one flattened image"
    }
    val map = requireNotNull(EnemySpritemap(parser).parseSpritemap(asset.map))
    require(map.entries.isNotEmpty()) { "${asset.name} has no OAM pieces" }
    val minX = map.entries.minOf { it.xOffset }
    val minY = map.entries.minOf { it.yOffset }
    val maxX = map.entries.maxOf { it.xOffset + if (it.is16x16) 16 else 8 }
    val maxY = map.entries.maxOf { it.yOffset + if (it.is16x16) 16 else 8 }
    val width = maxX - minX
    val height = maxY - minY
    val owners = IntArray(width * height) { -1 }
    val sourceDestinations = IntArray(sheet.pixels.size) { -1 }

    fun mapTile(entry: EnemySpritemap.OamEntry, tile: Int, offsetX: Int, offsetY: Int) {
        require(tile >= 0) { "${asset.name} references an invalid source tile" }
        for (py in 0 until 8) for (px in 0 until 8) {
            val sourceX = tile % 16 * 8 + if (entry.hFlip) 7 - px else px
            val sourceY = tile / 16 * 8 + if (entry.vFlip) 7 - py else py
            require(sourceX in 0 until sheet.width && sourceY in 0 until sheet.height) {
                "${asset.name} references source tile ${tile.toString(16).uppercase()} outside ${sheet.source.name}"
            }
            val sourceIndex = sourceY * sheet.width + sourceX
            require(sheet.indices[sourceIndex] >= 0)
            val destX = entry.xOffset - minX + offsetX + px
            val destY = entry.yOffset - minY + offsetY + py
            val destIndex = destY * width + destX
            require(owners[destIndex] == -1) {
                "${asset.name} contains overlapping OAM pieces and cannot be imported as one flattened image"
            }
            require(sourceDestinations[sourceIndex] == -1) {
                "${asset.name} reuses source pixels and cannot be imported as one flattened image"
            }
            owners[destIndex] = sourceIndex
            sourceDestinations[sourceIndex] = destIndex
        }
    }

    for (entry in map.entries) {
        val tile = entry.tileNum and 0x1FF
        if (entry.is16x16) {
            val tiles = intArrayOf(tile, tile + 1, tile + 16, tile + 17)
            val offsets = arrayOf(0 to 0, 8 to 0, 0 to 8, 8 to 8)
            for (index in tiles.indices) {
                val (x, y) = offsets[index]
                mapTile(entry, tiles[index], if (entry.hFlip) 8 - x else x, if (entry.vFlip) 8 - y else y)
            }
        } else {
            mapTile(entry, tile, 0, 0)
        }
    }
    return NativeOamComposition(
        sheet = sheet,
        pixels = IntArray(owners.size) { index -> owners[index].takeIf { it >= 0 }?.let(sheet.pixels::get) ?: 0 },
        width = width,
        height = height,
        sourcePixelIndices = owners,
    )
}

private fun matchNativePalette(pixel: Int, palette: IntArray): Int {
    if (pixel ushr 24 < 128) return 0
    val opaqueColors = palette.filter { it ushr 24 != 0 }
    require(opaqueColors.isNotEmpty()) { "The selected palette has no opaque colours" }
    if (pixel in opaqueColors) return pixel
    return opaqueColors.minBy { color ->
        val r = (pixel shr 16 and 255) - (color shr 16 and 255)
        val g = (pixel shr 8 and 255) - (color shr 8 and 255)
        val b = (pixel and 255) - (color and 255)
        r * r + g * g + b * b
    }
}

/** Import source sheets, not flattened compositions whose pixels can have several owners. */
internal fun importNativeSpritePng(file: File, sheet: NativeSpriteEditorState.Sheet): IntArray {
    val image = requireNotNull(ImageIO.read(file)) { "Could not read PNG" }
    require(image.width == sheet.width && image.height == sheet.height) {
        "Expected source sheet ${sheet.width}×${sheet.height}px, got ${image.width}×${image.height}px. Import the source PNG, not an assembled frame/animation sheet."
    }
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    return IntArray(pixels.size) { i -> matchNativePalette(pixels[i], sheet.palette) }
}

internal fun exportNativeSpritePng(file: File, sheet: NativeSpriteEditorState.Sheet) =
    exportFramePng(SpriteAnimationFrame(sheet.pixels, sheet.width, sheet.height), file)

internal fun exportNativeOamCompositionPng(file: File, composition: NativeOamComposition) =
    exportFramePng(SpriteAnimationFrame(composition.pixels, composition.width, composition.height), file)

internal fun importNativeOamCompositionPng(file: File, composition: NativeOamComposition): IntArray {
    val image = requireNotNull(ImageIO.read(file)) { "Could not read PNG" }
    require(image.width == composition.width && image.height == composition.height) {
        "Expected assembled image ${composition.width}×${composition.height}px, got ${image.width}×${image.height}px. " +
            "Import the assembled image exported for this selection."
    }
    val imported = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    return composition.sheet.pixels.copyOf().also { sourcePixels ->
        for (index in imported.indices) {
            val sourceIndex = composition.sourcePixelIndices[index]
            if (sourceIndex < 0) {
                require(imported[index] ushr 24 < 128) {
                    "The image has an opaque pixel outside the editable OAM pieces at " +
                        "${index % composition.width}, ${index / composition.width}"
                }
            } else {
                sourcePixels[sourceIndex] = matchNativePalette(imported[index], composition.sheet.palette)
            }
        }
    }
}

internal fun chooseSpriteSourcePng(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import Sprite Source PNG"
        fileFilter = FileNameExtensionFilter("PNG source sheet", "png")
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun chooseAssembledSpritePng(name: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import $name PNG"
        fileFilter = FileNameExtensionFilter("PNG assembled image", "png")
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Existing frame PNG / timed GIF / animation sheet controls for every native workspace. */
@Composable
internal fun NativeSpriteAnimationPlayer(animation: SpriteAnimation?, modifier: Modifier = Modifier, previewSize: Int = 440) {
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun export(file: File?, write: (File) -> Unit) {
        if (file == null) return
        scope.launch {
            status = "Exporting ${file.name}…"
            status = runCatching { withContext(Dispatchers.IO) { write(file) } }.fold(
                onSuccess = { "Exported ${file.name}" }, onFailure = { "Export failed: ${it.message}" },
            )
        }
    }
    val name = exportSafeName(animation?.name ?: "sprite")
    BoxWithConstraints(modifier) {
        // Reserve space for transport/export controls instead of letting a fixed preview push them out of view.
        val controlsHeight = 200 + LocalEditorTheme.current.fontSize.value.detail.value * 3
        val fittedSize = if (maxHeight.value.isFinite()) minOf(previewSize, (maxHeight.value - controlsHeight).toInt().coerceAtLeast(96)) else previewSize
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            AnimationPlayer(animation, previewSize = fittedSize,
                onExportPng = { frame, index -> export(choosePngFile("Export Sprite Frame", "${name}_${index + 1}.png")) { exportFramePng(frame, it) } },
                onExportGif = { frames -> export(chooseGifFile("Export Sprite Animation", "$name.gif")) { exportAnimationGif(frames, it) } },
                onExportSheet = { frames -> export(choosePngFile("Export Animation Sheet", "${name}_animation.png")) { exportAnimationSheet(frames, it) } },
            )
            if (status.isNotBlank()) Text(status, fontSize = LocalEditorTheme.current.fontSize.value.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
