package com.supermetroid.editor.rom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * SpriteSomething's animation vocabulary for the pinned community-Samus sheet format.
 *
 * Unlike the ROM animation IDs, this manifest names poses and describes how separate
 * sheet regions are layered. Keeping it bundled beside the matching layout lets a
 * community sheet use the same animation-first UI as ROM Samus without guessing at
 * file-name patterns or silently dropping cannon ports and other overlays.
 */
class SamusCommunityAnimationCatalog private constructor(
    val groups: List<Group>,
) {
    data class Group(
        val name: String,
        val variants: List<Variant>,
    )

    data class Variant(
        val name: String,
        val frames: List<Frame>,
    )

    data class Frame(
        val durationTicks: Int,
        val displacementX: Int,
        val displacementY: Int,
        val tiles: List<Tile>,
    )

    data class Tile(
        val imageName: String,
        val x: Int,
        val y: Int,
        val horizontalFlip: Boolean,
        val verticalFlip: Boolean,
        val crop: Crop?,
    )

    data class Crop(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    val variantCount: Int get() = groups.sumOf { it.variants.size }
    val frameCount: Int get() = groups.sumOf { group -> group.variants.sumOf { it.frames.size } }

    /** All manifest image names that cannot be resolved from this decoded sheet. */
    fun unresolvedImageNames(sheet: SamusCommunitySheetDecoder.DecodedSheet): Set<String> =
        groups.asSequence()
            .flatMap { it.variants.asSequence() }
            .flatMap { it.frames.asSequence() }
            .flatMap { it.tiles.asSequence() }
            .map { it.imageName }
            .filter { resolveImage(sheet, it) == null }
            .toSortedSet()

    /**
     * Assemble one named SpriteSomething animation into SMEDIT's normal player model.
     * A union canvas is shared by every frame, so movement is stable instead of each
     * pose being independently centered and visibly jittering during playback.
     */
    fun buildAnimation(
        sheet: SamusCommunitySheetDecoder.DecodedSheet,
        group: Group,
        variant: Variant,
    ): SpriteAnimation {
        val bounds = variantBounds(sheet, variant)
        val width = max(1, bounds.right - bounds.left + CANVAS_PADDING * 2)
        val height = max(1, bounds.bottom - bounds.top + CANVAS_PADDING * 2)
        val renderedFrames = variant.frames.mapIndexed { frameIndex, frame ->
            val pixels = IntArray(width * height)

            // SpriteSomething reverses manifest order before compositing. This puts
            // body art down first and overlays (notably cannon ports) on top.
            for (tile in frame.tiles.asReversed()) {
                val image = resolveImage(sheet, tile.imageName) ?: continue
                drawTile(
                    target = pixels,
                    targetWidth = width,
                    targetHeight = height,
                    image = image,
                    tile = tile,
                    targetX = tile.x + frame.displacementX - bounds.left + CANVAS_PADDING,
                    targetY = tile.y + frame.displacementY - bounds.top + CANVAS_PADDING,
                )
            }

            SpriteAnimationFrame(
                pixels = pixels,
                width = width,
                height = height,
                durationTicks = frame.durationTicks.coerceAtLeast(1),
                label = "${group.name} · ${humanize(variant.name)} · Frame ${frameIndex + 1}",
            )
        }
        return SpriteAnimation(
            name = "${group.name} · ${humanize(variant.name)}",
            frames = renderedFrames,
            loop = true,
        )
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun variantBounds(
        sheet: SamusCommunitySheetDecoder.DecodedSheet,
        variant: Variant,
    ): Bounds {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (frame in variant.frames) {
            for (tile in frame.tiles) {
                val image = resolveImage(sheet, tile.imageName) ?: continue
                val crop = tile.crop
                val tileWidth = crop?.let { max(0, it.right - it.left) } ?: image.width
                val tileHeight = crop?.let { max(0, it.bottom - it.top) } ?: image.height
                val x = tile.x + frame.displacementX
                val y = tile.y + frame.displacementY
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + tileWidth)
                bottom = max(bottom, y + tileHeight)
            }
        }
        return if (left == Int.MAX_VALUE) Bounds(0, 0, 1, 1) else Bounds(left, top, right, bottom)
    }

    private fun resolveImage(
        sheet: SamusCommunitySheetDecoder.DecodedSheet,
        requestedName: String,
    ): SamusCommunitySheetDecoder.DecodedImage? {
        sheet.images[requestedName]?.let { return it }
        if (requestedName.startsWith(OPTIONAL_PREFIX)) {
            // SpriteSomething normally controls these with its cannon-port option.
            // SMEDIT shows the complete authored pose, so use the sheet's port art.
            sheet.images[requestedName.removePrefix(OPTIONAL_PREFIX)]?.let { return it }
        }
        return null
    }

    private fun drawTile(
        target: IntArray,
        targetWidth: Int,
        targetHeight: Int,
        image: SamusCommunitySheetDecoder.DecodedImage,
        tile: Tile,
        targetX: Int,
        targetY: Int,
    ) {
        val crop = tile.crop
        val cropLeft = crop?.left ?: 0
        val cropTop = crop?.top ?: 0
        val cropRight = crop?.right ?: image.width
        val cropBottom = crop?.bottom ?: image.height
        val drawWidth = max(0, cropRight - cropLeft)
        val drawHeight = max(0, cropBottom - cropTop)
        for (drawY in 0 until drawHeight) {
            val sourceY = if (tile.verticalFlip) cropBottom - 1 - drawY else cropTop + drawY
            val destinationY = targetY + drawY
            if (sourceY !in 0 until image.height || destinationY !in 0 until targetHeight) continue
            for (drawX in 0 until drawWidth) {
                val sourceX = if (tile.horizontalFlip) cropRight - 1 - drawX else cropLeft + drawX
                val destinationX = targetX + drawX
                if (sourceX !in 0 until image.width || destinationX !in 0 until targetWidth) continue
                val source = image.pixels[sourceY * image.width + sourceX]
                if ((source ushr 24) != 0) target[destinationY * targetWidth + destinationX] = source
            }
        }
    }

    companion object {
        const val FORMAT_ID = SamusCommunitySheetDecoder.FORMAT_ID
        const val SOURCE_REVISION = "f3428d26c4299ed1f7f4e648de69d0e8d05f746a"
        const val RESOURCE = "/samus-community/spritesomething-animations-f3428d26.json.gz.b64"
        private const val OPTIONAL_PREFIX = "optional_"
        private const val CANVAS_PADDING = 2

        fun loadBundled(): SamusCommunityAnimationCatalog {
            val encoded = SamusCommunityAnimationCatalog::class.java.getResourceAsStream(RESOURCE)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Missing bundled community Samus animation manifest: $RESOURCE")
            val compressed = Base64.getMimeDecoder().decode(encoded)
            val manifest = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
            return parse(manifest)
        }

        fun parse(manifest: String): SamusCommunityAnimationCatalog {
            val root = Json.parseToJsonElement(manifest).jsonObject
            val groups = root.entries
                .filterNot { it.key == "\$schema" }
                .map { (groupName, variantsElement) ->
                    val variants = variantsElement.jsonObject.entries.map { (variantName, framesElement) ->
                        Variant(
                            name = variantName,
                            frames = framesElement.jsonArray.map(::parseFrame),
                        )
                    }
                    Group(groupName, variants)
                }
            return SamusCommunityAnimationCatalog(groups)
        }

        private fun parseFrame(element: kotlinx.serialization.json.JsonElement): Frame {
            val frame = element.jsonObject
            val displacement = frame["displacement"]?.jsonArray ?: JsonArray(emptyList())
            return Frame(
                durationTicks = frame["frames"]?.jsonPrimitive?.intOrNull ?: 1,
                displacementX = displacement.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
                displacementY = displacement.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0,
                tiles = frame["tiles"]?.jsonArray.orEmpty().map(::parseTile),
            )
        }

        private fun parseTile(element: kotlinx.serialization.json.JsonElement): Tile {
            val tile = element.jsonObject
            val position = tile["pos"]?.jsonArray ?: JsonArray(emptyList())
            val flip = tile["flip"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            val crop = tile["crop"]?.jsonArray?.let(::parseCrop)
            return Tile(
                imageName = tile.getValue("image").jsonPrimitive.content,
                x = position.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0,
                y = position.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0,
                horizontalFlip = 'h' in flip || "both" in flip,
                verticalFlip = 'v' in flip || "both" in flip,
                crop = crop,
            )
        }

        private fun parseCrop(values: JsonArray): Crop? {
            if (values.size != 4) return null
            return Crop(
                left = values[0].jsonPrimitive.intOrNull ?: return null,
                top = values[1].jsonPrimitive.intOrNull ?: return null,
                right = values[2].jsonPrimitive.intOrNull ?: return null,
                bottom = values[3].jsonPrimitive.intOrNull ?: return null,
            )
        }

        fun humanize(value: String): String =
            value.replace('_', ' ')
                .split(' ')
                .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
