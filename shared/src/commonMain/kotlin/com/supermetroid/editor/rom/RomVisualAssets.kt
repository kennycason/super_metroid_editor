package com.supermetroid.editor.rom

/** Graphics recipes from the extracted assets and the engine's VRAM/palette transfers. */
object RomVisualAssets {
    enum class Format { TILES_4BPP, MODE7_TILES, BG_4BPP, MODE7_MAP, OAM, TITLE_SCENE }
    data class Asset(
        val name: String,
        val group: String,
        val graphics: Int,
        val bytes: Int = 0,
        val compressed: Boolean = false,
        val palette: Int = 0x9A8000,
        val format: Format = Format.TILES_4BPP,
        val map: Int = 0,
        val columns: Int = 16,
        val paletteColors: Int = 256,
        val paletteBase: Int = 0,
    )

    val ASSETS = listOf(
        Asset("Title screen", "Scenes", 0x94E000, compressed = true, palette = 0x8CE1E9, format = Format.TITLE_SCENE, map = 0x96FC04, columns = 128),
        Asset("Title background", "Scenes", 0x94E000, compressed = true, palette = 0x8CE1E9, format = Format.MODE7_MAP, map = 0x96FC04, columns = 128),
        Asset("Super Metroid title logo", "Scenes", 0x9580D8, compressed = true, palette = 0x8CE1E9, format = Format.OAM, map = 0x8C879D),
        Asset("Nintendo boot logo", "Scenes", 0x9580D8, compressed = true, palette = 0x8CE1E9, format = Format.OAM, map = 0x8C80BB),
        Asset("Nintendo copyright", "Scenes", 0x9580D8, compressed = true, palette = 0x8CE1E9, format = Format.OAM, map = 0x8C8103, paletteBase = 4),
        Asset("Intro · Mother Brain's room", "Scenes", 0x95F90E, compressed = true, palette = 0x8CE3E9, format = Format.BG_4BPP, map = 0x96FF14, columns = 64),
        Asset("Intro · Samus portrait", "Scenes", 0x95F90E, compressed = true, palette = 0x8CE3E9, format = Format.BG_4BPP, map = 0x9788CC, columns = 32),
        Asset("Ceres gunship", "Scenes", 0x95A82F, compressed = true, palette = 0x8CE5E9, format = Format.MODE7_MAP, map = 0x96FE69, columns = 128),
        Asset("Ending · Samus waiting", "Scenes", 0x979803, compressed = true, palette = 0x8CE9E9, format = Format.BG_4BPP, map = 0x9796F4, columns = 32),
        Asset("Ending · Super Metroid icon", "Scenes", 0x99E089, compressed = true, palette = 0x8CE7E9, format = Format.BG_4BPP, map = 0x99ECC4, columns = 32),
        Asset("Title sprite tiles", "Scenes", 0x9580D8, compressed = true, palette = 0x8CE1E9),
        Asset("Intro sprite tiles", "Scenes", 0x95E4C2, compressed = true, palette = 0x8CE3E9),
        Asset("Intro background tiles", "Scenes", 0x95F90E, compressed = true, palette = 0x8CE3E9),
        Asset("Ending · suitless Samus tiles", "Scenes", 0x97B957, compressed = true, palette = 0x8CE7E9),
        Asset("Ending · Samus firing tiles", "Scenes", 0x97D7FC, compressed = true, palette = 0x8CE7E9),
        Asset("Common sprite effects", "Effects", 0x9AD200, 0x2000, palette = 0x9A8100, paletteColors = 128),
        Asset("Gunship liftoff dust", "Effects", 0x94C800, 0x1400, palette = 0x8CE5E9),
        Asset("Samus death sequence tiles", "Death", 0x9B8000, 0x1400, palette = 0x9B9400),
        Asset("Grapple · horizontal", "Effects", 0x9A8200, 0x800, palette = 0x90C3E1, paletteColors = 16),
        Asset("Grapple · diagonal", "Effects", 0x9A8A00, 0x800, palette = 0x90C3E1, paletteColors = 16),
        Asset("Grapple · vertical", "Effects", 0x9A9200, 0x800, palette = 0x90C3E1, paletteColors = 16),
        Asset("Power beam tiles", "Effects", 0x9AF200, 0x200, palette = 0x90C3E1, paletteColors = 16),
        Asset("Ice beam tiles", "Effects", 0x9AF400, 0x200, palette = 0x90C401, paletteColors = 16),
        Asset("Wave beam tiles", "Effects", 0x9AF600, 0x200, palette = 0x90C421, paletteColors = 16),
        Asset("Plasma beam tiles", "Effects", 0x9AF800, 0x200, palette = 0x90C441, paletteColors = 16),
        Asset("Spazer tiles", "Effects", 0x9AFA00, 0x200, palette = 0x90C461, paletteColors = 16),
        Asset("Mother Brain death-beam effects", "Effects", 0xB7A000, 0x800, palette = 0xA994B2, paletteColors = 16),
    )

    fun raw(parser: RomParser, address: Int, size: Int): ByteArray {
        val pc = parser.snesToPc(address)
        val data = parser.getRomData()
        require(size >= 0 && pc >= 0 && pc + size <= data.size)
        return data.copyOfRange(pc, pc + size)
    }

