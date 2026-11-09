package com.supermetroid.editor.rom

import com.supermetroid.editor.data.IntroImageFit
import com.supermetroid.editor.data.IntroImageSource
import com.supermetroid.editor.data.IntroScene
import com.supermetroid.editor.data.IntroSceneLayout
import com.supermetroid.editor.data.effectiveImageSource
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Deterministic preview and 4bpp SNES compiler for managed intro stills. */
object IntroSceneCodec {
    const val WIDTH = 256
    const val HEIGHT = 224
    const val TILEMAP_ROWS = 32
    const val MAX_TEXT_LINES = 20
    const val MAX_PNG_BYTES = 10 * 1024 * 1024
    const val MAX_SOURCE_DIMENSION = 4096
    const val MAX_SOURCE_PIXELS = 16_000_000L
    private const val HIDDEN_REVEAL_GLYPH = '\u0001'

    data class CompiledScene(
        val tiles: ByteArray,
        val tilemap: ByteArray,
        val palette: ByteArray,
        val tileCount: Int,
    ) {
        val payload: ByteArray get() = tiles + tilemap + palette
        val tilemapOffset: Int get() = tiles.size
        val paletteOffset: Int get() = tiles.size + tilemap.size
    }

    data class ImportedPng(
        val base64: String,
        val sha256: String,
        val width: Int,
        val height: Int,
    )

