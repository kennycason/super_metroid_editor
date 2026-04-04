package com.supermetroid.editor.rom

/**
 * Pause screen minimap tile data for one area.
 *
 * Each area has a 64×32 grid of map tiles. Each tile is a 16-bit word:
 *   Bits 0-9:   Tile index (0-1023, though only 0-255 are typical)
 *   Bits 10-11: Palette (0=black/unexplored, 1=blue-pink, 2=white, 3=red)
 *   Bit 14:     Horizontal flip
 *   Bit 15:     Vertical flip
 *
 * The grid is stored as two 32×32 halves (left half, right half).
 * ROM addresses per area (PC offsets via snesToPc):
 *   Crateria=$1A9000, Brinstar=$1A8000, Norfair=$1AA000,
 *   Wrecked Ship=$1AB000, Maridia=$1AC000, Tourian=$1AD000, Ceres=$1AE000
 */
data class MinimapData(
    val area: Int,
    val tiles: IntArray,   // 64*32 = 2048 tile words (row-major, x + y*64)
) {
    /** Get tile word at (x, y). Returns 0 for out-of-bounds. */
    fun getTile(x: Int, y: Int): Int {
        if (x !in 0 until MAP_WIDTH || y !in 0 until MAP_HEIGHT) return 0
        return tiles[y * MAP_WIDTH + x]
    }

    /** Return a copy with one tile modified. */
    fun withTile(x: Int, y: Int, value: Int): MinimapData {
        if (x !in 0 until MAP_WIDTH || y !in 0 until MAP_HEIGHT) return this
        val newTiles = tiles.copyOf()
        newTiles[y * MAP_WIDTH + x] = value
        return copy(tiles = newTiles)
    }

    /** Return a copy with a rectangular region filled. */
    fun withFill(x0: Int, y0: Int, x1: Int, y1: Int, value: Int): MinimapData {
        val newTiles = tiles.copyOf()
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (x in 0 until MAP_WIDTH && y in 0 until MAP_HEIGHT) {
                    newTiles[y * MAP_WIDTH + x] = value
                }
            }
        }
        return copy(tiles = newTiles)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MinimapData) return false
        return area == other.area && tiles.contentEquals(other.tiles)
    }

    override fun hashCode(): Int = 31 * area + tiles.contentHashCode()

    companion object {
        const val MAP_WIDTH = 64
        const val MAP_HEIGHT = 32
        const val TILE_COUNT = MAP_WIDTH * MAP_HEIGHT  // 2048

        // SNES addresses for each area's map tile data (bank $B5)
        // Crateria and Brinstar are swapped in the ROM.
        val AREA_MAP_ADDRESSES = intArrayOf(
            0xB5_9000,  // 0 = Crateria  (PC $1A9000)
            0xB5_8000,  // 1 = Brinstar  (PC $1A8000)
            0xB5_A000,  // 2 = Norfair   (PC $1AA000)
            0xB5_B000,  // 3 = Wrecked Ship (PC $1AB000)
            0xB5_C000,  // 4 = Maridia   (PC $1AC000)
            0xB5_D000,  // 5 = Tourian   (PC $1AD000)
            0xB5_E000,  // 6 = Ceres     (PC $1AE000)
        )

        // Map station reveal data SNES addresses (bank $82)
        // SMILE uses PC 0x11727 + area*0x100; PC→SNES for bank $82
        val MAP_STATION_ADDRESSES = intArrayOf(
            0x82_9727,  // 0 = Crateria  (PC $11727)
            0x82_9827,  // 1 = Brinstar  (PC $11827)
            0x82_9927,  // 2 = Norfair   (PC $11927)
            0x82_9A27,  // 3 = Wrecked Ship (PC $11A27)
            0x82_9B27,  // 4 = Maridia   (PC $11B27)
            0x82_9C27,  // 5 = Tourian   (PC $11C27)
            0x82_9D27,  // 6 = Ceres     (PC $11D27)
        )

        const val MAP_STATION_DATA_SIZE = 0x100  // 256 bytes per area

        const val NUM_AREAS = 7

        val AREA_NAMES = arrayOf(
            "Crateria", "Brinstar", "Norfair", "Wrecked Ship",
            "Maridia", "Tourian", "Ceres"
        )

        // Tile word bit masks
        const val TILE_INDEX_MASK = 0x03FF
        const val PALETTE_MASK = 0x0C00
        const val PALETTE_SHIFT = 10
        const val HFLIP_BIT = 0x4000
        const val VFLIP_BIT = 0x8000

        fun tileIndex(word: Int): Int = word and TILE_INDEX_MASK
        fun tilePalette(word: Int): Int = (word and PALETTE_MASK) shr PALETTE_SHIFT
        fun tileHFlip(word: Int): Boolean = (word and HFLIP_BIT) != 0
        fun tileVFlip(word: Int): Boolean = (word and VFLIP_BIT) != 0

        /** Build a tile word from components. */
        fun makeTileWord(index: Int, palette: Int = 0, hFlip: Boolean = false, vFlip: Boolean = false): Int {
            return (index and TILE_INDEX_MASK) or
                ((palette and 0x3) shl PALETTE_SHIFT) or
                (if (hFlip) HFLIP_BIT else 0) or
                (if (vFlip) VFLIP_BIT else 0)
        }

        /** Create an empty map for an area. */
        fun empty(area: Int): MinimapData = MinimapData(area, IntArray(TILE_COUNT))
    }
}

