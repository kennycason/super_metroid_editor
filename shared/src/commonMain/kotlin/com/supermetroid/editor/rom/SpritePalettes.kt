package com.supermetroid.editor.rom

/**
 * Raw sprite palette reading/writing for Samus, beams, bosses, and enemies.
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
        val description: String,
        val offset: Int,
        val colorCount: Int,
        val category: String
    ) {
        val byteSize: Int get() = colorCount * 2
        val rowCount: Int get() = colorCount / 16
    }

    private fun region(
        id: String,
        name: String,
        description: String,
        offset: Int,
        colorCount: Int,
        category: String,
    ) = PaletteRegion(id, name, description, offset, colorCount, category)

    val SAMUS_POWER = region("samus_power", "Power Suit", "Default suit (all animation frames)", 0x0D9400, 528, "samus")
    val SAMUS_VARIA = region("samus_varia", "Varia Suit", "Varia suit (all animation frames)", 0x0D9820, 528, "samus")
    val SAMUS_GRAVITY = region("samus_gravity", "Gravity Suit", "Gravity suit (all animation frames)", 0x0D9C40, 528, "samus")
    val SAMUS_EXTRA = region("samus_extra", "Samus Extra", "Ship, crystal flash, hyper beam palettes", 0x0DA060, 432, "samus")
    val BEAM_STANDARD = region("beam_standard", "Standard Beam", "Power beam projectile colors", 0x0D7C00, 16, "beams")

    val BOSS_REGIONS: List<PaletteRegion> = listOf(
        region("boss_spore_spawn", "Spore Spawn", "Spore Spawn palette (all rows)", 0x12E359, 176, "bosses"),
        region("boss_ridley", "Ridley", "Ridley sprite palettes (all rows)", 0x13614F, 160, "bosses"),
        region("boss_kraid", "Kraid", "Kraid sprite palettes (all rows)", 0x138687, 48, "bosses"),
        region("boss_kraid_bg", "Kraid (background)", "Kraid room background palette", 0x13AAA6, 16, "bosses"),
        region("boss_phantoon", "Phantoon", "Phantoon palettes", 0x13CA01, 32, "bosses"),
        region("boss_draygon", "Draygon", "Draygon palettes (all rows)", 0x12A1F7, 96, "bosses"),
        region("boss_crocomire", "Crocomire", "Crocomire palettes (all rows)", 0x12387D, 96, "bosses"),
        region("boss_mother_brain", "Mother Brain", "Mother Brain palettes (all rows)", 0x149472, 112, "bosses"),
        region("boss_botwoon", "Botwoon", "Botwoon palette", 0x199319, 16, "bosses"),
        region("boss_botwoon_health", "Botwoon (health)", "Botwoon health-based palette transitions", 0x19971B, 128, "bosses"),
        region("boss_torizo", "Torizo", "Torizo palettes (all rows)", 0x150687, 160, "bosses"),
        region("boss_golden_torizo_1", "Golden Torizo (palette 1)", "GT health-based palette set 1", 0x020032, 128, "bosses"),
        region("boss_golden_torizo_2", "Golden Torizo (palette 2)", "GT health-based palette set 2", 0x020132, 128, "bosses"),
        region("boss_big_metroid", "Big Metroid", "Baby Metroid (end-game) palette", 0x14F8E6, 16, "bosses"),
        region("boss_mini_kraid", "Mini Kraid", "Mini Kraid palette", 0x13198C, 16, "bosses"),
        region("boss_zebetites", "Zebetites", "Zebetites palette", 0x14D6BC, 16, "bosses"),
    )

    val ENEMY_REGIONS: List<PaletteRegion> = listOf(
        region("enemy_metroid", "Metroid", "Metroid palette", 0x11E9AF, 16, "enemies"),
        region("enemy_metroid_modified", "Metroid (modified)", "Metroid variant palette", 0x11A725, 16, "enemies"),
        region("enemy_space_pirate", "Space Pirate", "Space Pirate palette (Crateria)", 0x190687, 16, "enemies"),
        region("enemy_space_pirate_brinstar", "Space Pirate (Brinstar)", "Space Pirate Brinstar palette", 0x1906A7, 16, "enemies"),
        region("enemy_space_pirate_norfair", "Space Pirate (Norfair)", "Space Pirate Norfair palette", 0x190727, 16, "enemies"),
        region("enemy_space_pirate_maridia", "Space Pirate (Maridia)", "Space Pirate Maridia palette", 0x1906C7, 16, "enemies"),
        region("enemy_space_pirate_tourian", "Space Pirate (Tourian)", "Space Pirate Tourian palette", 0x190707, 16, "enemies"),
        region("enemy_tourian_escape_pirate", "Tourian Escape Pirate", "Tourian escape sequence pirate palette", 0x19E525, 16, "enemies"),
        region("enemy_kihunter_green", "Kihunter (green)", "Kihunter green palette", 0x14699A, 16, "enemies"),
        region("enemy_kihunter_red", "Kihunter (red)", "Kihunter red palette", 0x1469BA, 16, "enemies"),
        region("enemy_kihunter_gold", "Kihunter (gold)", "Kihunter gold palette", 0x1469DA, 16, "enemies"),
        region("enemy_zoomer", "Zoomer", "Zoomer palette", 0x11E5B0, 16, "enemies"),
        region("enemy_geemer", "Geemer (horizontal)", "Geemer palette", 0x11DFA2, 16, "enemies"),
        region("enemy_ripper", "Ripper", "Ripper palette", 0x116457, 16, "enemies"),
        region("enemy_ripper_ii", "Ripper II", "Ripper II palette", 0x11617B, 16, "enemies"),
        region("enemy_sidehopper", "Sidehopper", "Sidehopper palette", 0x11AA48, 16, "enemies"),
        region("enemy_sidehopper_big", "Sidehopper (big)", "Big Sidehopper palette", 0x11B085, 16, "enemies"),
        region("enemy_dessgeega", "Dessgeega", "Dessgeega palette", 0x11AF85, 16, "enemies"),
        region("enemy_waver", "Waver", "Waver palette", 0x118687, 16, "enemies"),
        region("enemy_reo", "Reo", "Reo palette", 0x113A7B, 16, "enemies"),
        region("enemy_skree", "Skree", "Skree palette", 0x119B9B, 16, "enemies"),
        region("enemy_skree_norfair", "Skree (Norfair)", "Norfair Skree palette", 0x11C63E, 16, "enemies"),
        region("enemy_viola", "Viola", "Viola palette", 0x11B5B3, 16, "enemies"),
        region("enemy_multiviola", "Multiviola", "Multiviola palette", 0x1132BC, 16, "enemies"),
        region("enemy_zeela", "Zeela", "Zeela palette", 0x11E23C, 16, "enemies"),
        region("enemy_sova", "Sova", "Sova palette", 0x11E57C, 16, "enemies"),
        region("enemy_sciser", "Sciser", "Sciser (Maridia crab) palette", 0x11965B, 16, "enemies"),
        region("enemy_skultera", "Skultera", "Skultera palette", 0x11900A, 16, "enemies"),
        region("enemy_dragon", "Dragon", "Dragon palette (Norfair lava dragon)", 0x11657B, 16, "enemies"),
        region("enemy_geruta", "Geruta", "Geruta palette", 0x1140D1, 16, "enemies"),
        region("enemy_holtz", "Holtz", "Holtz palette", 0x1145FA, 16, "enemies"),
        region("enemy_squeept", "Squeept", "Squeept palette", 0x113E1C, 16, "enemies"),
        region("enemy_cacatac", "Cacatac", "Cacatac palette", 0x111E6A, 16, "enemies"),
        region("enemy_owtch", "Owtch", "Owtch (spike enemy) palette", 0x11238B, 16, "enemies"),
        region("enemy_beetom", "Beetom", "Beetom palette", 0x14365E, 16, "enemies"),
        region("enemy_atomic", "Atomic", "Atomic palette", 0x146230, 16, "enemies"),
        region("enemy_zoa", "Zoa", "Zoa palette", 0x11B3A1, 16, "enemies"),
        region("enemy_oum", "Oum", "Oum palette", 0x11980B, 16, "enemies"),
        region("enemy_alcoon", "Alcoon", "Alcoon palette", 0x145BC7, 16, "enemies"),
        region("enemy_puu", "Puu", "Puu (Maridia ghost) palette", 0x144143, 16, "enemies"),
        region("enemy_evir", "Evir", "Evir palette", 0x140687, 16, "enemies"),
        region("enemy_yapping_maw", "Yapping Maw", "Yapping Maw palette", 0x141F4F, 16, "enemies"),
        region("enemy_namihe", "Namihe", "Namihe palette", 0x14159D, 16, "enemies"),
        region("enemy_fune", "Fune", "Fune palette", 0x141379, 16, "enemies"),
        region("enemy_lavaman", "Lavaman", "Lavaman palette", 0x142C1C, 16, "enemies"),
        region("enemy_puromi", "Puromi", "Puromi palette", 0x131470, 16, "enemies"),
        region("enemy_hibashi", "Hibashi", "Hibashi (flame) palette", 0x130CFB, 16, "enemies"),
        region("enemy_fireflea", "Fireflea", "Fireflea palette", 0x118C0F, 16, "enemies"),
        region("enemy_zeb", "Zeb", "Zeb (pipe bug) palette", 0x19878B, 16, "enemies"),
        region("enemy_zebbo", "Zebbo", "Zebbo palette", 0x1989FD, 16, "enemies"),
        region("enemy_gamet", "Gamet", "Gamet palette", 0x198AC1, 16, "enemies"),
        region("enemy_geega", "Geega", "Geega palette", 0x198EDC, 16, "enemies"),
        region("enemy_koma", "Koma", "Koma palette", 0x1467AC, 16, "enemies"),
        region("enemy_shaktool", "Shaktool", "Shaktool palette", 0x155911, 16, "enemies"),
        region("enemy_work_robot", "Work Robot", "Work Robot palette", 0x1446B3, 16, "enemies"),
        region("enemy_wrecked_ship_robot", "Wrecked Ship Robot", "Wrecked Ship Robot palette", 0x14F8A6, 16, "enemies"),
        region("enemy_etecoon", "Etecoon", "Etecoon palette", 0x13E7FE, 16, "enemies"),
        region("enemy_dachora", "Dachora", "Dachora palette", 0x13F225, 16, "enemies"),
        region("enemy_samus_ship", "Samus Ship (sprite)", "Samus ship enemy sprite palette", 0x11259E, 16, "enemies"),
        region("enemy_thin_hopping_blob", "Thin Hopping Blob", "Thin Hopping Blob palette", 0x112FF3, 16, "enemies"),
        region("enemy_maridia_snail", "Maridia Large Snail", "Maridia Large Snail palette", 0x1188F0, 16, "enemies"),
        region("enemy_hachi_1", "Hachi (bee 1)", "Kihunter bee variant 1 palette", 0x11B0A5, 16, "enemies"),
        region("enemy_hachi_2", "Hachi (bee 2)", "Kihunter bee variant 2 palette", 0x11B217, 16, "enemies"),
        region("enemy_nuclear_waffle", "Nuclear Waffle", "Nuclear Waffle palette", 0x146587, 16, "enemies"),
        region("enemy_ws_orbs", "Wrecked Ship Orbs", "Wrecked Ship Orbs palette", 0x145821, 16, "enemies"),
        region("enemy_lava_seahorse", "Lava Seahorse", "Lava Seahorse palette", 0x1562BD, 16, "enemies"),
        region("enemy_walking_lava_seahorse", "Walking Lava Seahorse", "Walking Lava Seahorse palette", 0x15631D, 16, "enemies"),
        region("enemy_mini_crocomire", "Mini Crocomire", "Mini Crocomire palette", 0x008202, 16, "enemies"),
        region("enemy_space_pirate_gold", "Space Pirate (gold)", "Space Pirate gold / Mini Maridia Turtle palette", 0x1906E7, 16, "enemies"),
        region("enemy_mother_bomb", "Mother Bomb", "Mother Bomb palette", 0x14EBAC, 16, "enemies"),
        region("enemy_escape_etecoon", "Escape Etecoon", "Escape sequence Etecoon palette", 0x11C8A6, 16, "enemies"),
    )

    val REGIONS: List<PaletteRegion> = listOf(
        SAMUS_POWER, SAMUS_VARIA, SAMUS_GRAVITY, SAMUS_EXTRA, BEAM_STANDARD
    ) + BOSS_REGIONS + ENEMY_REGIONS

    val SAMUS_REGIONS: List<PaletteRegion> = REGIONS.filter { it.category == "samus" }
    val BEAM_REGIONS: List<PaletteRegion> = REGIONS.filter { it.category == "beams" }
    val BOSSES_REGIONS: List<PaletteRegion> = REGIONS.filter { it.category == "bosses" }
    val ENEMIES_REGIONS: List<PaletteRegion> = REGIONS.filter { it.category == "enemies" }

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
