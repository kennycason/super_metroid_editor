package com.supermetroid.editor.procgen

internal data class DoorPocket(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

internal data class DoorSetup(
    val preserved: BooleanArray,
    val forceAir: BooleanArray,
    val pockets: List<DoorPocket>,
)

private data class DoorGroup(
    val cells: List<Int>,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    val vertical: Boolean get() = maxY - minY > maxX - minX
}

internal object DoorPreservation {
    fun setup(originalWords: IntArray, originalBts: IntArray, width: Int, height: Int): DoorSetup {
        val n = width * height
        val preserved = BooleanArray(n)
        val forceAir = BooleanArray(n)
        fun idx(x: Int, y: Int) = y * width + x
        fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

        val doorCells = ArrayList<Int>()
        for (i in 0 until n) if (((originalWords[i] shr 12) and 0xF) == 0x9) doorCells.add(i)
        val groups = buildDoorGroups(doorCells, width, height)
        for (group in groups) {
            markDoorPreservation(group, originalWords, originalBts, width, height, preserved)
        }
        val pockets = buildDoorPockets(groups, width, height)
        for (p in pockets) {
            for (y in p.y0..p.y1) for (x in p.x0..p.x1) {
                if (inBounds(x, y) && !preserved[idx(x, y)]) forceAir[idx(x, y)] = true
            }
        }
        return DoorSetup(preserved, forceAir, pockets)
    }

    private fun markDoorPreservation(
        group: DoorGroup,
        originalWords: IntArray,
        originalBts: IntArray,
        width: Int,
        height: Int,
        preserved: BooleanArray,
    ) {
        fun idx(x: Int, y: Int) = y * width + x
        fun mark(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) preserved[idx(x, y)] = true
        }
        fun markFixture(x: Int, y: Int) {
            if (x !in 0 until width || y !in 0 until height) return
            val i = idx(x, y)
            val type = (originalWords[i] shr 12) and 0xF
            if (isDoorFixtureType(type) || originalBts.getOrElse(i) { 0 } != 0) preserved[i] = true
        }
        fun markRect(x0: Int, y0: Int, x1: Int, y1: Int, keep: (Int, Int) -> Boolean) {
            for (y in y0..y1) for (x in x0..x1) {
                if (keep(x, y)) markFixture(x, y)
            }
        }

        // Door fixtures are more than the type-9 door cells: vanilla rooms
        // often use adjacent cap/pipe tiles with BTS or flips that must remain
        // verbatim for the door to look and behave correctly.
        for (cell in group.cells) mark(cell % width, cell / width)
        if (group.vertical) {
            markRect(
                group.minX - DOOR_FIXTURE_DEPTH,
                group.minY - DOOR_FIXTURE_MARGIN,
                group.maxX + DOOR_FIXTURE_DEPTH,
                group.maxY + DOOR_FIXTURE_MARGIN,
            ) { _, y -> y < group.minY || y > group.maxY }
        } else {
            markRect(
                group.minX - DOOR_FIXTURE_MARGIN,
                group.minY - DOOR_FIXTURE_DEPTH,
                group.maxX + DOOR_FIXTURE_MARGIN,
                group.maxY + DOOR_FIXTURE_DEPTH,
            ) { x, _ -> x < group.minX || x > group.maxX }
        }
    }

    private fun buildDoorGroups(doorCells: List<Int>, width: Int, height: Int): List<DoorGroup> {
        val remaining = doorCells.toMutableSet()
        val groups = ArrayList<DoorGroup>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            val cells = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            remaining.remove(start)
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                cells.add(c)
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
            for (c in cells) {
                minX = minOf(minX, c % width)
                maxX = maxOf(maxX, c % width)
                minY = minOf(minY, c / width)
                maxY = maxOf(maxY, c / width)
            }
            groups.add(DoorGroup(cells, minX, minY, maxX, maxY))
        }
        return groups
    }

    /** Carve candidate pockets from each door group toward the room maze. */
    private fun buildDoorPockets(groups: List<DoorGroup>, width: Int, height: Int): List<DoorPocket> {
        if (groups.isEmpty()) return emptyList()
        val pockets = ArrayList<DoorPocket>()
        for (group in groups) {
            val depth = DOOR_CLEARANCE
            if (group.vertical) {
                val centerY = (group.minY + group.maxY) / 2
                val y0 = minOf(group.minY, centerY - 1)
                val y1 = maxOf(group.maxY, centerY + 1)
                val nearLeftEdge = group.minX <= depth
                val nearRightEdge = group.maxX >= width - depth - 1
                if (!nearRightEdge) pockets.add(DoorPocket(group.maxX + 1, y0, group.maxX + depth, y1))
                if (!nearLeftEdge) pockets.add(DoorPocket(group.minX - depth, y0, group.minX - 1, y1))
            } else {
                val centerX = (group.minX + group.maxX) / 2
                val x0 = minOf(group.minX, centerX - 1) - HORIZONTAL_DOOR_CLEARANCE_MARGIN
                val x1 = maxOf(group.maxX, centerX + 1) + HORIZONTAL_DOOR_CLEARANCE_MARGIN
                val nearTopEdge = group.minY <= depth
                val nearBottomEdge = group.maxY >= height - depth - 1
                if (!nearBottomEdge) pockets.add(DoorPocket(x0, group.maxY + 1, x1, group.maxY + depth))
                if (!nearTopEdge) pockets.add(DoorPocket(x0, group.minY - depth, x1, group.minY - 1))
            }
        }
        return pockets
    }

    private const val DOOR_FIXTURE_DEPTH = 8
    private const val DOOR_FIXTURE_MARGIN = 3
    private const val DOOR_CLEARANCE = 5
    private const val HORIZONTAL_DOOR_CLEARANCE_MARGIN = 1

    private fun isDoorFixtureType(type: Int): Boolean =
        type != 0x0 && type != 0x2 && type != 0x4 && type != 0x7
}