/**
 * Map station reveal data — which tiles are revealed when the player
 * visits a map station in a given area.
 */
data class MapStationData(
    val area: Int,
    val revealed: BooleanArray,  // 2048 flags (one per map tile)
) {
    fun isRevealed(x: Int, y: Int): Boolean {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return false
        return revealed[y * MinimapData.MAP_WIDTH + x]
    }

    fun withToggle(x: Int, y: Int): MapStationData {
        if (x !in 0 until MinimapData.MAP_WIDTH || y !in 0 until MinimapData.MAP_HEIGHT) return this
        val idx = y * MinimapData.MAP_WIDTH + x
        val newRevealed = revealed.copyOf()
        newRevealed[idx] = !newRevealed[idx]
        return copy(revealed = newRevealed)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MapStationData) return false
        return area == other.area && revealed.contentEquals(other.revealed)
    }

    override fun hashCode(): Int = 31 * area + revealed.contentHashCode()

    companion object {
        fun empty(area: Int): MapStationData = MapStationData(area, BooleanArray(MinimapData.TILE_COUNT))
    }
}

/**
 * Well-known minimap tile indices and their meanings.
 * These are the standard tile graphics used in the pause menu.
 */
object MinimapTiles {
    // Empty/blank
    const val EMPTY = 0x1F

    // Passable room tiles (no walls)
    const val ROOM_OPEN = 0x1B

    // Wall configuration tiles (single walls)
    const val WALL_TOP = 0x21
    const val WALL_BOTTOM = 0x23
    const val WALL_LEFT = 0x25
    const val WALL_RIGHT = 0x27

    // Corner tiles (two walls)
    const val CORNER_TL = 0x20
    const val CORNER_TR = 0x22
    const val CORNER_BL = 0x24
    const val CORNER_BR = 0x26

    // Three-wall tiles
    const val WALLS_TLB = 0x28
    const val WALLS_TRB = 0x29
    const val WALLS_TLR = 0x2A
    const val WALLS_BLR = 0x2B

    // Four walls (enclosed cell)
    const val WALLS_ALL = 0x2C

    // Door tiles (arrows indicating passage direction)
    const val DOOR_RIGHT = 0x04
    const val DOOR_LEFT = 0x05
    const val DOOR_DOWN = 0x02
    const val DOOR_UP = 0x03

