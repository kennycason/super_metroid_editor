package com.supermetroid.editor.rom

import com.supermetroid.editor.rom.RomConstants.BANK_ROOM_DATA
import com.supermetroid.editor.rom.RomConstants.ROM_SIZE

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
    val creTileTablePtr: Int = TileGraphics.CRE_TILE_TABLE_SNES,
    val creGfxPtr: Int = TileGraphics.CRE_GFX_SNES,
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
        if (fixed.usableFor(usedTilesets)) return fixed.withCrePointers(parser)

        return (scanBank8F(parser, usedTilesets, validator) ?: fixed).withCrePointers(parser)
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

    private fun RomGraphicsCatalog.withCrePointers(parser: RomParser): RomGraphicsCatalog {
        val crePointers = CreGraphicsDetector.detect(parser)
        return copy(
            creTileTablePtr = crePointers.tileTablePtr,
            creGfxPtr = crePointers.gfxPtr,
        )
    }

    private data class CrePointers(val tileTablePtr: Int, val gfxPtr: Int)

    private object CreGraphicsDetector {
        private const val POINTER_LOAD_BYTES = 14
        private const val CRE_GFX_BYTES =
            (TileGraphics.TOTAL_TILES - TileGraphics.CRE_TILE_START) * TileGraphics.BYTES_PER_TILE
        private const val MIN_CRE_TABLE_UNIQUE_TILES = 64
        private const val MIN_CRE_TABLE_COMPRESSED_BYTES = 512

        fun detect(parser: RomParser): CrePointers {
            val fixedTable = parser.decompressOrNull(TileGraphics.CRE_TILE_TABLE_SNES)
            val fixedGfx = parser.decompressOrNull(TileGraphics.CRE_GFX_SNES)
            val fixedTableValid = fixedTable?.data?.let(::isCreTileTable) == true
            val fixedGfxValid = fixedGfx?.data?.size == CRE_GFX_BYTES
            if (fixedTableValid && fixedGfxValid) {
                return CrePointers(TileGraphics.CRE_TILE_TABLE_SNES, TileGraphics.CRE_GFX_SNES)
            }

            val refs = decompressionPointerReferences(parser)
            val tablePtr = if (fixedTableValid) {
                TileGraphics.CRE_TILE_TABLE_SNES
            } else {
                findReferencedCreTable(parser, refs) ?: scanExpandedCreTable(parser) ?: TileGraphics.CRE_TILE_TABLE_SNES
            }
            val gfxPtr = if (fixedGfxValid) {
                TileGraphics.CRE_GFX_SNES
            } else {
                findReferencedCreGfx(parser, refs) ?: TileGraphics.CRE_GFX_SNES
            }
            return CrePointers(tablePtr, gfxPtr)
        }

        private fun findReferencedCreTable(parser: RomParser, refs: Map<Int, Int>): Int? =
            refs.keys
                .mapNotNull { ptr ->
                    val decompressed = parser.decompressOrNull(ptr) ?: return@mapNotNull null
                    val score = creTableScore(decompressed.data) ?: return@mapNotNull null
                    CreTableCandidate(
                        ptr = ptr,
                        referenceCount = refs.getValue(ptr),
                        consumed = decompressed.consumed,
                        score = score,
                    )
                }
                .filter { it.score.uniqueTileCount >= MIN_CRE_TABLE_UNIQUE_TILES }
                .maxWithOrNull(creTableComparator)
                ?.ptr

        private fun scanExpandedCreTable(parser: RomParser): Int? =
            scanExpandedData(parser, TileGraphics.CRE_METATILE_COUNT * 8)
                .mapNotNull { candidate ->
                    val score = creTableScore(candidate.data) ?: return@mapNotNull null
                    CreTableCandidate(
                        ptr = candidate.ptr,
                        referenceCount = 0,
                        consumed = candidate.consumed,
                        score = score,
                    )
                }
                .filter {
                    it.score.uniqueTileCount >= MIN_CRE_TABLE_UNIQUE_TILES &&
                        it.consumed >= MIN_CRE_TABLE_COMPRESSED_BYTES
                }
                .maxWithOrNull(creTableComparator)
                ?.ptr

        private fun findReferencedCreGfx(parser: RomParser, refs: Map<Int, Int>): Int? =
            refs.keys
                .mapNotNull { ptr ->
                    val decompressed = parser.decompressOrNull(ptr) ?: return@mapNotNull null
                    if (decompressed.data.size != CRE_GFX_BYTES) return@mapNotNull null
                    GfxCandidate(
                        ptr = ptr,
                        referenceCount = refs.getValue(ptr),
                        consumed = decompressed.consumed,
                        nonZeroBytes = decompressed.data.count { it.toInt() != 0 },
                        uniqueBytes = decompressed.data.map { it.toInt() and 0xFF }.toSet().size,
                    )
                }
                .filter { it.uniqueBytes >= 16 && it.nonZeroBytes > CRE_GFX_BYTES / 4 }
                .maxWithOrNull(gfxComparator)
                ?.ptr

        private fun isCreTileTable(data: ByteArray): Boolean =
            creTableScore(data)?.let { score ->
                score.creTileWordCount >= TileGraphics.CRE_METATILE_COUNT * 4 * 3 / 4 &&
                    score.uniqueTileCount >= MIN_CRE_TABLE_UNIQUE_TILES
            } == true

        private fun creTableScore(data: ByteArray): CreTableScore? {
            if (data.size != TileGraphics.CRE_METATILE_COUNT * 8) return null
            var creTileWords = 0
            var nonZeroWords = 0
            val uniqueTiles = mutableSetOf<Int>()
            var offset = 0
            while (offset + 1 < data.size) {
                val word = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                val tile = word and 0x03FF
                if (tile >= TileGraphics.CRE_TILE_START) creTileWords++
                if (word != 0) nonZeroWords++
                uniqueTiles.add(tile)
                offset += 2
            }
            return CreTableScore(
                creTileWordCount = creTileWords,
                nonZeroWordCount = nonZeroWords,
                uniqueTileCount = uniqueTiles.size,
            )
        }

        private fun decompressionPointerReferences(parser: RomParser): Map<Int, Int> {
            val romData = parser.getRomData()
            val refs = mutableMapOf<Int, Int>()
            for (pc in 0..(romData.size - POINTER_LOAD_BYTES)) {
                if ((romData[pc].toInt() and 0xFF) != 0xA9) continue
                if ((romData[pc + 3].toInt() and 0xFF) != 0x85 || (romData[pc + 4].toInt() and 0xFF) != 0x48) continue
                if ((romData[pc + 5].toInt() and 0xFF) != 0xA9) continue
                if ((romData[pc + 8].toInt() and 0xFF) != 0x85 || (romData[pc + 9].toInt() and 0xFF) != 0x47) continue
                if ((romData[pc + 10].toInt() and 0xFF) != 0x22 ||
                    (romData[pc + 11].toInt() and 0xFF) != 0xFF ||
                    (romData[pc + 12].toInt() and 0xFF) != 0xB0 ||
                    (romData[pc + 13].toInt() and 0xFF) != 0x80
                ) {
                    continue
                }

                val bankWord = readU16(romData, pc + 1)
                val lowWord = readU16(romData, pc + 6)
                val ptr = (((bankWord ushr 8) and 0xFF) shl 16) or lowWord
                if (ptrLooksReadable(parser, ptr)) {
                    refs[ptr] = refs.getOrDefault(ptr, 0) + 1
                }
            }
            return refs
        }

        private fun scanExpandedData(parser: RomParser, targetSize: Int): List<DataCandidate> {
            val romData = parser.getRomData()
            val start = minOf(romData.size, parser.romStartOffsetForLayout() + ROM_SIZE)
            val results = mutableListOf<DataCandidate>()
            for (pc in start until romData.size) {
                if ((romData[pc].toInt() and 0xFF) == 0xFF) continue
                val decompressed = runCatching { parser.decompressLZ5AtPcWithSize(pc) }.getOrNull() ?: continue
                if (decompressed.first.size == targetSize) {
                    results.add(
                        DataCandidate(
                            ptr = parser.pcToSnes(pc),
                            consumed = decompressed.second,
                            data = decompressed.first,
                        )
                    )
                }
            }
            return results
        }

        private fun RomParser.decompressOrNull(snesAddress: Int): DecompressedData? =
            runCatching {
                val (data, consumed) = decompressLZ2WithSize(snesAddress)
                DecompressedData(data, consumed)
            }.getOrNull()

        private fun ptrLooksReadable(parser: RomParser, snesAddress: Int): Boolean {
            if (snesAddress == 0 || snesAddress == 0xFFFFFF) return false
            val bank = (snesAddress ushr 16) and 0xFF
            val address = snesAddress and 0xFFFF
            if (bank < 0x80 || address < 0x8000) return false
            val pc = runCatching { parser.snesToPc(snesAddress) }.getOrNull() ?: return false
            return pc in parser.getRomData().indices
        }

        private val creTableComparator = compareBy<CreTableCandidate> { it.referenceCount }
            .thenBy { it.score.creTileWordCount }
            .thenBy { it.score.nonZeroWordCount }
            .thenBy { it.score.uniqueTileCount }
            .thenBy { it.consumed }

        private val gfxComparator = compareBy<GfxCandidate> { it.referenceCount }
            .thenBy { it.uniqueBytes }
            .thenBy { it.nonZeroBytes }
            .thenBy { it.consumed }

        private data class DecompressedData(val data: ByteArray, val consumed: Int)
        private data class DataCandidate(val ptr: Int, val consumed: Int, val data: ByteArray)
        private data class CreTableScore(
            val creTileWordCount: Int,
            val nonZeroWordCount: Int,
            val uniqueTileCount: Int,
        )
        private data class CreTableCandidate(
            val ptr: Int,
            val referenceCount: Int,
            val consumed: Int,
            val score: CreTableScore,
        )
        private data class GfxCandidate(
            val ptr: Int,
            val referenceCount: Int,
            val consumed: Int,
            val nonZeroBytes: Int,
            val uniqueBytes: Int,
        )
    }
}
