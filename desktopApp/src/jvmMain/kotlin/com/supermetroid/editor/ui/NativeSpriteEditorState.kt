package com.supermetroid.editor.ui

import com.supermetroid.editor.asm.AsmNativeSpriteAssetAccess
import com.supermetroid.editor.asm.AsmNativeSpritePaletteAccess
import com.supermetroid.editor.asm.AsmCinematicMapAssetAccess
import com.supermetroid.editor.data.TilesetGfxData
import com.supermetroid.editor.rom.*
import java.util.Base64

/** Source edits are portable project data; a preview parser never mutates the loaded ROM. */
internal class NativeSpriteEditorState(
    private val customGfx: () -> TilesetGfxData,
    private val onDirty: () -> Unit,
    private val asmAccess: (String) -> AsmNativeSpriteAssetAccess? = { null },
    private val saveAsmPixels: (String, ByteArray, ByteArray) -> Boolean = { _, _, _ -> false },
    private val restoreAsmPixels: (String) -> Boolean = { false },
    private val asmPaletteAccess: (Int) -> AsmNativeSpritePaletteAccess? = { null },
    private val asmPaletteAccessAll: (Set<Int>) -> Map<Int, AsmNativeSpritePaletteAccess> = { emptyMap() },
    private val saveAsmPalette: (Int, ByteArray, String) -> Boolean = { _, _, _ -> false },
    private val restoreAsmPalette: (Int) -> Boolean = { false },
    private val asmCinematicMapAccessAll: () -> Map<String, AsmCinematicMapAssetAccess> = { emptyMap() },
    private val onBlocked: (String) -> Unit = {},
) {
    data class Sheet(
        val source: NativeSpriteAssets.Source, val raw: ByteArray, val palette: IntArray,
        val indices: IntArray, val pixels: IntArray, val width: Int, val height: Int,
        val asmSourceBytes: ByteArray? = null,
    )

    fun previewParser(parser: RomParser): RomParser {
        val gfx = customGfx()
        val sourceAccesses = NativeSpriteAssets.SOURCES.associate { source -> source.key to asmAccess(source.key) }
        val paletteAddresses = runCatching { NativeSpriteAssets.paletteRows(parser) }
            .getOrDefault(emptySet())
            .filterTo(linkedSetOf()) { it in 0x800000..0xFFFFFF }
        val paletteAccesses = asmPaletteAccessAll(paletteAddresses)
        val sourceBackedPalettePcs = paletteAccesses.keys.mapTo(hashSetOf(), parser::snesToPc)
        val cinematicMapAccesses = asmCinematicMapAccessAll()
        val sourceRom = parser.getRomData().copyOf()
        sourceAccesses.forEach { (sourceKey, access) ->
            if (access !is AsmNativeSpriteAssetAccess.Editable) return@forEach
            val pc = parser.snesToPc(NativeSpriteAssets.source(sourceKey).address)
            require(pc >= 0 && pc + access.sourceBytes.size <= sourceRom.size) {
                "${access.assetPath} cannot be previewed at its native source address"
            }
            // Preview-only: a relocated compressed stream may temporarily span
            // its old envelope so native render recipes can decode the current
            // authored bytes without changing the loaded ROM or build placement.
            access.sourceBytes.copyInto(sourceRom, pc)
        }
        cinematicMapAccesses.forEach { (sourceKey, access) ->
            if (access !is AsmCinematicMapAssetAccess.Editable) return@forEach
            val pc = parser.snesToPc(CinematicMapAssets.source(sourceKey).address)
            require(pc >= 0 && pc + access.sourceBytes.size <= sourceRom.size) {
                "${access.assetPath} cannot be previewed at its native source address"
            }
            access.sourceBytes.copyInto(sourceRom, pc)
        }
        val sourceParser = RomParser(sourceRom)
        val plan = RomWritePlan(sourceRom)
        val legacyVisuals = TilesetGfxData(
            visualTileBlocks = gfx.visualTileBlocks.filterKeys { sourceAccesses[it] == null }.toMutableMap(),
            visualPalettes = gfx.visualPalettes.filterKeys { key ->
                key.toIntOrNull(16)?.let { paletteAccesses[it] } == null
            }.toMutableMap(),
        )
        NativeSpriteAssets.planEdits(sourceParser, legacyVisuals).forEach(plan::add)
        // Reuse existing palette owners, including shared Samus and boss palette ranges.
        for ((id, b64) in gfx.spritePalettes) {
            if (id.startsWith("enemy_pal:")) {
                val speciesId = requireNotNull(id.removePrefix("enemy_pal:").toIntOrNull(16))
                val bytes = Base64.getDecoder().decode(b64)
                require(bytes.size == 32)
                val header = parser.snesToPc(RomConstants.BANK_ENEMY_AI or speciesId)
                require(header >= 0 && header + 0x0D <= parser.getRomData().size)
                val address = (parser.readByteAt(header + 0x0C) shl 16) or parser.readUInt16At(header + 2)
                if (parser.snesToPc(address) in sourceBackedPalettePcs) continue
                plan.add(RomWriteIntent("graphics:$id", "Enemy palette $id", parser.snesToPc(address), bytes.map { it.toInt() and 255 }))
                continue
            }
            val region = SpritePalettes.findRegion(id) ?: continue
            val bytes = Base64.getDecoder().decode(b64)
            require(bytes.size == region.byteSize)
            if (sourceBackedPalettePcs.isEmpty()) {
                plan.add(RomWriteIntent("graphics:sprite-palette-$id", region.name, region.offset, bytes.map { it.toInt() and 255 }))
            } else {
                repeat(region.rowCount) { row ->
                    val offset = region.offset + row * 32
                    if (offset !in sourceBackedPalettePcs) {
                        val rowBytes = bytes.copyOfRange(row * 32, row * 32 + 32)
                        plan.add(RomWriteIntent(
                            "graphics:sprite-palette-$id-row-$row",
                            "${region.name} · row ${row + 1}",
                            offset,
                            rowBytes.map { it.toInt() and 255 },
                        ))
                    }
                }
            }
        }
        for ((key, encoded) in gfx.spriteTileBlocks) {
            if (!key.startsWith("enemy:")) continue
            val speciesId = requireNotNull(key.removePrefix("enemy:").toIntOrNull(16))
            val raw = Base64.getDecoder().decode(encoded)
            if (speciesId == 0xD07F && sourceAccesses["ADB600"] != null) continue
            val validation = EnemySpriteGraphics.validateEnemyTileEdit(sourceParser, speciesId, raw)
            require(validation.isExportable) { validation.errors.joinToString() }
            plan.add(RomWriteIntent("graphics:$key", key, requireNotNull(validation.pcAddress), raw.map { it.toInt() and 255 }))
        }
        paletteAccesses.values.forEach { access ->
            if (access !is AsmNativeSpritePaletteAccess.Editable) return@forEach
            val pc = parser.snesToPc(access.address)
            require(pc >= 0 && pc + access.rawPalette.size <= plan.romData.size) {
                "Palette ${access.address.toString(16).uppercase()} is outside the preview ROM"
            }
            access.rawPalette.copyInto(plan.romData, pc)
        }
        return RomParser(plan.romData)
    }

    fun loadSheet(parser: RomParser, sourceKey: String, paletteAddress: Int): Sheet {
        val source = NativeSpriteAssets.source(sourceKey)
        require(paletteAddress in NativeSpriteAssets.paletteRows(parser))
        val sourceAccess = asmAccess(sourceKey)
        if (sourceAccess is AsmNativeSpriteAssetAccess.AsmOnly) {
            throw IllegalStateException(sourceAccess.reason)
        }
        val effective = previewParser(parser)
        val raw = (sourceAccess as? AsmNativeSpriteAssetAccess.Editable)?.rawData ?: source.read(effective)
        val colors = RomVisualAssets.palette(effective, paletteAddress, if (source.mode7) 256 else 16, transparentRows = !source.mode7)
        colors[0] = 0
        val tileBytes = if (source.mode7) 64 else 32
        require(raw.size % tileBytes == 0)
        val width = 128
        val height = ((raw.size / tileBytes + 15) / 16) * 8
        val indices = IntArray(width * height) { -1 }
        val pixels = IntArray(indices.size)
        val decoder = TileDecoder()
        for (tile in 0 until raw.size / tileBytes) {
            val decoded = if (source.mode7) IntArray(64) { raw[tile * 64 + it].toInt() and 255 }
                else decoder.decode4bppTileIndices(raw, tile * 32)
            for (y in 0..7) for (x in 0..7) {
                val i = (tile / 16 * 8 + y) * width + tile % 16 * 8 + x
                indices[i] = decoded[y * 8 + x]
                pixels[i] = colors[indices[i]]
            }
        }
        return Sheet(
            source,
            raw,
            colors,
            indices,
            pixels,
            width,
            height,
            asmSourceBytes = (sourceAccess as? AsmNativeSpriteAssetAccess.Editable)?.sourceBytes,
        )
    }

    /** Preserve original indexes when equal palette colors would make an ARGB round trip ambiguous. */
    fun encodePixels(sheet: Sheet, pixels: IntArray): ByteArray {
        require(pixels.size == sheet.pixels.size) { "Source sheet dimensions cannot change" }
        val raw = sheet.raw.copyOf()
        for (i in pixels.indices) {
            if (pixels[i] == sheet.pixels[i]) continue
            require(sheet.indices[i] >= 0) { "Pixels outside the source tile data cannot be painted" }
            val color = if (pixels[i] ushr 24 == 0) 0 else pixels[i]
            val index = sheet.palette.indexOf(color)
            require(index >= 0) { "Source pixels must use the selected palette" }
            val x = i % sheet.width
            val y = i / sheet.width
            val tile = (y / 8) * 16 + x / 8
            if (sheet.source.mode7) raw[tile * 64 + (y % 8) * 8 + x % 8] = index.toByte()
            else for (plane in 0..3) {
                val offset = tile * 32 + (plane / 2) * 16 + (y % 8) * 2 + plane % 2
                val bit = 1 shl (7 - x % 8)
                val old = raw[offset].toInt() and 255
                raw[offset] = (if (index and (1 shl plane) != 0) old or bit else old and bit.inv()).toByte()
            }
        }
        return raw
    }

    fun applyPixels(parser: RomParser, sheet: Sheet, pixels: IntArray): Boolean {
        val raw = encodePixels(sheet, pixels)
        sheet.asmSourceBytes?.let { expected ->
            if (!saveAsmPixels(sheet.source.key, raw, expected)) return false
            onDirty()
            return true
        }
        NativeSpriteAssets.encodedSource(parser, sheet.source, raw) // Reject overflow before changing project state.
        val ship = sheet.source.key == "ADB600"
        if (ship) require(EnemySpriteGraphics.readGraphicsBlock(parser, 0xD07F)?.snesAddress == sheet.source.address) { "Ship graphics owner was relocated" }
        val edits = if (ship) customGfx().spriteTileBlocks else customGfx().visualTileBlocks
        val key = if (ship) "enemy:D07F" else sheet.source.key
        val old = edits[key]
        if (raw.contentEquals(sheet.source.read(parser))) edits.remove(key)
        else edits[key] = Base64.getEncoder().encodeToString(raw)
        if (old != edits[key]) {
            onDirty()
            return true
        }
        return false
    }

    fun hasTiles(sourceKey: String): Boolean = when (val access = asmAccess(sourceKey)) {
        is AsmNativeSpriteAssetAccess.Editable -> access.modified || if (sourceKey == "ADB600") {
            customGfx().spriteTileBlocks.containsKey("enemy:D07F")
        } else customGfx().visualTileBlocks.containsKey(sourceKey)
        is AsmNativeSpriteAssetAccess.AsmOnly -> false
        null -> if (sourceKey == "ADB600") customGfx().spriteTileBlocks.containsKey("enemy:D07F")
        else customGfx().visualTileBlocks.containsKey(sourceKey)
    }

    fun resetTiles(sourceKey: String): Boolean {
        if (asmAccess(sourceKey) != null) {
            val restored = restoreAsmPixels(sourceKey)
            if (restored) onDirty()
            return restored
        }
        val removed = if (sourceKey == "ADB600") customGfx().spriteTileBlocks.remove("enemy:D07F") else customGfx().visualTileBlocks.remove(sourceKey)
        if (removed != null) onDirty()
        return removed != null
    }

    fun previewWithPixels(parser: RomParser, sheet: Sheet, pixels: IntArray): RomParser {
        val effective = previewParser(parser)
        val raw = encodePixels(sheet, pixels)
        val bytes = if (sheet.asmSourceBytes != null && !sheet.source.compressed) raw
        else NativeSpriteAssets.encodedSource(parser, sheet.source, raw)
        bytes.copyInto(effective.getRomData(), parser.snesToPc(sheet.source.address))
        return RomParser(effective.getRomData())
    }

    fun palette(parser: RomParser, address: Int): IntArray {
        val access = asmPaletteAccess(address)
        val bytes = (access as? AsmNativeSpritePaletteAccess.Editable)?.rawPalette
            ?: RomVisualAssets.raw(previewParser(parser), address, 32)
        return IntArray(16) { index ->
            if (index == 0) 0 else EnemySpriteGraphics.snesColorToArgb(
                (bytes[index * 2].toInt() and 0xFF) or ((bytes[index * 2 + 1].toInt() and 0xFF) shl 8),
            )
        }
    }

    fun savePalette(parser: RomParser, address: Int, colors: IntArray): Boolean {
        require(address in NativeSpriteAssets.paletteRows(parser) && colors.size == 16)
        val original = (asmPaletteAccess(address) as? AsmNativeSpritePaletteAccess.Editable)?.rawPalette
            ?: RomVisualAssets.raw(parser, address, 32)
        val bytes = original.copyOf()
        for (i in 1..15) {
            val bgr = EnemySpriteGraphics.argbToSnesColor(colors[i])
            bytes[i * 2] = bgr.toByte()
            bytes[i * 2 + 1] = (bgr shr 8).toByte()
        }
        return writePaletteRow(parser, address, bytes)
    }

    private fun paletteRegion(parser: RomParser, address: Int) = SpritePalettes.REGIONS.firstOrNull {
        val pc = parser.snesToPc(address)
        pc >= it.offset && pc + 32 <= it.offset + it.byteSize
    }

    private fun canRetireLegacyPalette(parser: RomParser, address: Int): Boolean {
        if (address == NativeSpriteAssets.shipPalette(parser)) return true
        val region = paletteRegion(parser, address) ?: return true
        val encoded = customGfx().spritePalettes[region.id] ?: return true
        val current = Base64.getDecoder().decode(encoded)
        val original = parser.getRomData().copyOfRange(region.offset, region.offset + region.byteSize)
        val rowOffset = parser.snesToPc(address) - region.offset
        return current.indices.all { index ->
            index in rowOffset until rowOffset + 32 || current[index] == original[index]
        }
    }

    private fun retireLegacyPalette(parser: RomParser, address: Int): Boolean {
        val gfx = customGfx()
        if (address == NativeSpriteAssets.shipPalette(parser)) {
            return gfx.spritePalettes.remove("enemy_pal:D07F") != null
        }
        val region = paletteRegion(parser, address)
        if (region != null) return gfx.spritePalettes.remove(region.id) != null
        return gfx.visualPalettes.remove(NativeSpriteAssets.key(address)) != null
    }

    private fun writePaletteRow(parser: RomParser, address: Int, bytes: ByteArray): Boolean {
        val gfx = customGfx()
        when (val access = asmPaletteAccess(address)) {
            is AsmNativeSpritePaletteAccess.AsmOnly -> {
                onBlocked("Palette edit blocked: ${access.reason}")
                return false
            }
            is AsmNativeSpritePaletteAccess.Editable -> {
                if (!canRetireLegacyPalette(parser, address)) {
                    onBlocked(
                        "Palette edit blocked: this legacy palette region also contains edits to other rows. " +
                            "Restore or migrate those rows first.",
                    )
                    return false
                }
                if (!saveAsmPalette(address, bytes, access.sourceText)) return false
                retireLegacyPalette(parser, address)
                onDirty()
                return true
            }
            null -> Unit
        }
        if (address == NativeSpriteAssets.shipPalette(parser)) {
            val key = "enemy_pal:D07F"
            val old = gfx.spritePalettes[key]
            if (bytes.contentEquals(RomVisualAssets.raw(parser, address, 32))) gfx.spritePalettes.remove(key)
            else gfx.spritePalettes[key] = Base64.getEncoder().encodeToString(bytes)
            if (old != gfx.spritePalettes[key]) {
                onDirty()
                return true
            }
            return false
        }
        val region = paletteRegion(parser, address)
        if (region != null) {
            val original = parser.getRomData().copyOfRange(region.offset, region.offset + region.byteSize)
            val current = gfx.spritePalettes[region.id]?.let { Base64.getDecoder().decode(it) } ?: original.copyOf()
            bytes.copyInto(current, parser.snesToPc(address) - region.offset)
            val old = gfx.spritePalettes[region.id]
            if (current.contentEquals(original)) gfx.spritePalettes.remove(region.id)
            else gfx.spritePalettes[region.id] = Base64.getEncoder().encodeToString(current)
            if (old != gfx.spritePalettes[region.id]) {
                onDirty()
                return true
            }
        } else {
            val key = NativeSpriteAssets.key(address)
            val old = gfx.visualPalettes[key]
            if (bytes.contentEquals(RomVisualAssets.raw(parser, address, 32))) gfx.visualPalettes.remove(key)
            else gfx.visualPalettes[key] = Base64.getEncoder().encodeToString(bytes)
            if (old != gfx.visualPalettes[key]) {
                onDirty()
                return true
            }
        }
        return false
    }

    fun resetPalette(parser: RomParser, address: Int): Boolean {
        val access = asmPaletteAccess(address)
        if (access is AsmNativeSpritePaletteAccess.Editable) {
            if (!canRetireLegacyPalette(parser, address)) {
                onBlocked(
                    "Palette restore blocked: this legacy palette region also contains edits to other rows.",
                )
                return false
            }
            val restored = if (access.modified) restoreAsmPalette(address) else false
            val retired = retireLegacyPalette(parser, address)
            if (restored || retired) onDirty()
            return restored || retired
        }
        return writePaletteRow(parser, address, RomVisualAssets.raw(parser, address, 32))
    }

    fun hasPalette(parser: RomParser, address: Int): Boolean {
        val sourceModified = (asmPaletteAccess(address) as? AsmNativeSpritePaletteAccess.Editable)?.modified == true
        val legacy = if (address == NativeSpriteAssets.shipPalette(parser)) {
            customGfx().spritePalettes.containsKey("enemy_pal:D07F")
        } else {
            paletteRegion(parser, address)?.let { customGfx().spritePalettes.containsKey(it.id) }
                ?: customGfx().visualPalettes.containsKey(NativeSpriteAssets.key(address))
        }
        return sourceModified || legacy
    }
}