    // Item dot tiles (room tile + centered dot)
    const val ITEM_OPEN = 0x76
    const val ITEM_WALL_TOP = 0x77
    const val ITEM_WALL_BOTTOM = 0x78
    const val ITEM_WALL_LEFT = 0x79
    const val ITEM_WALL_RIGHT = 0x7A
    const val ITEM_CORNER_TL = 0x7B
    const val ITEM_CORNER_TR = 0x7C
    const val ITEM_CORNER_BL = 0x7D
    const val ITEM_CORNER_BR = 0x7E
    const val ITEM_WALLS_ALL = 0x80

    // Map station tile
    const val MAP_STATION = 0x46

    // Save station tile
    const val SAVE_STATION = 0x44

    // Energy recharge
    const val ENERGY_STATION = 0x48

    // Missile recharge
    const val MISSILE_STATION = 0x4A

    // Elevator
    const val ELEVATOR = 0xCE

    /** All tiles in a logical order for the palette/picker. */
    val PALETTE_TILES = intArrayOf(
        // Row 1: Empty and basic room
        EMPTY, ROOM_OPEN,
        // Row 2: Walls
        WALL_TOP, WALL_BOTTOM, WALL_LEFT, WALL_RIGHT,
        // Row 3: Corners
        CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR,
        // Row 4: Three walls
        WALLS_TLB, WALLS_TRB, WALLS_TLR, WALLS_BLR, WALLS_ALL,
        // Row 5: Doors
        DOOR_UP, DOOR_DOWN, DOOR_LEFT, DOOR_RIGHT,
        // Row 6: Items
        ITEM_OPEN, ITEM_WALL_TOP, ITEM_WALL_BOTTOM,
        ITEM_WALL_LEFT, ITEM_WALL_RIGHT,
        ITEM_CORNER_TL, ITEM_CORNER_TR, ITEM_CORNER_BL, ITEM_CORNER_BR,
        ITEM_WALLS_ALL,
        // Row 7: Stations
        SAVE_STATION, MAP_STATION, ENERGY_STATION, MISSILE_STATION, ELEVATOR,
    )

    /** Human-readable names for well-known tiles. */
    val TILE_NAMES = mapOf(
        EMPTY to "Empty",
        ROOM_OPEN to "Room (open)",
        WALL_TOP to "Wall: top",
        WALL_BOTTOM to "Wall: bottom",
        WALL_LEFT to "Wall: left",
        WALL_RIGHT to "Wall: right",
        CORNER_TL to "Corner: top-left",
        CORNER_TR to "Corner: top-right",
        CORNER_BL to "Corner: bottom-left",
        CORNER_BR to "Corner: bottom-right",
        WALLS_TLB to "Walls: T/L/B",
        WALLS_TRB to "Walls: T/R/B",
        WALLS_TLR to "Walls: T/L/R",
        WALLS_BLR to "Walls: B/L/R",
        WALLS_ALL to "Walls: all",
        DOOR_UP to "Door: up",
        DOOR_DOWN to "Door: down",
        DOOR_LEFT to "Door: left",
        DOOR_RIGHT to "Door: right",
        ITEM_OPEN to "Item (open)",
        ITEM_WALL_TOP to "Item + wall top",
        ITEM_WALL_BOTTOM to "Item + wall bottom",
        ITEM_WALL_LEFT to "Item + wall left",
        ITEM_WALL_RIGHT to "Item + wall right",
        ITEM_CORNER_TL to "Item + corner TL",
        ITEM_CORNER_TR to "Item + corner TR",
        ITEM_CORNER_BL to "Item + corner BL",
        ITEM_CORNER_BR to "Item + corner BR",
        ITEM_WALLS_ALL to "Item + all walls",
        SAVE_STATION to "Save station",
        MAP_STATION to "Map station",
        ENERGY_STATION to "Energy station",
        MISSILE_STATION to "Missile station",
        ELEVATOR to "Elevator",
    )
}
