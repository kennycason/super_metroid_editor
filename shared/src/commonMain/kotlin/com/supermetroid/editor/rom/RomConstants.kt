package com.supermetroid.editor.rom

/**
 * Shared constants for Super Metroid ROM parsing.
 *
 * SNES LoROM bank addresses, tile format sizes, and other values
 * used across multiple ROM parsing classes.
 */
object RomConstants {

    // ─── SNES Bank Base Addresses ────────────────────────────────────
    // Used with `or` to form full SNES addresses from 16-bit pointers.
    // e.g., snesToPc(BANK_ROOM_DATA or roomScrollsPtr)

    /** Bank $8F — rooms, PLMs, scrolls, doors, state data */
    const val BANK_ROOM_DATA = 0x8F0000

    /** Bank $83 — FX entries, door cap data */
    const val BANK_FX = 0x830000

    /** Bank $A0 — enemy/boss species headers (AI bank) */
    const val BANK_ENEMY_AI = 0xA00000

    /** Bank $A1 — enemy population sets */
    const val BANK_ENEMY_SET = 0xA10000

    /** Bank $B4 — enemy graphics sets */
    const val BANK_ENEMY_GFX = 0xB40000

    // ─── ROM File Layout ─────────────────────────────────────────────

    /** Super Metroid ROM size without SMC header (3 MB) */
    const val ROM_SIZE = 0x300000

    /** ROM size with 512-byte SMC header */
    const val ROM_SIZE_WITH_HEADER = 0x300200

    /** SMC header size in bytes */
    const val SMC_HEADER_SIZE = 0x200

    // ─── Tile Format ─────────────────────────────────────────────────

    /** SNES 4bpp tile: 32 bytes per 8×8 tile */
    const val BYTES_PER_4BPP_TILE = 32

    /** Colors per SNES sub-palette (4bpp = 16 colors) */
    const val COLORS_PER_PALETTE = 16

    /** Empty/transparent tile index used by boss spritemaps */
    const val EMPTY_TILE = 0x338

    // ─── Room State Data ─────────────────────────────────────────────

    /** Size of a room state data block in bytes (bank $8F) */
    const val STATE_DATA_SIZE = 26

    // ─── Rendering ───────────────────────────────────────────────────

    /** Default dark background color (ARGB) used for tile/map rendering */
    const val ROM_BG_COLOR = 0xFF0C0C18.toInt()

    // ─── Layer 3 ────────────────────────────────────────────────────

    /** ROM PC offset of the Layer 3 setup pointer table (indexed by fxType) */
    const val L3_POINTER_TABLE_PC = 0x1ABF0

    /** ROM PC offset of the base 2bpp Layer 3 tile graphics (256 tiles × 16 bytes) */
    const val L3_BASE_GFX_PC = 0xD3200

    /** Layer 3 tilemap entries: 32 columns × 33 rows */
    const val L3_TILEMAP_COLS = 32
    const val L3_TILEMAP_ROWS = 33
    const val L3_TILEMAP_SIZE = L3_TILEMAP_COLS * L3_TILEMAP_ROWS  // 1056

    /** Per-fxType replacement GFX addresses (ROM PC) — first 4 tiles replaced */
    val L3_REPLACEMENT_GFX: Map<Int, Int> = mapOf(
        0x02 to 0x3A564,  // Lava
        0x04 to 0x3A6A4,  // Acid
        0x08 to 0x3A7E4,  // Spores
        0x0A to 0x3A974,  // Rain
    )

    // ─── SPC Audio ───────────────────────────────────────────────────

    /** SPC700 RAM size (64 KB) */
    const val SPC_RAM_SIZE = 0x10000
}
