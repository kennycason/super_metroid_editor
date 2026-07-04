package com.supermetroid.editor.procgen

/**
 * 8-bit neighbor-solidity mask shared by [TilesetProfile] learning and
 * [TileDresser] output. Bit 0=N, 1=S, 2=W, 3=E, 4=NW, 5=NE, 6=SW, 7=SE;
 * 1 means the neighbor is solid (or out of bounds).
 */
internal object NeighborMask {
    fun fromPredicate(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        isSolid: (Int, Int) -> Boolean,
    ): Int {
        fun solid(dx: Int, dy: Int): Boolean {
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) return true
            return isSolid(nx, ny)
        }
        var mask = 0
        if (solid(0, -1)) mask = mask or 0x01
        if (solid(0, 1)) mask = mask or 0x02
        if (solid(-1, 0)) mask = mask or 0x04
        if (solid(1, 0)) mask = mask or 0x08
        if (solid(-1, -1)) mask = mask or 0x10
        if (solid(1, -1)) mask = mask or 0x20
        if (solid(-1, 1)) mask = mask or 0x40
        if (solid(1, 1)) mask = mask or 0x80
        return mask
    }

    fun fromStructureGrid(cells: IntArray, width: Int, height: Int, x: Int, y: Int): Int =
        fromPredicate(width, height, x, y) { nx, ny ->
            cells[ny * width + nx] != BiomeCell.AIR
        }
}
