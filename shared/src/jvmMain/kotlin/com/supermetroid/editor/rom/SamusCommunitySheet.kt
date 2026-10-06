package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * The SpriteSomething/MapRandoSprites Samus PNG contract.
 *
 * This is an image/layout decoder only. It deliberately does not write a ROM: custom
 * sheets require SpriteSomething's expanded-ROM graphics, DMA, tilemap, palette, and
 * code rewrite, which will be implemented as a separately guarded export stage.
 */
class SamusCommunitySheetDecoder(
    val layout: SamusCommunityLayout = SamusCommunityLayout.loadBundled(),
) {
    enum class Severity { ERROR, WARNING, INFO }

    data class Issue(
        val severity: Severity,
        val code: String,
        val message: String,
        val imageName: String? = null,
    )

    data class DecodedImage(
        val name: String,
        val width: Int,
        val height: Int,
        /** Palette-applied ARGB preview. Index zero is transparent. */
        val pixels: IntArray,
        /** SpriteSomething-compatible palette indices, or null for palette_block. */
        val paletteIndices: ByteArray?,
        val paletteInterval: IntRange?,
    )

    data class DecodedSheet(
        val sourceName: String,
        val width: Int,
        val height: Int,
        val images: Map<String, DecodedImage>,
        /** The 15 x 7 palette block, flattened row-major as RGB values. */
        val masterPaletteRgb: IntArray,
        val opaqueIndexedPixelCount: Int,
        val quantizedPixelCount: Int,
    )

    data class Result(
        val sheet: DecodedSheet?,
        val issues: List<Issue>,
    ) {
        val isValid: Boolean
            get() = sheet != null && issues.none { it.severity == Severity.ERROR }
    }

    fun decode(file: File): Result {
        if (!file.isFile) {
            return failure("FILE_NOT_FOUND", "Community Samus sheet does not exist: ${file.path}")
        }
        val image = try {
            ImageIO.read(file)
        } catch (error: Exception) {
            return failure("PNG_READ_FAILED", "Could not read ${file.name}: ${error.message}")
        } ?: return failure("PNG_READ_FAILED", "${file.name} is not a supported image")
        return decode(image, file.name)
    }

    fun decode(image: BufferedImage, sourceName: String = "community-samus.png"): Result {
        val issues = layout.validate().toMutableList()
        if (image.width != FORMAT_WIDTH || image.height != FORMAT_HEIGHT) {
            issues += Issue(
                Severity.ERROR,
                "WRONG_DIMENSIONS",
                "$sourceName is ${image.width}x${image.height}; expected ${FORMAT_WIDTH}x$FORMAT_HEIGHT",
            )
            return Result(null, issues)
        }
        if (issues.any { it.severity == Severity.ERROR }) return Result(null, issues)

        val rawImages = linkedMapOf<String, RawImage>()
        var sheetY = 0
        for ((rowIndex, row) in layout.rows.withIndex()) {
            val rowGeometry = layout.rowGeometry(row)
            val rowX = (image.width - rowGeometry.width) / 2
            var cellX = 0
            for (imageName in row) {
                val definition = layout.definition(imageName)
                val bounds = layout.boundingBox(imageName)
                val spacer = definition.directInt("spacer") ?: 0
                cellX -= min(spacer, 0)

                val raw = IntArray(bounds.width * bounds.height)
                val shift = layout.intArrayProperty(imageName, "shift") ?: intArrayOf(0, 0)
                val scale = layout.intProperty(imageName, "scale") ?: 1
                val areas = buildList {
                    add(layout.intArrayProperty(imageName, "dimensions")!!)
                    layout.intMatrixProperty(imageName, "extra area")?.let(::addAll)
                }
                var regionFailed = false
                for (unscaledArea in areas) {
                    val area = unscaledArea.map { it * scale }.toIntArray()
                    val sourceX = rowX + layout.borderSize + cellX + area[0] - bounds.left
                    val sourceY = sheetY + layout.borderSize + shift[1] + area[1] - rowGeometry.top
                    val destinationX = area[0] - bounds.left
                    val destinationY = area[1] - bounds.top + shift[1]
                    val copyWidth = area[2] - area[0]
                    val copyHeight = area[3] - area[1]
                    if (
                        sourceX < 0 || sourceY < 0 ||
                        sourceX + copyWidth > image.width || sourceY + copyHeight > image.height ||
                        destinationX < 0 || destinationY < 0 ||
                        destinationX + copyWidth > bounds.width ||
                        destinationY + copyHeight > bounds.height
                    ) {
                        regionFailed = true
                        issues += Issue(
                            Severity.ERROR,
                            "REGION_OUT_OF_BOUNDS",
                            "$imageName has an out-of-bounds layout region in row $rowIndex",
                            imageName,
                        )
                        break
                    }
                    for (y in 0 until copyHeight) {
                        val sourcePixels = image.getRGB(sourceX, sourceY + y, copyWidth, 1, null, 0, copyWidth)
                        sourcePixels.copyInto(
                            raw,
                            destinationOffset = (destinationY + y) * bounds.width + destinationX,
                        )
                    }
                }
                if (!regionFailed) {
                    val targetWidth = bounds.width / scale
                    val targetHeight = bounds.height / scale
                    rawImages[imageName] = if (scale == 1) {
                        RawImage(bounds.width, bounds.height, raw)
                    } else {
                        RawImage(
                            targetWidth,
                            targetHeight,
                            resizePillowBicubic(raw, bounds.width, bounds.height, targetWidth, targetHeight),
                        )
                    }
                }
                cellX += bounds.width + 2 * layout.borderSize + max(spacer, 0)
            }
            sheetY += rowGeometry.height
        }

        if (issues.any { it.severity == Severity.ERROR }) return Result(null, issues)
        if (rawImages.size != IMAGE_COUNT) {
            issues += Issue(
                Severity.ERROR,
                "IMAGE_COUNT_MISMATCH",
                "Decoded ${rawImages.size} named images; expected $IMAGE_COUNT",
            )
            return Result(null, issues)
        }

        val paletteBlock = rawImages["palette_block"]
            ?: return failure("MISSING_PALETTE_BLOCK", "Layout did not decode palette_block", issues)
        if (paletteBlock.width != 15 || paletteBlock.height != 7) {
            return failure(
                "INVALID_PALETTE_BLOCK",
                "palette_block is ${paletteBlock.width}x${paletteBlock.height}; expected 15x7",
                issues,
            )
        }
        val masterPalette = paletteBlock.pixels.map { it and 0x00FFFFFF }.toIntArray()
        val decoded = linkedMapOf<String, DecodedImage>()
        decoded["palette_block"] = DecodedImage(
            "palette_block",
            paletteBlock.width,
            paletteBlock.height,
            paletteBlock.pixels,
            null,
            null,
        )

        var opaqueIndexedPixels = 0
        var quantizedPixels = 0
        for ((imageName, raw) in rawImages) {
            if (imageName == "palette_block") continue
            val intervalValues = layout.intArrayProperty(imageName, "import palette interval")
            if (intervalValues == null || intervalValues.size != 2) {
                issues += Issue(
                    Severity.ERROR,
                    "MISSING_PALETTE_INTERVAL",
                    "$imageName has no import palette interval",
                    imageName,
                )
                continue
            }
            val start = intervalValues[0]
            val endExclusive = intervalValues[1]
            if (start < 0 || endExclusive <= start || endExclusive > masterPalette.size) {
                issues += Issue(
                    Severity.ERROR,
                    "INVALID_PALETTE_INTERVAL",
                    "$imageName palette interval [$start, $endExclusive) is outside 0..${masterPalette.size}",
                    imageName,
                )
                continue
            }
            val interval = start until endExclusive
            val indices = ByteArray(raw.pixels.size)
            val preview = IntArray(raw.pixels.size)
            for (index in raw.pixels.indices) {
                val argb = raw.pixels[index]
                if ((argb ushr 24) != 0xFF) continue
                val rgb = argb and 0x00FFFFFF
                val (paletteOffset, exact) = closestColor(rgb, masterPalette, interval)
                val paletteIndex = paletteOffset + 1 // SpriteSomething reserves zero for transparency.
                indices[index] = paletteIndex.toByte()
                preview[index] = 0xFF000000.toInt() or masterPalette[start + paletteOffset]
                opaqueIndexedPixels++
                if (!exact) quantizedPixels++
            }
            decoded[imageName] = DecodedImage(
                imageName,
                raw.width,
                raw.height,
                preview,
                indices,
                interval,
            )
        }

        if (issues.any { it.severity == Severity.ERROR }) return Result(null, issues)
        if (opaqueIndexedPixels == 0) {
            issues += Issue(
                Severity.WARNING,
                "FULLY_TRANSPARENT",
                "$sourceName contains no opaque pixels in any named sprite region",
            )
        }
        if (quantizedPixels > 0) {
            issues += Issue(
                Severity.INFO,
                "PALETTE_QUANTIZATION",
                "$quantizedPixels opaque pixels use colors outside their exact palette interval and will be quantized",
            )
        }
        if (paletteBlock.pixels.all { (it ushr 24) == 0 }) {
            issues += Issue(
                Severity.INFO,
                "TRANSPARENT_PALETTE_BLOCK",
                "The palette block is transparent; RGB values decode as black",
                "palette_block",
            )
        }

        return Result(
            DecodedSheet(
                sourceName = sourceName,
                width = image.width,
                height = image.height,
                images = decoded,
                masterPaletteRgb = masterPalette,
                opaqueIndexedPixelCount = opaqueIndexedPixels,
                quantizedPixelCount = quantizedPixels,
            ),
            issues,
        )
    }

    private fun closestColor(rgb: Int, palette: IntArray, interval: IntRange): Pair<Int, Boolean> {
        val red = (rgb ushr 16) and 0xFF
        val green = (rgb ushr 8) and 0xFF
        val blue = rgb and 0xFF
        var bestOffset = 0
        var bestDistance = Int.MAX_VALUE
        for ((offset, paletteIndex) in interval.withIndex()) {
            val candidate = palette[paletteIndex]
            val dr = red - ((candidate ushr 16) and 0xFF)
            val dg = green - ((candidate ushr 8) and 0xFF)
            val db = blue - (candidate and 0xFF)
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                bestDistance = distance
                bestOffset = offset
                if (distance == 0) break
            }
        }
        return bestOffset to (bestDistance == 0)
    }

    private fun failure(code: String, message: String, existing: List<Issue> = emptyList()): Result =
        Result(null, existing + Issue(Severity.ERROR, code, message))

    private data class RawImage(val width: Int, val height: Int, val pixels: IntArray)

    companion object {
        const val FORMAT_ID = "spritesomething-f3428d26"
        const val FORMAT_WIDTH = 876
        const val FORMAT_HEIGHT = 2543
        const val ROW_COUNT = 52
        const val IMAGE_COUNT = 637
        const val DMA_SEQUENCE_COUNT = 570

        /**
         * SpriteSomething calls Pillow's RGBA `resize()` without a filter argument,
         * which means Pillow bicubic. Keep the same separable cubic kernel and 22-bit
         * coefficient rounding so imported palette bytes match its output exactly.
         */
        private fun resizePillowBicubic(
            source: IntArray,
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): IntArray {
            // Pillow temporarily converts RGBA to premultiplied RGBa around any
            // non-nearest resize, then removes premultiplication afterward.
            val premultiplied = IntArray(source.size) { index -> premultiply(source[index]) }
            val horizontal = coefficients(sourceWidth, targetWidth)
            val temporary = IntArray(targetWidth * sourceHeight)
            for (y in 0 until sourceHeight) {
                for (targetX in 0 until targetWidth) {
                    val coefficient = horizontal[targetX]
                    val sums = longArrayOf(ROUNDING, ROUNDING, ROUNDING, ROUNDING)
                    for (offset in 0 until coefficient.count) {
                        val argb = premultiplied[y * sourceWidth + coefficient.start + offset]
                        val weight = coefficient.weights[offset].toLong()
                        sums[0] += ((argb ushr 16) and 0xFF) * weight
                        sums[1] += ((argb ushr 8) and 0xFF) * weight
                        sums[2] += (argb and 0xFF) * weight
                        sums[3] += ((argb ushr 24) and 0xFF) * weight
                    }
                    temporary[y * targetWidth + targetX] = argb(sums)
                }
            }

            val vertical = coefficients(sourceHeight, targetHeight)
            val target = IntArray(targetWidth * targetHeight)
            for (targetY in 0 until targetHeight) {
                val coefficient = vertical[targetY]
                for (x in 0 until targetWidth) {
                    val sums = longArrayOf(ROUNDING, ROUNDING, ROUNDING, ROUNDING)
                    for (offset in 0 until coefficient.count) {
                        val argb = temporary[(coefficient.start + offset) * targetWidth + x]
                        val weight = coefficient.weights[offset].toLong()
                        sums[0] += ((argb ushr 16) and 0xFF) * weight
                        sums[1] += ((argb ushr 8) and 0xFF) * weight
                        sums[2] += (argb and 0xFF) * weight
                        sums[3] += ((argb ushr 24) and 0xFF) * weight
                    }
                    target[targetY * targetWidth + x] = unpremultiply(argb(sums))
                }
            }
            return target
        }

        private data class ResampleCoefficients(
            val start: Int,
            val count: Int,
            val weights: IntArray,
        )

        private fun coefficients(sourceSize: Int, targetSize: Int): List<ResampleCoefficients> {
            val scale = sourceSize.toDouble() / targetSize
            val filterScale = max(1.0, scale)
            val support = 2.0 * filterScale
            val inverseFilterScale = 1.0 / filterScale
            return List(targetSize) { targetIndex ->
                val center = (targetIndex + 0.5) * scale
                val start = max(0, (center - support + 0.5).toInt())
                val end = min(sourceSize, (center + support + 0.5).toInt())
                val raw = DoubleArray(end - start) { offset ->
                    bicubic((offset + start - center + 0.5) * inverseFilterScale)
                }
                val total = raw.sum()
                val weights = IntArray(raw.size) { index ->
                    val normalized = if (total == 0.0) 0.0 else raw[index] / total
                    if (normalized < 0.0) {
                        (-0.5 + normalized * PRECISION).toInt()
                    } else {
                        (0.5 + normalized * PRECISION).toInt()
                    }
                }
                ResampleCoefficients(start, end - start, weights)
            }
        }

        private fun bicubic(input: Double): Double {
            val x = kotlin.math.abs(input)
            return when {
                x < 1.0 -> ((1.5 * x - 2.5) * x * x) + 1.0
                x < 2.0 -> -0.5 * (((x - 5.0) * x + 8.0) * x - 4.0)
                else -> 0.0
            }
        }

        private fun argb(sums: LongArray): Int {
            fun channel(index: Int): Int = (sums[index] shr PRECISION_BITS).coerceIn(0L, 255L).toInt()
            return (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }

        private fun premultiply(argb: Int): Int {
            val alpha = (argb ushr 24) and 0xFF
            fun channel(shift: Int): Int {
                val product = ((argb ushr shift) and 0xFF) * alpha + 128
                return ((product ushr 8) + product) ushr 8
            }
            return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun unpremultiply(argb: Int): Int {
            val alpha = (argb ushr 24) and 0xFF
            if (alpha == 0 || alpha == 0xFF) return argb
            fun channel(shift: Int): Int =
                (255 * ((argb ushr shift) and 0xFF) / alpha).coerceIn(0, 255)
            return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private const val PRECISION_BITS = 22
        private const val PRECISION = 1 shl PRECISION_BITS
        private const val ROUNDING = 1L shl (PRECISION_BITS - 1)
    }
}

class SamusCommunityLayout private constructor(
    val borderColor: IntArray,
    val borderSize: Int,
    val rows: List<List<String>>,
    val dmaSequence: List<String>,
    private val definitions: Map<String, JsonObject>,
) {
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class RowGeometry(val width: Int, val height: Int, val top: Int, val bottom: Int)

    fun definition(name: String): JsonObject =
        definitions[name] ?: error("Unknown community Samus image $name")

    fun boundingBox(name: String): Rect {
        val raw = rawBoundingBox(name)
        val scale = intProperty(name, "scale") ?: 1
        val shift = intArrayProperty(name, "shift") ?: intArrayOf(0, 0)
        return Rect(
            raw.left * scale + shift[0],
            raw.top * scale + shift[1],
            raw.right * scale + shift[0],
            raw.bottom * scale + shift[1],
        )
    }

    fun rowGeometry(row: List<String>): RowGeometry {
        var width = 0
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (name in row) {
            val bounds = boundingBox(name)
            val spacer = definition(name).directInt("spacer") ?: 0
            width += bounds.width + 2 * borderSize + kotlin.math.abs(spacer)
            top = min(top, bounds.top)
            bottom = max(bottom, bounds.bottom)
        }
        return RowGeometry(width, bottom - top + 2 * borderSize, top, bottom)
    }

    fun expectedWidth(): Int = rows.maxOf { rowGeometry(it).width }

    fun expectedHeight(): Int = rows.sumOf { rowGeometry(it).height }

    fun intProperty(name: String, property: String): Int? =
        inheritedProperty(name, property)?.jsonPrimitive?.int

    fun intArrayProperty(name: String, property: String): IntArray? =
        inheritedProperty(name, property)?.jsonArray?.map { it.jsonPrimitive.int }?.toIntArray()

    fun intMatrixProperty(name: String, property: String): List<IntArray>? =
        inheritedProperty(name, property)?.jsonArray?.map { row ->
            row.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        }

    fun validate(): List<SamusCommunitySheetDecoder.Issue> {
        val issues = mutableListOf<SamusCommunitySheetDecoder.Issue>()
        val flattened = rows.flatten()
        if (rows.size != SamusCommunitySheetDecoder.ROW_COUNT) {
            issues += error("LAYOUT_ROW_COUNT", "Layout has ${rows.size} rows; expected ${SamusCommunitySheetDecoder.ROW_COUNT}")
        }
        if (definitions.size != SamusCommunitySheetDecoder.IMAGE_COUNT) {
            issues += error("LAYOUT_IMAGE_COUNT", "Layout has ${definitions.size} definitions; expected ${SamusCommunitySheetDecoder.IMAGE_COUNT}")
        }
        if (flattened.size != flattened.distinct().size) {
            issues += error("DUPLICATE_LAYOUT_IMAGE", "The layout contains duplicate image names")
        }
        val missingFromRows = definitions.keys - flattened.toSet()
        val unknownInRows = flattened.toSet() - definitions.keys
        if (missingFromRows.isNotEmpty()) {
            issues += error("UNPLACED_LAYOUT_IMAGES", "${missingFromRows.size} image definitions are absent from layout rows")
        }
        if (unknownInRows.isNotEmpty()) {
            issues += error("UNKNOWN_LAYOUT_IMAGES", "${unknownInRows.size} row images have no definition")
        }
        if (dmaSequence.size != SamusCommunitySheetDecoder.DMA_SEQUENCE_COUNT) {
            issues += error("DMA_SEQUENCE_COUNT", "DMA sequence has ${dmaSequence.size} entries; expected ${SamusCommunitySheetDecoder.DMA_SEQUENCE_COUNT}")
        }
        val unknownDma = dmaSequence.toSet() - definitions.keys
        if (unknownDma.isNotEmpty()) {
            issues += error("UNKNOWN_DMA_IMAGES", "${unknownDma.size} DMA entries have no image definition")
        }
        for (name in definitions.keys) {
            try {
                val dimensions = intArrayProperty(name, "dimensions")
                if (dimensions == null || dimensions.size != 4) {
                    issues += error("INVALID_IMAGE_DIMENSIONS", "$name has no four-value dimensions", name)
                } else {
                    boundingBox(name)
                    intArrayProperty(name, "import palette interval")
                }
            } catch (problem: Exception) {
                issues += error("INVALID_IMAGE_DEFINITION", "$name: ${problem.message}", name)
            }
        }
        val width = runCatching(::expectedWidth).getOrNull()
        val height = runCatching(::expectedHeight).getOrNull()
        if (width != SamusCommunitySheetDecoder.FORMAT_WIDTH || height != SamusCommunitySheetDecoder.FORMAT_HEIGHT) {
            issues += error(
                "LAYOUT_CANVAS_SIZE",
                "Layout computes ${width ?: "?"}x${height ?: "?"}; expected ${SamusCommunitySheetDecoder.FORMAT_WIDTH}x${SamusCommunitySheetDecoder.FORMAT_HEIGHT}",
            )
        }
        return issues
    }

    private fun rawBoundingBox(name: String): Rect {
        val dimensions = intArrayProperty(name, "dimensions")
            ?: error("$name has no dimensions")
        require(dimensions.size == 4) { "$name dimensions must contain four values" }
        var left = dimensions[0]
        var top = dimensions[1]
        var right = dimensions[2]
        var bottom = dimensions[3]
        for (area in intMatrixProperty(name, "extra area").orEmpty()) {
            require(area.size == 4) { "$name extra area must contain four values" }
            left = min(left, area[0])
            top = min(top, area[1])
            right = max(right, area[2])
            bottom = max(bottom, area[3])
        }
        require(right > left && bottom > top) { "$name has empty or inverted dimensions" }
        return Rect(left, top, right, bottom)
    }

    private fun inheritedProperty(name: String, property: String): JsonElement? {
        val visited = mutableSetOf<String>()
        var current = name
        repeat(100) {
            if (!visited.add(current)) error("Parent cycle while resolving $name.$property")
            val definition = definitions[current] ?: error("Unknown parent $current for $name")
            definition[property]?.let { return it }
            val parent = definition["parent"]?.jsonPrimitive?.content ?: return null
            current = parent
        }
        error("Parent chain exceeded 100 entries while resolving $name.$property")
    }

    private fun error(code: String, message: String, imageName: String? = null) =
        SamusCommunitySheetDecoder.Issue(
            SamusCommunitySheetDecoder.Severity.ERROR,
            code,
            message,
            imageName,
        )

    companion object {
        const val RESOURCE = "/samus-community/spritesomething-layout-f3428d26.json"

        fun loadBundled(): SamusCommunityLayout {
            val json = SamusCommunityLayout::class.java.getResourceAsStream(RESOURCE)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Missing bundled SpriteSomething layout $RESOURCE")
            return parse(json)
        }

        fun parse(json: String): SamusCommunityLayout {
            val root = Json.parseToJsonElement(json).jsonObject
            return SamusCommunityLayout(
                borderColor = root.getValue("border_color").jsonArray.map { it.jsonPrimitive.int }.toIntArray(),
                borderSize = root.getValue("border_size").jsonPrimitive.int,
                rows = root.getValue("layout").jsonArray.map { row ->
                    row.jsonArray.map { it.jsonPrimitive.content }
                },
                dmaSequence = root.getValue("dma_sequence").jsonArray.map { it.jsonPrimitive.content },
                definitions = root.getValue("images").jsonObject.mapValues { (_, value) -> value.jsonObject },
            )
        }
    }
}

data class MapRandoSamusSprite(
    val name: String,
    val version: Int,
    val displayName: String,
    val authors: List<String>,
    val creditsName: String?,
    val category: String,
)

object MapRandoSamusCatalog {
    fun parse(file: File): List<MapRandoSamusSprite> = parse(file.readText())

    fun parse(json: String): List<MapRandoSamusSprite> =
        Json.parseToJsonElement(json).jsonArray.flatMap { categoryElement ->
            val category = categoryElement.jsonObject
            val categoryName = category.getValue("category_name").jsonPrimitive.content
            category.getValue("sprites").jsonArray.map { spriteElement ->
                val sprite = spriteElement.jsonObject
                MapRandoSamusSprite(
                    name = sprite.getValue("name").jsonPrimitive.content,
                    version = sprite.getValue("version").jsonPrimitive.int,
                    displayName = sprite.getValue("display_name").jsonPrimitive.content,
                    authors = sprite.getValue("authors").jsonArray.map { it.jsonPrimitive.content },
                    creditsName = sprite["credits_name"]?.jsonPrimitive?.content,
                    category = categoryName,
                )
            }
        }
}

private fun JsonObject.directInt(name: String): Int? = this[name]?.jsonPrimitive?.int
