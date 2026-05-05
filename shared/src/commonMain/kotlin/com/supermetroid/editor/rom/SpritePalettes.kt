package com.supermetroid.editor.rom

/**
 * Samus suit and beam palette reading/writing.
 *
 * These palettes are stored as raw (uncompressed) BGR555 data at fixed
 * PC offsets in the ROM, unlike tileset palettes which are LZ5-compressed.
 *
 * Each suit has 33 palette rows × 16 colors = 528 colors (1056 bytes).
 * The first row is the "base" palette; subsequent rows are animation frames
 * (visor flash, damage flash, crystal flash, etc.).
 *
 * ROM addresses verified against super_metroid_colors regions.ts.
 */
object SpritePalettes {

    // ─── ROM PC offsets (unheadered) ───────────────────────────────

    /** Palette region: id, display name, PC offset, color count */
    data class PaletteRegion(
        val id: String,
        val name: String,
        val offset: Int,
        val colorCount: Int,
        val category: String
    ) {
        val byteSize: Int get() = colorCount * 2
        val rowCount: Int get() = colorCount / 16
    }

    val SAMUS_POWER = PaletteRegion("samus_power", "Power Suit", 0x0D9400, 528, "samus")
    val SAMUS_VARIA = PaletteRegion("samus_varia", "Varia Suit", 0x0D9820, 528, "samus")
    val SAMUS_GRAVITY = PaletteRegion("samus_gravity", "Gravity Suit", 0x0D9C40, 528, "samus")
    val SAMUS_EXTRA = PaletteRegion("samus_extra", "Samus Extra", 0x0DA060, 432, "samus")
    val BEAM_STANDARD = PaletteRegion("beam_standard", "Standard Beam", 0x0D7C00, 16, "beams")

    val REGIONS: List<PaletteRegion> = listOf(
        SAMUS_POWER, SAMUS_VARIA, SAMUS_GRAVITY, SAMUS_EXTRA, BEAM_STANDARD
    )

    val SAMUS_REGIONS: List<PaletteRegion> = REGIONS.filter { it.category == "samus" }
    val BEAM_REGIONS: List<PaletteRegion> = REGIONS.filter { it.category == "beams" }

    fun findRegion(id: String): PaletteRegion? = REGIONS.find { it.id == id }

    // ─── Read/write BGR555 colors from ROM ─────────────────────────

    /**
     * Read a palette region from ROM as an array of BGR555 values.
     * Returns null if the offset is out of range.
     */
    fun readColors(romData: ByteArray, region: PaletteRegion): IntArray? {
        val offset = region.offset
        if (offset + region.byteSize > romData.size) return null
        val colors = IntArray(region.colorCount)
        for (i in 0 until region.colorCount) {
            val addr = offset + i * 2
            colors[i] = (romData[addr].toInt() and 0xFF) or
                    ((romData[addr + 1].toInt() and 0xFF) shl 8)
        }
        return colors
    }

    /**
     * Read a single 16-color palette row from a region.
     * Row 0 is the "base" palette displayed in the editor preview.
     */
    fun readRow(romData: ByteArray, region: PaletteRegion, row: Int): IntArray? {
        if (row < 0 || row >= region.rowCount) return null
        val offset = region.offset + row * 32
        if (offset + 32 > romData.size) return null
        val colors = IntArray(16)
        for (i in 0 until 16) {
            val addr = offset + i * 2
            colors[i] = (romData[addr].toInt() and 0xFF) or
                    ((romData[addr + 1].toInt() and 0xFF) shl 8)
        }
        return colors
    }

    /**
     * Write modified BGR555 colors back to a ROM data array (in-place).
     * Used during export to patch Samus/beam palettes.
     */
    fun writeColors(romData: ByteArray, region: PaletteRegion, colors: IntArray) {
        require(colors.size == region.colorCount) {
            "Expected ${region.colorCount} colors for ${region.id}, got ${colors.size}"
        }
        val offset = region.offset
        for (i in colors.indices) {
            val addr = offset + i * 2
            romData[addr] = (colors[i] and 0xFF).toByte()
            romData[addr + 1] = ((colors[i] shr 8) and 0xFF).toByte()
        }
    }

    /**
     * Convert a BGR555 color array to raw bytes (for base64 serialization).
     */
    fun colorsToBytes(colors: IntArray): ByteArray {
        val data = ByteArray(colors.size * 2)
        for (i in colors.indices) {
            data[i * 2] = (colors[i] and 0xFF).toByte()
            data[i * 2 + 1] = ((colors[i] shr 8) and 0xFF).toByte()
        }
        return data
    }

    /**
     * Convert raw bytes back to a BGR555 color array.
     */
    fun bytesToColors(data: ByteArray): IntArray {
        val colors = IntArray(data.size / 2)
        for (i in colors.indices) {
            colors[i] = (data[i * 2].toInt() and 0xFF) or
                    ((data[i * 2 + 1].toInt() and 0xFF) shl 8)
        }
        return colors
    }

    // ─── Tileset names (for Area palette editor) ─────────────────

    val TILESET_NAMES = arrayOf(
        "Crateria Cave",            // 0
        "Crateria Surface",         // 1
        "Crateria / Blue Brinstar", // 2
        "Red Crateria",             // 3
        "Old Tourian",              // 4
        "Wrecked Ship (off)",       // 5
        "Wrecked Ship (on)",        // 6
        "Green Brinstar",           // 7
        "Red Brinstar",             // 8
        "Pre-Kraid / Warehouse",    // 9
        "Upper Norfair",            // 10
        "Lower Norfair",            // 11
        "Inner Maridia",            // 12
        "Outer Maridia",            // 13
        "Tourian 1",                // 14
        "Tourian 2",                // 15
        "Mother Brain",             // 16
        "Ceres",                    // 17
        "Ceres Elevator",           // 18
        "Blue Crateria (alt)",      // 19
        "Green Brinstar (alt)",     // 20
        "Crateria (dark)",          // 21
        "Kraid Room",               // 22
        "Crocomire Room",           // 23
        "Draygon Room",             // 24
        "Unused 1",                 // 25
        "Unused 2",                 // 26
        "Debug",                    // 27
        "Title Screen",             // 28
    )

    fun tilesetName(index: Int): String =
        if (index in TILESET_NAMES.indices) TILESET_NAMES[index] else "Tileset $index"

    /**
     * Convert BGR555 to ARGB8888 for UI display.
     */
    fun bgr555ToArgb(bgr555: Int): Int {
        val r5 = bgr555 and 0x1F
        val g5 = (bgr555 shr 5) and 0x1F
        val b5 = (bgr555 shr 10) and 0x1F
        val r8 = (r5 shl 3) or (r5 shr 2)
        val g8 = (g5 shl 3) or (g5 shr 2)
        val b8 = (b5 shl 3) or (b5 shr 2)
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }
}
