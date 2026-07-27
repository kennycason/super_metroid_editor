package com.supermetroid.editor.rom

import com.supermetroid.editor.rom.RomConstants.BANK_ROOM_DATA

enum class RomGraphicsCatalogSource {
    VANILLA_FIXED,
    DISCOVERED_BANK_8F,
}

data class TilesetPointerEntry(
    val tileTablePtr: Int,
    val gfxPtr: Int,
    val palettePtr: Int,
    val valid: Boolean,
)

data class RomGraphicsCatalog(
    val source: RomGraphicsCatalogSource,
    val tableSnesAddress: Int,
    val entries: List<TilesetPointerEntry>,
) {
    fun entry(tilesetId: Int): TilesetPointerEntry? =
        entries.getOrNull(tilesetId)?.takeIf { it.valid }
}

object RomGraphicsCatalogDetector {
    private const val TILESET_ENTRY_BYTES = 9
    private const val TILESET_TABLE_BYTES = TileGraphics.NUM_TILESETS * TILESET_ENTRY_BYTES

    fun detect(parser: RomParser): RomGraphicsCatalog {
        val usedTilesets = usedTilesetIds(parser)
        val validator = TilesetEntryValidator(parser)
        val fixedPc = parser.snesToPc(TileGraphics.TILESET_TABLE_SNES)
        val fixed = readTableAt(
            parser = parser,
            tablePc = fixedPc,
            source = RomGraphicsCatalogSource.VANILLA_FIXED,
            validator = validator,
        )
        if (fixed.usableFor(usedTilesets)) return fixed

        return scanBank8F(parser, usedTilesets, validator) ?: fixed
    }

    private fun scanBank8F(
        parser: RomParser,
        usedTilesets: Set<Int>,
        validator: TilesetEntryValidator,
    ): RomGraphicsCatalog? {
        val romData = parser.getRomData()
        val bankStart = runCatching { parser.snesToPc(BANK_ROOM_DATA or 0x8000) }.getOrNull() ?: return null
        val bankEndExclusive = runCatching { parser.snesToPc(BANK_ROOM_DATA or 0xFFFF) + 1 }.getOrNull() ?: return null
        val start = maxOf(bankStart, 0)
        val end = minOf(bankEndExclusive, romData.size)
        val required = requiredValidEntries(usedTilesets)
        var best: TableCandidate? = null

        for (pc in start..(end - TILESET_TABLE_BYTES)) {
            val cheapValid = cheapValidEntryCount(parser, pc)
            if (cheapValid < required) continue
            val table = readTableAt(
                parser = parser,
                tablePc = pc,
                source = RomGraphicsCatalogSource.DISCOVERED_BANK_8F,
                validator = validator,
            )
            val usedValid = table.validEntryCount(usedTilesets)
            if (usedValid < required) continue
            val candidate = TableCandidate(
                table = table,
                usedValid = usedValid,
                prefixValid = table.validPrefixCount(),
                totalValid = table.entries.count { it.valid },
            )
            val currentBest = best
            if (currentBest == null || candidate.isBetterThan(currentBest)) {
                best = candidate
            }
        }

        return best?.table
    }

    private fun readTableAt(
        parser: RomParser,
        tablePc: Int,
        source: RomGraphicsCatalogSource,
        validator: TilesetEntryValidator,
    ): RomGraphicsCatalog {
        val romData = parser.getRomData()
        val entries = MutableList(TileGraphics.NUM_TILESETS) {
            TilesetPointerEntry(0, 0, 0, valid = false)
        }
        if (tablePc < 0 || tablePc + TILESET_TABLE_BYTES > romData.size) {
            return RomGraphicsCatalog(source, 0, entries)
        }

        for (tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            val offset = tablePc + tilesetId * TILESET_ENTRY_BYTES
            val entry = TilesetPointerEntry(
                tileTablePtr = readU24(romData, offset),
                gfxPtr = readU24(romData, offset + 3),
                palettePtr = readU24(romData, offset + 6),
                valid = false,
            )
            entries[tilesetId] = entry.copy(valid = validator.isValid(entry))
        }
        return RomGraphicsCatalog(
            source = source,
            tableSnesAddress = runCatching { parser.pcToSnes(tablePc) }.getOrDefault(0),
            entries = entries,
        )
    }

