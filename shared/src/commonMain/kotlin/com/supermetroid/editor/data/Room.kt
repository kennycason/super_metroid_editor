package com.supermetroid.editor.data

/**
 * Represents a Super Metroid room parsed from ROM.
 * 
 * Room header format (variable length):
 *   Bytes 0-8:   Fixed header (index, area, map pos, dimensions, scrollers, CRE)
 *   Bytes 9-10:  Door out pointer
 *   Bytes 11+:   Room state entries → state data (26 bytes)
 * 
 * State data contains the level data pointer (3 bytes), tileset, etc.
 */
data class Room(
    val roomId: Int,           // Room ID (e.g., 0x91F8) — offset within bank $8F
    val name: String,
    val handle: String,
    
    // Fixed room header fields
    val index: Int,            // Byte 0: Room index
    val area: Int,             // Byte 1: Area (0=Crateria..6=Ceres)
    val mapX: Int,             // Byte 2: X position on minimap
    val mapY: Int,             // Byte 3: Y position on minimap
    val width: Int,            // Byte 4: Room width in screens
    val height: Int,           // Byte 5: Room height in screens
    val upScroller: Int,       // Byte 6: Up scroller
    val downScroller: Int,     // Byte 7: Down scroller
    val creBitflag: Int,       // Byte 8: CRE/special graphics bitflag
    val doorOut: Int,          // Bytes 9-10: Door out pointer
    
    // From the room state selected for the initial editor preview (26 bytes)
    val levelDataPtr: Int = 0,   // 3-byte SNES address to compressed level data
    val tileset: Int = 0,        // Tileset index
    val musicData: Int = 0,      // Music data byte
    val musicTrack: Int = 0,     // Music track byte
    val fxPtr: Int = 0,          // FX pointer
    val enemySetPtr: Int = 0,    // Enemy population pointer
    val enemyGfxPtr: Int = 0,    // Enemy graphics pointer
    val bgScrolling: Int = 0,    // Background scrolling
    val roomScrollsPtr: Int = 0, // Scroll data pointer
    val xraySpecialCasingPtr: Int = 0, // Special X-Ray block table pointer
    val mainAsmPtr: Int = 0,     // Main ASM routine pointer
    val plmSetPtr: Int = 0,      // PLM (Post Load Modification) set pointer
    val bgDataPtr: Int = 0,      // Background tilemap pointer
    val setupAsmPtr: Int = 0,    // Setup ASM routine pointer
) {
    val areaName: String
        get() = when (area) {
            0 -> "Crateria"
            1 -> "Brinstar"
            2 -> "Norfair"
            3 -> "Wrecked Ship"
            4 -> "Maridia"
            5 -> "Tourian"
            6 -> "Ceres"
            7 -> "Debug/Unused"
            else -> "Unknown"
        }
    
    val roomSize: Pair<Int, Int>
        get() = Pair(width, height)
}
