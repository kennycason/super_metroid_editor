package com.supermetroid.editor.ui

import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.EnemySpriteGraphics
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.SpritePalettes
import java.util.Base64

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

    // ── Enemy tile-sheet (raw 4bpp) ───────────────────────────────────────

    fun loadEnemyTileData(romParser: RomParser, speciesId: Int): ByteArray? {
        // A project block cannot manufacture edit/export ownership for a
        // species whose source header transfers zero graphics bytes.
        val tileDataSize = EnemySpriteGraphics.readSpeciesStats(romParser, speciesId)?.first ?: return null
        if (tileDataSize <= 0) return null
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
        paletteOverride: IntArray? = null,
    ) {
        val palette = paletteOverride ?: loadEnemyPalette(romParser, speciesId) ?: return
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
        val palettes = customGfx().spritePalettes
        val b64 = palettes[enemyPalKey(speciesId)]
        if (b64 != null) {
            try {
                val raw = Base64.getDecoder().decode(b64)
                return enemyPalBytesToArgb(raw)
            } catch (_: Exception) { /* fall through to ROM */ }
        }
        paletteRegionSlice(romParser, speciesId)?.let { slice ->
            val encoded = palettes[slice.region.id] ?: return@let
            try {
                val raw = Base64.getDecoder().decode(encoded)
                if (raw.size == slice.region.byteSize) {
                    return enemyPalBytesToArgb(raw.copyOfRange(slice.byteOffset, slice.byteOffset + 32))
                }
            } catch (_: Exception) { /* fall through to ROM */ }
        }
        return EnemySpriteGraphics.readEnemyPalette(romParser, speciesId)
    }

    fun applyEnemyPalette(romParser: RomParser, speciesId: Int, palette: IntArray) {
        val raw = enemyPalArgbToBytes(palette)
        val gfx = customGfx()
        val legacyKey = enemyPalKey(speciesId)
        val slice = paletteRegionSlice(romParser, speciesId)
        if (slice == null) {
            gfx.spritePalettes[legacyKey] = Base64.getEncoder().encodeToString(raw)
        } else {
            val original = romParser.getRomData().copyOfRange(
                slice.region.offset,
                slice.region.offset + slice.region.byteSize,
            )
            val current = gfx.spritePalettes[slice.region.id]?.let { encoded ->
                runCatching { Base64.getDecoder().decode(encoded) }
                    .getOrNull()
                    ?.takeIf { it.size == slice.region.byteSize }
            }?.copyOf() ?: original.copyOf()
            raw.copyInto(current, slice.byteOffset)
            if (current.contentEquals(original)) gfx.spritePalettes.remove(slice.region.id)
            else gfx.spritePalettes[slice.region.id] = Base64.getEncoder().encodeToString(current)
            gfx.spritePalettes.remove(legacyKey)
            gfx.paletteEffects.remove(slice.region.id)
        }
        onDirty()
    }

    fun hasCustomEnemyPalette(romParser: RomParser, speciesId: Int): Boolean {
        val gfx = customGfx()
        if (gfx.spritePalettes.containsKey(enemyPalKey(speciesId))) return true
        val regionId = paletteRegionSlice(romParser, speciesId)?.region?.id ?: return false
        return gfx.spritePalettes.containsKey(regionId) || gfx.paletteEffects.containsKey(regionId)
    }

    fun resetEnemyPalette(romParser: RomParser, speciesId: Int) {
        val gfx = customGfx()
        var changed = gfx.spritePalettes.remove(enemyPalKey(speciesId)) != null
        paletteRegionSlice(romParser, speciesId)?.let { slice ->
            val encoded = gfx.spritePalettes[slice.region.id]
            if (encoded != null) {
                val original = romParser.getRomData().copyOfRange(
                    slice.region.offset,
                    slice.region.offset + slice.region.byteSize,
                )
                val current = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                if (current?.size == slice.region.byteSize) {
                    original.copyInto(
                        current,
                        destinationOffset = slice.byteOffset,
                        startIndex = slice.byteOffset,
                        endIndex = slice.byteOffset + 32,
                    )
                    if (current.contentEquals(original)) gfx.spritePalettes.remove(slice.region.id)
                    else gfx.spritePalettes[slice.region.id] = Base64.getEncoder().encodeToString(current)
                    changed = true
                }
            }
            changed = gfx.paletteEffects.remove(slice.region.id) != null || changed
        }
        if (changed) onDirty()
    }

    private fun enemyPalKey(speciesId: Int) = "enemy_pal:${speciesId.toString(16).uppercase()}"

    private data class PaletteRegionSlice(
        val region: SpritePalettes.PaletteRegion,
        val byteOffset: Int,
    )

    private fun paletteRegionSlice(romParser: RomParser, speciesId: Int): PaletteRegionSlice? {
        val source = EnemySpriteGraphics.loadEnemyPreviewPaletteSource(romParser, speciesId) ?: return null
        val pc = romParser.snesToPc(source.snesAddress)
        val region = SpritePalettes.REGIONS.firstOrNull {
            pc >= it.offset && pc + 32 <= it.offset + it.byteSize
        } ?: return null
        return PaletteRegionSlice(region, pc - region.offset)
    }

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