    fun importPng(bytes: ByteArray): ImportedPng {
        require(bytes.isNotEmpty()) { "The selected PNG is empty." }
        require(bytes.size <= MAX_PNG_BYTES) { "PNG is larger than 10 MiB." }
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        require(bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }) {
            "The selected file is not a PNG."
        }
        val image = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: error("The selected PNG is not readable.")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..MAX_SOURCE_DIMENSION && height in 1..MAX_SOURCE_DIMENSION) {
                    "PNG dimensions must be between 1 and $MAX_SOURCE_DIMENSION pixels."
                }
                require(width.toLong() * height <= MAX_SOURCE_PIXELS) {
                    "PNG contains more than 16 million pixels."
                }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }
        val normalized = ByteArrayOutputStream().also { output ->
            check(ImageIO.write(image, "PNG", output)) { "PNG encoder is unavailable." }
        }.toByteArray()
        require(normalized.size <= MAX_PNG_BYTES) { "Normalized PNG is larger than 10 MiB." }
        return ImportedPng(
            base64 = Base64.getEncoder().encodeToString(normalized),
            sha256 = sha256(normalized),
            width = image.width,
            height = image.height,
        )
    }

    fun render(scene: IntroScene, romSourceImage: BufferedImage? = null): BufferedImage {
        val frame = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = frame.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.color = Color(scene.backgroundColor, true)
            g.fillRect(0, 0, WIDTH, HEIGHT)
            g.composite = AlphaComposite.SrcOver
            if (scene.layout != IntroSceneLayout.TEXT_ONLY) {
                resolveSource(scene, romSourceImage)?.let { source -> drawSource(g, source, scene.imageFit) }
            }
            if (scene.layout != IntroSceneLayout.IMAGE_ONLY && scene.text.isNotBlank()) drawText(g, scene)
        } finally {
            g.dispose()
        }
        return frame
    }

    fun compile(scene: IntroScene, romSourceImage: BufferedImage? = null): CompiledScene = compile(
        render(scene, romSourceImage),
        reservedColors = if (scene.layout != IntroSceneLayout.IMAGE_ONLY && scene.text.isNotBlank()) {
            listOf(scene.textColor)
        } else emptyList(),
    )

    fun textLineCount(text: String): Int = wrap(text.uppercase(), 38).size

    /** Keeps final wrapping/centering stable while hiding characters not yet typed. */
    fun maskTextForReveal(text: String, visibleCharacters: Int): String = buildString(text.length) {
        text.forEachIndexed { index, character ->
            append(if (index < visibleCharacters || character.isWhitespace()) character else HIDDEN_REVEAL_GLYPH)
        }
    }

    fun compile(image: BufferedImage, reservedColors: List<Int> = emptyList()): CompiledScene {
        require(image.width == WIDTH && image.height == HEIGHT) { "Intro frame must be exactly ${WIDTH}x$HEIGHT." }
        val palettes = Array(7) { band ->
            quantizeBand(image, band * 32, min(HEIGHT, band * 32 + 32), reservedColors)
        }
        val indexed = Array(HEIGHT) { y ->
            val palette = palettes[y / 32]
            ByteArray(WIDTH) { x -> nearestColor(image.getRGB(x, y), palette).toByte() }
        }

        val tiles = mutableListOf<ByteArray>()
        val tileByBytes = mutableMapOf<String, Int>()
        fun register(tile: Array<ByteArray>): Pair<Int, Int> {
            val candidates = listOf(
                tile to 0,
                flip(tile, horizontal = true, vertical = false) to 0x4000,
                flip(tile, horizontal = false, vertical = true) to 0x8000,
                flip(tile, horizontal = true, vertical = true) to 0xC000,
            )
            for ((candidate, flags) in candidates) {
                val encoded = encodeTile(candidate)
                val existing = tileByBytes[Base64.getEncoder().encodeToString(encoded)]
                if (existing != null) return existing to flags
            }
            val encoded = encodeTile(tile)
            val index = tiles.size
            tiles += encoded
            tileByBytes[Base64.getEncoder().encodeToString(encoded)] = index
            return index to 0
        }

        val blank = Array(8) { ByteArray(8) }
        val blankIndex = register(blank).first
        val mapWords = IntArray(32 * TILEMAP_ROWS) { blankIndex }
        for (tileY in 0 until HEIGHT / 8) {
            for (tileX in 0 until WIDTH / 8) {
                val tile = Array(8) { row -> ByteArray(8) { col -> indexed[tileY * 8 + row][tileX * 8 + col] } }
                val (index, flags) = register(tile)
                require(index < 1024) { "Scene needs ${index + 1} unique tiles; the SNES limit is 1024." }
                val palette = tileY / 4
                mapWords[tileY * 32 + tileX] = index or (palette shl 10) or flags
            }
        }

        val tileBytes = tiles.fold(ByteArray(0)) { all, tile -> all + tile }
        val mapBytes = ByteArray(mapWords.size * 2)
        mapWords.forEachIndexed { index, word ->
            mapBytes[index * 2] = word.toByte()
            mapBytes[index * 2 + 1] = (word ushr 8).toByte()
        }
        val paletteBytes = ByteArray(256)
        palettes.forEachIndexed { band, palette ->
            palette.forEachIndexed { index, argb ->
                val word = argbToBgr555(argb)
                val offset = (band * 16 + index) * 2
                paletteBytes[offset] = word.toByte()
                paletteBytes[offset + 1] = (word ushr 8).toByte()
            }
        }
        return CompiledScene(tileBytes, mapBytes, paletteBytes, tiles.size)
    }

    /** Decodes exactly what the SNES payload will display; useful for preview and tests. */
    fun decode(compiled: CompiledScene): BufferedImage {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val colors = IntArray(128) { index ->
            val offset = index * 2
            bgr555ToArgb((compiled.palette[offset].toInt() and 255) or ((compiled.palette[offset + 1].toInt() and 255) shl 8))
        }
        for (tileY in 0 until HEIGHT / 8) for (tileX in 0 until WIDTH / 8) {
            val mapOffset = (tileY * 32 + tileX) * 2
            val word = (compiled.tilemap[mapOffset].toInt() and 255) or ((compiled.tilemap[mapOffset + 1].toInt() and 255) shl 8)
            val tileIndex = word and 0x3FF
            val palette = (word ushr 10) and 7
            val h = word and 0x4000 != 0
            val v = word and 0x8000 != 0
            val pixels = decodeTile(compiled.tiles, tileIndex * 32)
            for (y in 0..7) for (x in 0..7) {
                val sourceX = if (h) 7 - x else x
                val sourceY = if (v) 7 - y else y
                image.setRGB(tileX * 8 + x, tileY * 8 + y, colors[palette * 16 + (pixels[sourceY][sourceX].toInt() and 15)])
            }
        }
        return image
    }

    private fun resolveSource(scene: IntroScene, romSourceImage: BufferedImage?): BufferedImage? = when (scene.effectiveImageSource) {
        IntroImageSource.NONE -> null
        IntroImageSource.UPLOADED_PNG -> decodeUploadedPng(scene)
        else -> requireNotNull(romSourceImage) {
            "${scene.name}: the selected ROM cinematic image is unavailable. Load the source ROM or choose another image."
        }
    }

    private fun decodeUploadedPng(scene: IntroScene): BufferedImage {
        require(scene.imagePngBase64.isNotBlank()) { "${scene.name}: the uploaded PNG data is missing." }
        val bytes = runCatching { Base64.getDecoder().decode(scene.imagePngBase64) }
            .getOrElse { error("${scene.name}: stored image is not valid base64.") }
        val imported = importPng(bytes)
        require(scene.imageSha256.isBlank() || scene.imageSha256.equals(imported.sha256, ignoreCase = true)) {
            "${scene.name}: stored image hash does not match its PNG data."
        }
        return ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(imported.base64)))
            ?: error("${scene.name}: stored PNG could not be decoded.")
    }

    private fun drawSource(g: Graphics2D, source: BufferedImage, fit: IntroImageFit) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        if (fit == IntroImageFit.STRETCH) {
            g.drawImage(source, 0, 0, WIDTH, HEIGHT, null)
            return
        }
        val scale = if (fit == IntroImageFit.FIT) {
            min(WIDTH.toDouble() / source.width, HEIGHT.toDouble() / source.height)
        } else {
            max(WIDTH.toDouble() / source.width, HEIGHT.toDouble() / source.height)
        }
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        g.drawImage(source, (WIDTH - width) / 2, (HEIGHT - height) / 2, width, height, null)
    }

    private fun drawText(g: Graphics2D, scene: IntroScene) {
        val lines = wrap(scene.text.uppercase(), 38)
        require(lines.size <= MAX_TEXT_LINES) {
            "Scene text wraps to ${lines.size} lines; the safe maximum is $MAX_TEXT_LINES."
        }
        if (lines.isEmpty()) return
        val lineHeight = 10
        val blockHeight = lines.size * lineHeight - 2
        val top = when (scene.layout) {
            IntroSceneLayout.TEXT_PANEL_TOP -> 16
            IntroSceneLayout.TEXT_PANEL_BOTTOM -> HEIGHT - blockHeight - 18
            else -> (HEIGHT - blockHeight) / 2
        }
        if (scene.layout == IntroSceneLayout.TEXT_PANEL_TOP || scene.layout == IntroSceneLayout.TEXT_PANEL_BOTTOM) {
            g.color = Color(scene.panelColor, true)
            g.fillRect(0, (top - 8).coerceAtLeast(0), WIDTH, (blockHeight + 16).coerceAtMost(HEIGHT))
        }
        lines.forEachIndexed { lineIndex, line ->
            val width = line.length * 6 - 1
            val left = ((WIDTH - width) / 2).coerceAtLeast(4)
            drawPixelLine(g, line, left + 1, top + lineIndex * lineHeight + 1, 0xD8000000.toInt())
            drawPixelLine(g, line, left, top + lineIndex * lineHeight, scene.textColor)
        }
    }

    private fun drawPixelLine(g: Graphics2D, text: String, x: Int, y: Int, argb: Int) {
        g.color = Color(argb, true)
        text.forEachIndexed { index, character ->
            if (character == HIDDEN_REVEAL_GLYPH) return@forEachIndexed
            val rows = FONT[character] ?: FONT.getValue('?')
            rows.forEachIndexed { row, bits ->
                for (column in 0 until 5) if (bits and (1 shl (4 - column)) != 0) g.fillRect(x + index * 6 + column, y + row, 1, 1)
            }
        }
    }

    private fun wrap(text: String, maxColumns: Int): List<String> = buildList {
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { paragraph ->
            if (paragraph.isBlank()) { add(""); return@forEach }
            var line = ""
            paragraph.trim().split(Regex("\\s+")).forEach { word ->
                var remaining = word
                while (remaining.length > maxColumns) {
                    if (line.isNotEmpty()) { add(line); line = "" }
                    add(remaining.take(maxColumns)); remaining = remaining.drop(maxColumns)
                }
                val candidate = if (line.isEmpty()) remaining else "$line $remaining"
                if (candidate.length > maxColumns) { add(line); line = remaining } else line = candidate
            }
            if (line.isNotEmpty()) add(line)
        }
    }

    private fun quantizeBand(image: BufferedImage, yStart: Int, yEnd: Int, reservedColors: List<Int>): IntArray {
        val counts = mutableMapOf<Int, Int>()
        for (y in yStart until yEnd) for (x in 0 until WIDTH) {
            val normalized = bgr555ToArgb(argbToBgr555(image.getRGB(x, y)))
            counts[normalized] = (counts[normalized] ?: 0) + 1
        }
        val reserved = reservedColors.map { bgr555ToArgb(argbToBgr555(it)) }.distinct().take(15)
        val selected = reserved + counts.entries
            .filterNot { it.key in reserved || it.key == 0xFF000000.toInt() }
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(15 - reserved.size).map { it.key }
        return IntArray(16) { index -> if (index == 0) 0xFF000000.toInt() else selected.getOrElse(index - 1) { 0xFF000000.toInt() } }
    }

    private fun nearestColor(argb: Int, palette: IntArray): Int {
        val r = argb ushr 16 and 255; val g = argb ushr 8 and 255; val b = argb and 255
        var best = 0; var bestDistance = Int.MAX_VALUE
        palette.forEachIndexed { index, color ->
            val dr = r - (color ushr 16 and 255); val dg = g - (color ushr 8 and 255); val db = b - (color and 255)
            val distance = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (distance < bestDistance) { bestDistance = distance; best = index }
        }
        return best
    }

    private fun flip(tile: Array<ByteArray>, horizontal: Boolean, vertical: Boolean): Array<ByteArray> =
        Array(8) { y -> ByteArray(8) { x -> tile[if (vertical) 7 - y else y][if (horizontal) 7 - x else x] } }

    private fun encodeTile(tile: Array<ByteArray>): ByteArray = ByteArray(32).also { out ->
        for (y in 0..7) for (x in 0..7) {
            val value = tile[y][x].toInt() and 15
            val bit = 1 shl (7 - x)
            if (value and 1 != 0) out[y * 2] = (out[y * 2].toInt() or bit).toByte()
            if (value and 2 != 0) out[y * 2 + 1] = (out[y * 2 + 1].toInt() or bit).toByte()
            if (value and 4 != 0) out[16 + y * 2] = (out[16 + y * 2].toInt() or bit).toByte()
            if (value and 8 != 0) out[16 + y * 2 + 1] = (out[16 + y * 2 + 1].toInt() or bit).toByte()
        }
    }

    private fun decodeTile(bytes: ByteArray, offset: Int): Array<ByteArray> = Array(8) { y -> ByteArray(8) { x ->
        val bit = 1 shl (7 - x)
        (((bytes[offset + y * 2].toInt() and bit) / bit) or
            (((bytes[offset + y * 2 + 1].toInt() and bit) / bit) shl 1) or
            (((bytes[offset + 16 + y * 2].toInt() and bit) / bit) shl 2) or
            (((bytes[offset + 16 + y * 2 + 1].toInt() and bit) / bit) shl 3)).toByte()
    } }

    private fun argbToBgr555(argb: Int): Int =
        ((argb ushr 19 and 31)) or ((argb ushr 11 and 31) shl 5) or ((argb ushr 3 and 31) shl 10)

    private fun bgr555ToArgb(value: Int): Int {
        fun expand(v: Int) = (v shl 3) or (v ushr 2)
        return 0xFF000000.toInt() or (expand(value and 31) shl 16) or
            (expand(value ushr 5 and 31) shl 8) or expand(value ushr 10 and 31)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private val FONT: Map<Char, IntArray> = mapOf(
        ' ' to intArrayOf(0,0,0,0,0,0,0), 'A' to intArrayOf(14,17,17,31,17,17,17),
        'B' to intArrayOf(30,17,17,30,17,17,30), 'C' to intArrayOf(14,17,16,16,16,17,14),
        'D' to intArrayOf(30,17,17,17,17,17,30), 'E' to intArrayOf(31,16,16,30,16,16,31),
        'F' to intArrayOf(31,16,16,30,16,16,16), 'G' to intArrayOf(14,17,16,23,17,17,15),
        'H' to intArrayOf(17,17,17,31,17,17,17), 'I' to intArrayOf(31,4,4,4,4,4,31),
        'J' to intArrayOf(1,1,1,1,17,17,14), 'K' to intArrayOf(17,18,20,24,20,18,17),
        'L' to intArrayOf(16,16,16,16,16,16,31), 'M' to intArrayOf(17,27,21,21,17,17,17),
        'N' to intArrayOf(17,25,21,19,17,17,17), 'O' to intArrayOf(14,17,17,17,17,17,14),
        'P' to intArrayOf(30,17,17,30,16,16,16), 'Q' to intArrayOf(14,17,17,17,21,18,13),
        'R' to intArrayOf(30,17,17,30,20,18,17), 'S' to intArrayOf(15,16,16,14,1,1,30),
        'T' to intArrayOf(31,4,4,4,4,4,4), 'U' to intArrayOf(17,17,17,17,17,17,14),
        'V' to intArrayOf(17,17,17,17,17,10,4), 'W' to intArrayOf(17,17,17,21,21,21,10),
        'X' to intArrayOf(17,17,10,4,10,17,17), 'Y' to intArrayOf(17,17,10,4,4,4,4),
        'Z' to intArrayOf(31,1,2,4,8,16,31), '0' to intArrayOf(14,17,19,21,25,17,14),
        '1' to intArrayOf(4,12,4,4,4,4,14), '2' to intArrayOf(14,17,1,2,4,8,31),
        '3' to intArrayOf(30,1,1,14,1,1,30), '4' to intArrayOf(2,6,10,18,31,2,2),
        '5' to intArrayOf(31,16,16,30,1,1,30), '6' to intArrayOf(14,16,16,30,17,17,14),
        '7' to intArrayOf(31,1,2,4,8,8,8), '8' to intArrayOf(14,17,17,14,17,17,14),
        '9' to intArrayOf(14,17,17,15,1,1,14), '.' to intArrayOf(0,0,0,0,0,12,12),
        ',' to intArrayOf(0,0,0,0,0,12,8), '!' to intArrayOf(4,4,4,4,4,0,4),
        '?' to intArrayOf(14,17,1,2,4,0,4), '\'' to intArrayOf(4,4,8,0,0,0,0),
        '-' to intArrayOf(0,0,0,31,0,0,0), ':' to intArrayOf(0,12,12,0,12,12,0),
        '/' to intArrayOf(1,2,2,4,8,8,16), '(' to intArrayOf(2,4,8,8,8,4,2),
        ')' to intArrayOf(8,4,2,2,2,4,8), '&' to intArrayOf(12,18,20,8,21,18,13),
    )
}