    private fun usedTilesetIds(parser: RomParser): Set<Int> {
        val fromRooms = runCatching {
            parser.roomCatalog.rooms
                .mapNotNull { roomInfo -> parser.readRoomHeader(roomInfo.getRoomIdAsInt())?.tileset }
                .filter { it in 0 until TileGraphics.NUM_TILESETS }
                .toSet()
        }.getOrDefault(emptySet())
        return fromRooms.ifEmpty { (0 until TileGraphics.NUM_TILESETS).toSet() }
    }

    private fun RomGraphicsCatalog.usableFor(usedTilesets: Set<Int>): Boolean =
        validEntryCount(usedTilesets) >= usedTilesets.size

    private fun RomGraphicsCatalog.validEntryCount(usedTilesets: Set<Int>): Int =
        usedTilesets.count { entries.getOrNull(it)?.valid == true }

    private fun RomGraphicsCatalog.validPrefixCount(): Int {
        var count = 0
        for (entry in entries) {
            if (!entry.valid) break
            count++
        }
        return count
    }

    private fun requiredValidEntries(usedTilesets: Set<Int>): Int {
        if (usedTilesets.size <= 1) return 1
        return maxOf(8, (usedTilesets.size * 2 + 2) / 3)
    }

    private fun cheapValidEntryCount(parser: RomParser, tablePc: Int): Int {
        val romData = parser.getRomData()
        if (tablePc < 0 || tablePc + TILESET_TABLE_BYTES > romData.size) return 0
        var valid = 0
        for (tilesetId in 0 until TileGraphics.NUM_TILESETS) {
            val offset = tablePc + tilesetId * TILESET_ENTRY_BYTES
            if (pointerLooksReadable(parser, readU24(romData, offset)) &&
                pointerLooksReadable(parser, readU24(romData, offset + 3)) &&
                pointerLooksReadable(parser, readU24(romData, offset + 6))
            ) {
                valid++
            }
        }
        return valid
    }

    private fun pointerLooksReadable(parser: RomParser, snesAddress: Int): Boolean {
        if (snesAddress == 0 || snesAddress == 0xFFFFFF) return false
        val b0 = snesAddress and 0xFF
        val b1 = (snesAddress ushr 8) and 0xFF
        val b2 = (snesAddress ushr 16) and 0xFF
        if (b0 == b1 && b1 == b2) return false
        val bank = (snesAddress ushr 16) and 0xFF
        val address = snesAddress and 0xFFFF
        if (bank < 0x80 || address < 0x8000) return false
        val pc = runCatching { parser.snesToPc(snesAddress) }.getOrNull() ?: return false
        return pc in parser.getRomData().indices
    }

    private fun readU24(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16)

    private data class TableCandidate(
        val table: RomGraphicsCatalog,
        val usedValid: Int,
        val prefixValid: Int,
        val totalValid: Int,
    ) {
        fun isBetterThan(other: TableCandidate): Boolean =
            compareValuesBy(
                this,
                other,
                TableCandidate::usedValid,
                TableCandidate::prefixValid,
                TableCandidate::totalValid,
            ) > 0
    }

    private class TilesetEntryValidator(private val parser: RomParser) {
        private val cache = mutableMapOf<Pair<Int, DataKind>, Boolean>()

        fun isValid(entry: TilesetPointerEntry): Boolean =
            isValidData(entry.tileTablePtr, DataKind.TILE_TABLE) &&
                isValidData(entry.gfxPtr, DataKind.GFX) &&
                isValidData(entry.palettePtr, DataKind.PALETTE)

        private fun isValidData(snesAddress: Int, kind: DataKind): Boolean =
            cache.getOrPut(snesAddress to kind) {
                if (!pointerLooksReadable(parser, snesAddress)) return@getOrPut false
                val data = runCatching { parser.decompressLZ2(snesAddress) }.getOrNull()
                    ?: return@getOrPut false
                when (kind) {
                    DataKind.TILE_TABLE -> data.size >= 8 &&
                        data.size <= TileGraphics.METATILE_COUNT * 8 &&
                        data.size % 8 == 0
                    DataKind.GFX -> data.size >= TileGraphics.BYTES_PER_TILE &&
                        data.size <= TileGraphics.TOTAL_TILES * TileGraphics.BYTES_PER_TILE * 2 &&
                        data.size % TileGraphics.BYTES_PER_TILE == 0
                    DataKind.PALETTE -> data.size in 64..512 && data.size % 2 == 0
                }
            }
    }

    private enum class DataKind {
        TILE_TABLE,
        GFX,
        PALETTE,
    }
}