    fun palette(parser: RomParser, address: Int, count: Int = 256, transparentRows: Boolean = true): IntArray {
        val bytes = raw(parser, address, count * 2)
        return IntArray(count) { i ->
            if (transparentRows && i % 16 == 0) 0 else EnemySpriteGraphics.snesColorToArgb((bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8))
        }
    }

    fun render(
        parser: RomParser,
        asset: Asset,
        paletteRow: Int = 0,
        mapOverride: ByteArray? = null,
    ): SpriteAnimationFrame? = runCatching {
        if (asset.format == Format.TITLE_SCENE) return renderTitleScene(parser, asset, mapOverride)
        val tiles = if (asset.compressed) parser.decompressLZ2(asset.graphics) else raw(parser, asset.graphics, asset.bytes)
        val colors = palette(parser, asset.palette, asset.paletteColors, transparentRows = asset.format == Format.TILES_4BPP || asset.format == Format.OAM)
        require(paletteRow in 0 until asset.paletteColors / 16)
        val decoder = TileDecoder()
        if (asset.format == Format.OAM) {
            val renderer = EnemySpritemap(parser)
            val map = renderer.parseSpritemap(asset.map)?.let { original ->
                original.copy(entries = original.entries.map { it.copy(palRow = it.palRow or asset.paletteBase) })
            } ?: return null
            val rows = (0..7).associateWith { colors.copyOfRange(128 + it * 16, 144 + it * 16) }
            val sprite = renderer.renderSpritemap(map, tiles, rows.getValue(0), rows, EnemySpritemap.OamTileNumberMode.LOW_9) ?: return null
            return SpriteAnimationFrame(sprite.pixels, sprite.width, sprite.height, label = asset.name)
        }
        val mode7 = asset.format == Format.MODE7_TILES || asset.format == Format.MODE7_MAP
        val tileSize = if (mode7) 64 else 32
        val decoded = Array(tiles.size / tileSize) { index ->
            if (mode7) IntArray(64) { tiles[index * 64 + it].toInt() and 255 }
            else decoder.decode4bppTileIndices(tiles, index * 32)
        }
        val map = mapOverride ?: if (asset.map != 0) parser.decompressLZ2(asset.map) else null
        val count = if (map == null) decoded.size else map.size / if (mode7) 1 else 2
        val columns = asset.columns
        val rows = (count + columns - 1) / columns
        val width = columns * 8
        val height = rows * 8
        val pixels = IntArray(width * height)
        for (i in 0 until count) {
            val word = when {
                map == null -> i
                mode7 -> map[i].toInt() and 255
                else -> (map[i * 2].toInt() and 255) or ((map[i * 2 + 1].toInt() and 255) shl 8)
            }
            val tile = decoded.getOrNull(if (mode7 || map == null) word else word and 0x3FF) ?: continue
            // Standard BG nametables store consecutive 32x32 screen blocks.
            val bx = if (map != null && !mode7) (i / 1024 % (columns / 32)) * 32 + i % 32 else i % columns
            val by = if (map != null && !mode7) (i / 1024 / (columns / 32)) * 32 + i % 1024 / 32 else i / columns
            val row = if (map == null) paletteRow else word shr 10 and 7
            for (y in 0..7) for (x in 0..7) {
                val sx = if (map != null && !mode7 && word and 0x4000 != 0) 7 - x else x
                val sy = if (map != null && !mode7 && word and 0x8000 != 0) 7 - y else y
                val ci = tile[sy * 8 + sx]
                val colorIndex = if (mode7) ci else if (ci == 0 && map != null) 0 else row * 16 + ci
                val pixel = if (ci == 0 && map == null) 0 else colors.getOrElse(colorIndex) { 0 }
                pixels[(by * 8 + y) * width + bx * 8 + x] = pixel
            }
        }
        SpriteAnimationFrame(pixels, width, height, label = asset.name)
    }.getOrNull()

    private fun renderTitleScene(parser: RomParser, asset: Asset, mapOverride: ByteArray?): SpriteAnimationFrame? {
        val background = render(parser, asset.copy(format = Format.MODE7_MAP), mapOverride = mapOverride) ?: return null
        val width = 256
        val height = 224
        val pixels = IntArray(width * height) { i -> background.pixels[(i / width) * background.width + i % width] }
        val tiles = parser.decompressLZ2(0x9580D8)
        val colors = palette(parser, 0x8CE1E9)
        val rows = (0..7).associateWith { colors.copyOfRange(128 + it * 16, 144 + it * 16) }
        val renderer = EnemySpritemap(parser)
        for ((address, y, base) in listOf(Triple(0x8C879D, 48, 2), Triple(0x8C8103, 196, 4))) {
            val map = renderer.parseSpritemap(address)?.let { original ->
                original.copy(entries = original.entries.map { it.copy(palRow = it.palRow or base) })
            } ?: return null
            val sprite = renderer.renderSpritemap(map, tiles, rows.getValue(base), rows, EnemySpritemap.OamTileNumberMode.LOW_9) ?: return null
            for (sy in 0 until sprite.height) for (sx in 0 until sprite.width) {
                val dx = 128 - sprite.originX + sx
                val dy = y - sprite.originY + sy
                val pixel = sprite.pixels[sy * sprite.width + sx]
                if (dx in 0 until width && dy in 0 until height && pixel ushr 24 != 0) pixels[dy * width + dx] = pixel
            }
        }
        return SpriteAnimationFrame(pixels, width, height, label = asset.name)
    }
}
