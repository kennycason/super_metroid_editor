package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.RomParser

/**
 * Manages enemy sprite, tile-sheet, and palette overrides for the editor project.
 *
 * All reads and writes go through the [customGfx] accessor so that calls always
 * reflect the current project without holding a stale reference across project loads.
 */
class EnemySpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val onDirty: () -> Unit,
) {
    // ── PNG sprite overlay (preview thumbnails) ───────────────────────────

    fun getEnemySpritePixels(speciesIdHex: String): Pair<IntArray, Pair<Int, Int>>? {
        val customB64 = customGfx().enemyGfx[speciesIdHex]
        val img: java.awt.image.BufferedImage? = if (customB64 != null) {
            try {
                val bytes = java.util.Base64.getDecoder().decode(customB64)
                javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
            } catch (_: Exception) { null }
        } else {
            try {
                val stream = javaClass.getResourceAsStream("/enemies/$speciesIdHex.png")
                stream?.let { javax.imageio.ImageIO.read(it) }
            } catch (_: Exception) { null }
        }
        img ?: return null
        val w = img.width; val h = img.height
        val pixels = img.getRGB(0, 0, w, h, null, 0, w)
        return Pair(pixels, Pair(w, h))
    }

    fun hasCustomEnemySprite(speciesIdHex: String): Boolean =
        customGfx().enemyGfx.containsKey(speciesIdHex)

    fun exportEnemySprite(speciesIdHex: String, filePath: String): Boolean {
        val (pixels, dims) = getEnemySpritePixels(speciesIdHex) ?: return false
        return writePng(filePath, pixels, dims.first, dims.second)
    }

    fun importEnemySprite(speciesIdHex: String, filePath: String): Boolean {
        return try {
            val bytes = java.io.File(filePath).readBytes()
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            customGfx().enemyGfx[speciesIdHex] = b64
            onDirty()
            true
        } catch (_: Exception) { false }
    }

    fun saveEnemySpritePixels(speciesIdHex: String, pixels: IntArray, w: Int, h: Int): Boolean {
        return try {
            val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, pixels, 0, w)
            val baos = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(img, "png", baos)
            val b64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
            customGfx().enemyGfx[speciesIdHex] = b64
            onDirty()
            true
        } catch (_: Exception) { false }
    }

    fun resetEnemySprite(speciesIdHex: String) {
        customGfx().enemyGfx.remove(speciesIdHex)
        onDirty()
    }

    // ── Enemy tile-sheet (raw 4bpp) ───────────────────────────────────────

    fun loadEnemyTileData(romParser: RomParser, speciesId: Int): ByteArray? {
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        val customB64 = customGfx().spriteTileBlocks[key]
        if (customB64 != null) {
            try {
                return java.util.Base64.getDecoder().decode(customB64)
            } catch (_: Exception) { /* fall through to ROM */ }
        }
        return EnemySpriteGraphics.loadEnemyTileData(romParser, speciesId)
    }

    fun applyEnemyTileSheetEdits(
        romParser: RomParser,
        speciesId: Int,
        pixels: IntArray,
        w: Int,
        h: Int,
    ) {
        val palette = loadEnemyPalette(romParser, speciesId) ?: return
        val tileData = loadEnemyTileData(romParser, speciesId) ?: return
        val gfx = EnemySpriteGraphics(romParser)
        gfx.loadFromRaw(listOf(tileData))
        gfx.importFromArgb(pixels, w, h, palette, 16)
        val rawBlocks = gfx.getRawBlocks() ?: return
        val raw = rawBlocks.firstOrNull() ?: return
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        customGfx().spriteTileBlocks[key] = java.util.Base64.getEncoder().encodeToString(raw)
        onDirty()
    }

    fun hasCustomEnemyTiles(speciesId: Int): Boolean {
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        return customGfx().spriteTileBlocks.containsKey(key)
    }

    fun resetEnemyTiles(speciesId: Int) {
        val key = "enemy:${speciesId.toString(16).uppercase()}"
        customGfx().spriteTileBlocks.remove(key)
        onDirty()
    }

    // ── Enemy palette ─────────────────────────────────────────────────────

    fun loadEnemyPalette(romParser: RomParser, speciesId: Int): IntArray? {
        val b64 = customGfx().spritePalettes[enemyPalKey(speciesId)]
        if (b64 != null) {
            try {
                val raw = java.util.Base64.getDecoder().decode(b64)
                return enemyPalBytesToArgb(raw)
            } catch (_: Exception) { /* fall through to ROM */ }
        }
        return EnemySpriteGraphics.readEnemyPalette(romParser, speciesId)
    }

    fun applyEnemyPalette(speciesId: Int, palette: IntArray) {
        val raw = enemyPalArgbToBytes(palette)
        customGfx().spritePalettes[enemyPalKey(speciesId)] =
            java.util.Base64.getEncoder().encodeToString(raw)
        onDirty()
    }

    fun hasCustomEnemyPalette(speciesId: Int): Boolean =
        customGfx().spritePalettes.containsKey(enemyPalKey(speciesId))

    fun resetEnemyPalette(speciesId: Int) {
        customGfx().spritePalettes.remove(enemyPalKey(speciesId))
        onDirty()
    }

    private fun enemyPalKey(speciesId: Int) = "enemy_pal:${speciesId.toString(16).uppercase()}"

    private fun enemyPalBytesToArgb(raw: ByteArray): IntArray {
        val pal = IntArray(16)
        pal[0] = 0x00000000
        for (i in 1 until 16) {
            val lo = raw[i * 2].toInt() and 0xFF
            val hi = raw[i * 2 + 1].toInt() and 0xFF
            val bgr = lo or (hi shl 8)
            pal[i] = EnemySpriteGraphics.snesColorToArgb(bgr)
        }
        return pal
    }

    private fun enemyPalArgbToBytes(palette: IntArray): ByteArray {
        val raw = ByteArray(32)
        for (i in 0 until 16) {
            val argb = palette[i]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val bgr555 = ((b shr 3) shl 10) or ((g shr 3) shl 5) or (r shr 3)
            raw[i * 2] = (bgr555 and 0xFF).toByte()
            raw[i * 2 + 1] = ((bgr555 shr 8) and 0xFF).toByte()
        }
        return raw
    }
}
