package com.supermetroid.editor.rom

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * GIF89a encoder for animated sprites.
 *
 * Uses the JDK GIF writer instead of hand-rolled LZW so exported animations are
 * accepted consistently by browsers, Preview, and ImageIO.
 */
object GifEncoder {

    /**
     * Encode an animated GIF from sprite animation frames.
     *
     * @param frames  List of ARGB pixel arrays with dimensions
     * @param loop    Number of loops (0 = infinite)
     * @return GIF file bytes
     */
    fun encode(frames: List<SpriteAnimationFrame>, loop: Int = 0): ByteArray {
        require(frames.isNotEmpty()) { "At least one frame required" }

        val width = frames.maxOf { it.width }
        val height = frames.maxOf { it.height }
        val palette = buildGlobalPalette(frames)
        val colorModel = buildColorModel(palette)

        val out = ByteArrayOutputStream(width * height * frames.size)
        val writer = ImageIO.getImageWritersByFormatName("gif").asSequence().firstOrNull()
            ?: error("No GIF ImageIO writer available")
        try {
            MemoryCacheImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.prepareWriteSequence(null)
                for ((index, frame) in frames.withIndex()) {
                    val image = indexedFrameImage(frame, width, height, palette, colorModel)
                    val metadata = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier(image),
                        writer.defaultWriteParam,
                    )
                    configureFrameMetadata(
                        metadata = metadata,
                        delayCentiseconds = frameDelayCentiseconds(frame),
                        transparentIndex = 0,
                        loop = loop,
                        includeLoopExtension = index == 0,
                    )
                    writer.writeToSequence(IIOImage(image, null, metadata), writer.defaultWriteParam)
                }
                writer.endWriteSequence()
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    /**
     * Encode a single static frame as a GIF (for consistency with PNG export).
     */
    fun encodeSingle(pixels: IntArray, width: Int, height: Int): ByteArray {
        val frame = SpriteAnimationFrame(pixels, width, height, durationTicks = 0)
        return encode(listOf(frame), loop = 0)
    }

    // ─── Palette building ──────────────────────────────────────────────

    /**
     * Build a global palette from all frames. Index 0 is always transparent.
     * Collects unique non-transparent colors, caps at 255 (+ transparent = 256).
     */
    private fun buildGlobalPalette(frames: List<SpriteAnimationFrame>): IntArray {
        val colors = linkedSetOf(0x00000000) // index 0 = transparent
        for (frame in frames) {
            for (px in frame.pixels) {
                if ((px ushr 24) >= 128) {
                    colors.add(px or 0xFF000000.toInt())
                }
                if (colors.size >= 256) break
            }
            if (colors.size >= 256) break
        }
        return colors.toIntArray()
    }

    private fun buildColorModel(palette: IntArray): IndexColorModel {
        val paletteBits = paletteBitsNeeded(palette.size)
        val paletteSize = 1 shl paletteBits
        val reds = ByteArray(paletteSize)
        val greens = ByteArray(paletteSize)
        val blues = ByteArray(paletteSize)
        val alphas = ByteArray(paletteSize) { 0xFF.toByte() }
        alphas[0] = 0

        for (i in palette.indices) {
            val argb = palette[i]
            reds[i] = ((argb shr 16) and 0xFF).toByte()
            greens[i] = ((argb shr 8) and 0xFF).toByte()
            blues[i] = (argb and 0xFF).toByte()
            alphas[i] = ((argb ushr 24) and 0xFF).toByte()
        }

        return IndexColorModel(paletteBits, paletteSize, reds, greens, blues, alphas)
    }

    private fun indexedFrameImage(
        frame: SpriteAnimationFrame,
        width: Int,
        height: Int,
        palette: IntArray,
        colorModel: IndexColorModel,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, colorModel)
        val raster = image.raster
        val ox = (width - frame.width) / 2
        val oy = (height - frame.height) / 2
        val indices = quantizeFrame(frame.pixels, palette)

        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val dx = ox + x
                val dy = oy + y
                if (dx in 0 until width && dy in 0 until height) {
                    raster.setSample(dx, dy, 0, indices[y * frame.width + x].toInt() and 0xFF)
                }
            }
        }
        return image
    }

    /**
     * Map ARGB pixels to palette indices. Transparent pixels map to index 0.
     */
    private fun quantizeFrame(pixels: IntArray, palette: IntArray): ByteArray {
        val colorToIdx = HashMap<Int, Int>(palette.size * 2)
        for (i in palette.indices) {
            colorToIdx[palette[i]] = i
        }

        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val px = pixels[i]
            indices[i] = if ((px ushr 24) < 128) {
                0
            } else {
                val opaque = px or 0xFF000000.toInt()
                (colorToIdx[opaque] ?: findClosestColor(opaque, palette)).toByte()
            }
        }
        return indices
    }

    /**
     * Find the closest palette color by Euclidean distance in RGB space.
     * Skips index 0 (transparent).
     */
    private fun findClosestColor(argb: Int, palette: IntArray): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var bestIdx = 1
        var bestDist = Int.MAX_VALUE
        for (i in 1 until palette.size) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun configureFrameMetadata(
        metadata: IIOMetadata,
        delayCentiseconds: Int,
        transparentIndex: Int,
        loop: Int,
        includeLoopExtension: Boolean,
    ) {
        val formatName = metadata.nativeMetadataFormatName
        val root = metadata.getAsTree(formatName) as IIOMetadataNode

        val gce = getOrCreateNode(root, "GraphicControlExtension")
        gce.setAttribute("disposalMethod", "restoreToBackgroundColor")
        gce.setAttribute("userInputFlag", "FALSE")
        gce.setAttribute("transparentColorFlag", "TRUE")
        gce.setAttribute("delayTime", delayCentiseconds.toString())
        gce.setAttribute("transparentColorIndex", transparentIndex.toString())

        if (includeLoopExtension) {
            val appExtensions = getOrCreateNode(root, "ApplicationExtensions")
            val appNode = IIOMetadataNode("ApplicationExtension")
            appNode.setAttribute("applicationID", "NETSCAPE")
            appNode.setAttribute("authenticationCode", "2.0")
            appNode.userObject = byteArrayOf(
                1,
                (loop and 0xFF).toByte(),
                ((loop shr 8) and 0xFF).toByte(),
            )
            appExtensions.appendChild(appNode)
        }

        metadata.setFromTree(formatName, root)
    }

    private fun getOrCreateNode(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) {
            val node = root.item(i)
            if (node.nodeName.equals(name, ignoreCase = true)) {
                return node as IIOMetadataNode
            }
        }
        val node = IIOMetadataNode(name)
        root.appendChild(node)
        return node
    }

    private fun frameDelayCentiseconds(frame: SpriteAnimationFrame): Int =
        if (frame.durationTicks > 0) {
            ((frame.durationTicks * 100) / 60).coerceAtLeast(2)
        } else {
            7 // ~4 ticks default ≈ 67ms
        }

    private fun paletteBitsNeeded(colorCount: Int): Int {
        var bits = 1
        while ((1 shl bits) < colorCount) bits++
        return bits.coerceIn(2, 8)
    }
}
