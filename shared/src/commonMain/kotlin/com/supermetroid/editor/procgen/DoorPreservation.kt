package com.supermetroid.editor.procgen

internal data class DoorPocket(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

internal data class DoorSetup(
    val preserved: BooleanArray,
    val forceAir: BooleanArray,
    val pockets: List<DoorPocket>,
)

internal object DoorPreservation {
    fun setup(originalWords: IntArray, width: Int, height: Int): DoorSetup {
        val n = width * height
        val preserved = BooleanArray(n)
        val forceAir = BooleanArray(n)
        fun idx(x: Int, y: Int) = y * width + x
        fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

        val doorCells = ArrayList<Int>()
        for (i in 0 until n) if (((originalWords[i] shr 12) and 0xF) == 0x9) doorCells.add(i)
        for (i in doorCells) {
            val dx = i % width
            val dy = i / width
            for (yy in dy - 1..dy + 1) for (xx in dx - 1..dx + 1) {
                if (inBounds(xx, yy)) preserved[idx(xx, yy)] = true
            }
        }
        val pockets = buildDoorPockets(doorCells, width, height)
        for (p in pockets) {
            for (y in p.y0..p.y1) for (x in p.x0..p.x1) {
                if (inBounds(x, y) && !preserved[idx(x, y)]) forceAir[idx(x, y)] = true
            }
        }
        return DoorSetup(preserved, forceAir, pockets)
    }

    /** Group door cells into contiguous doors and carve an inward pocket per door. */
    private fun buildDoorPockets(doorCells: List<Int>, width: Int, height: Int): List<DoorPocket> {
        if (doorCells.isEmpty()) return emptyList()
        val remaining = doorCells.toMutableSet()
        val pockets = ArrayList<DoorPocket>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            val group = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            remaining.remove(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group.add(c)
                val cx = c % width
                val cy = c / width
                for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                    val ni = (cy + dy) * width + (cx + dx)
                    if (cx + dx in 0 until width && cy + dy in 0 until height && remaining.remove(ni)) {
                        queue.add(ni)
                    }
                }
            }
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            for (c in group) {
                minX = minOf(minX, c % width)
                maxX = maxOf(maxX, c % width)
                minY = minOf(minY, c / width)
                maxY = maxOf(maxY, c / width)
            }
            val depth = 6
            val pocket = when {
                minX <= width - maxX && maxX - minX < maxY - minY + 2 && minX < width / 2 ->
                    DoorPocket(maxX + 1, minY, maxX + depth, maxY)
                maxX - minX < maxY - minY + 2 && minX >= width / 2 ->
                    DoorPocket(minX - depth, minY, minX - 1, maxY)
                minY < height / 2 ->
                    DoorPocket(minX, maxY + 1, maxX, maxY + depth - 1)
                else ->
                    DoorPocket(minX, minY - depth + 1, maxX, minY - 1)
            }
            pockets.add(pocket)
        }
        return pockets
    }
}
