package com.supermetroid.editor.procgen

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

internal object StructureAlgorithms {
    fun build(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        rng: Random,
        originalSolid: BooleanArray? = null,
        doorPockets: List<DoorPocket> = emptyList(),
    ) {
        when (rules.algorithm) {
            StructureAlgorithm.CAVE -> structureCave(cells, width, height, rules, rng)
            StructureAlgorithm.CHAMBERS -> structureChambers(cells, width, height, rng)
            StructureAlgorithm.VERTICAL -> structureVertical(cells, width, height, rules, rng)
            StructureAlgorithm.OPEN_GALLERY -> structureGallery(cells, width, height, rules, rng)
            StructureAlgorithm.MAZE -> structureMaze(cells, width, height, rules, rng, doorPockets)
            StructureAlgorithm.RECTILINEAR -> structureRectilinear(cells, width, height, rng)
            StructureAlgorithm.SETTLEMENT -> structureSettlement(cells, width, height, rng)
            StructureAlgorithm.REMIX ->
                if (originalSolid != null && originalSolid.size >= cells.size) {
                    structureRemix(cells, width, height, rules, rng, originalSolid)
                } else {
                    structureCave(cells, width, height, rules, rng)
                }
            StructureAlgorithm.WFC -> Unit
        }
    }

    fun smooth(cells: IntArray, width: Int, height: Int, rules: BiomeRules) {
        val next = IntArray(cells.size)
        for (y in 0 until height) for (x in 0 until width) {
            var solid = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= width || ny >= height ||
                    cells[ny * width + nx] == BiomeCell.SOLID
                ) {
                    solid++
                }
            }
            val i = y * width + x
            next[i] = when {
                cells[i] == BiomeCell.SOLID ->
                    if (solid >= rules.surviveLimit) BiomeCell.SOLID else BiomeCell.AIR
                else ->
                    if (solid >= rules.birthLimit) BiomeCell.SOLID else BiomeCell.AIR
            }
        }
        next.copyInto(cells)
    }

    fun refine(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        protectedCells: BooleanArray,
    ) {
        sealPinholes(cells, width, height, protectedCells)
        removeSmallSolidIslands(cells, width, height, rules, protectedCells)
    }

    fun connectDoorPockets(
        cells: IntArray,
        width: Int,
        height: Int,
        doorPockets: List<DoorPocket>,
        tunnelThickness: Int,
        preserved: BooleanArray?,
    ) {
        if (width < 5 || height < 5 || doorPockets.size < 2) return
        val centers = doorPockets.map { pocket ->
            val x0 = pocket.x0.coerceIn(1, width - 2)
            val x1 = pocket.x1.coerceIn(1, width - 2)
            val y0 = pocket.y0.coerceIn(1, height - 2)
            val y1 = pocket.y1.coerceIn(1, height - 2)
            ((x0 + x1) / 2) to ((y0 + y1) / 2)
        }.distinct()
        if (centers.size < 2) return

        val roomCenterX = width / 2
        val roomCenterY = height / 2
        var hub = centers.first()
        var hubScore = Int.MAX_VALUE
        for (center in centers) {
            val score = abs(center.first - roomCenterX) + abs(center.second - roomCenterY)
            if (score < hubScore) {
                hub = center
                hubScore = score
            }
        }

        val ordered = centers.sortedWith(
            compareBy<Pair<Int, Int>> { abs(it.first - hub.first) + abs(it.second - hub.second) }
                .thenBy { it.second }
                .thenBy { it.first }
        )
        for ((x, y) in ordered) {
            if (x == hub.first && y == hub.second) continue
            GridConnectivity.carveTunnel(
                cells,
                width,
                height,
                x,
                y,
                hub.first,
                hub.second,
                thickness = tunnelThickness.coerceAtLeast(1),
                preserved = preserved,
            )
        }
    }

    private fun sealPinholes(
        cells: IntArray,
        width: Int,
        height: Int,
        protectedCells: BooleanArray,
    ) {
        val fill = ArrayList<Int>()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (protectedCells[i] || cells[i] != BiomeCell.AIR) continue
                var solid = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (cells[(y + dy) * width + x + dx] == BiomeCell.SOLID) solid++
                }
                if (solid >= 7) fill.add(i)
            }
        }
        for (i in fill) cells[i] = BiomeCell.SOLID
    }

    private fun removeSmallSolidIslands(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        protectedCells: BooleanArray,
    ) {
        val minSize = when (rules.algorithm) {
            StructureAlgorithm.OPEN_GALLERY -> 18
            StructureAlgorithm.VERTICAL -> 12
            StructureAlgorithm.CHAMBERS -> 10
            StructureAlgorithm.CAVE -> if (rules.style == BiomeStyle.WARREN) 7 else 12
            StructureAlgorithm.MAZE -> 4
            StructureAlgorithm.RECTILINEAR -> 8
            // Building walls are 1 tile thick; keep small constructed pieces.
            StructureAlgorithm.SETTLEMENT -> 4
            StructureAlgorithm.REMIX -> 10
            StructureAlgorithm.WFC -> 10
        }
        val seen = BooleanArray(cells.size)
        val queue = ArrayDeque<Int>()
        val component = ArrayList<Int>()
        for (start in cells.indices) {
            if (seen[start] || cells[start] != BiomeCell.SOLID) continue
            seen[start] = true
            queue.add(start)
            component.clear()
            var touchesBorder = false
            var touchesProtected = false
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                component.add(c)
                val x = c % width
                val y = c / width
                if (x <= BiomeCell.BORDER || y <= BiomeCell.BORDER ||
                    x >= width - BiomeCell.BORDER - 1 || y >= height - BiomeCell.BORDER - 1
                ) {
                    touchesBorder = true
                }
                if (protectedCells[c]) touchesProtected = true
                for ((dx, dy) in CARDINAL_DIRS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val ni = ny * width + nx
                    if (!seen[ni] && cells[ni] == BiomeCell.SOLID) {
                        seen[ni] = true
                        queue.add(ni)
                    }
                }
            }
            if (!touchesBorder && !touchesProtected && component.size < minSize) {
                for (i in component) cells[i] = BiomeCell.AIR
            }
        }
    }

    private fun structureCave(cells: IntArray, width: Int, height: Int, rules: BiomeRules, rng: Random) {
        val coarseH = ceil(height / rules.verticalBias).toInt().coerceAtLeast(1)
        val noise = Array(coarseH) { BooleanArray(width) { rng.nextDouble() < rules.fillChance } }
        for (y in 0 until height) {
            val ny = (y / rules.verticalBias).toInt().coerceAtMost(coarseH - 1)
            for (x in 0 until width) {
                cells[y * width + x] = if (noise[ny][x]) BiomeCell.SOLID else BiomeCell.AIR
            }
        }
        repeat(rules.caIterations) { smooth(cells, width, height, rules) }
    }

    private fun structureChambers(cells: IntArray, width: Int, height: Int, rng: Random) {
        cells.fill(BiomeCell.SOLID)
        val count = (width * height / 350).coerceIn(2, 14)
        data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)
        val rooms = ArrayList<Rect>()
        repeat(count * 3) {
            if (rooms.size >= count) return@repeat
            val w = rng.nextInt(6, minOf(20, width - 2 * BiomeCell.BORDER))
            val h = rng.nextInt(4, minOf(9, height - 2 * BiomeCell.BORDER))
            if (width - w - BiomeCell.BORDER <= BiomeCell.BORDER ||
                height - h - BiomeCell.BORDER <= BiomeCell.BORDER
            ) {
                return@repeat
            }
            val x = rng.nextInt(BiomeCell.BORDER, width - w - BiomeCell.BORDER)
            val y = rng.nextInt(BiomeCell.BORDER, height - h - BiomeCell.BORDER)
            rooms.add(Rect(x, y, w, h))
        }
        for (r in rooms) {
            for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) {
                cells[y * width + x] = BiomeCell.AIR
            }
        }
        for (i in 1 until rooms.size) {
            val a = rooms[i - 1]
            val b = rooms[i]
            GridConnectivity.carveTunnel(
                cells, width, height,
                a.x + a.w / 2, a.y + a.h / 2, b.x + b.w / 2, b.y + b.h / 2,
                thickness = rng.nextInt(2, 4), preserved = null,
            )
        }
    }

    private fun structureVertical(cells: IntArray, width: Int, height: Int, rules: BiomeRules, rng: Random) {
        cells.fill(BiomeCell.SOLID)
        val shaftCount = ((width - 2 * BiomeCell.BORDER) / 40).coerceAtLeast(1)
        val bandW = (width - 2 * BiomeCell.BORDER) / shaftCount
        for (s in 0 until shaftCount) {
            val bandLo = BiomeCell.BORDER + s * bandW
            var cx = (bandLo + bandW / 2 + rng.nextInt(-bandW / 4, bandW / 4 + 1))
                .coerceIn(BiomeCell.BORDER + 3, width - BiomeCell.BORDER - 4)
            var shaftW = rng.nextInt(4, 7)
            for (y in 0 until height) {
                for (x in (cx - shaftW / 2).coerceAtLeast(0) until (cx + (shaftW + 1) / 2).coerceAtMost(width)) {
                    cells[y * width + x] = BiomeCell.AIR
                }
                if (rng.nextDouble() < 0.35) cx += rng.nextInt(-2, 3)
                cx = cx.coerceIn(BiomeCell.BORDER + 3, width - BiomeCell.BORDER - 4)
                if (rng.nextDouble() < 0.15) shaftW = rng.nextInt(4, 7)
                if (rng.nextDouble() < 0.10 && y in BiomeCell.BORDER + 2 until height - BiomeCell.BORDER - 2) {
                    val side = if (rng.nextBoolean()) 1 else -1
                    val len = rng.nextInt(4, 10)
                    var px = cx + side * shaftW / 2
                    for (i in 0 until len) {
                        px += side
                        if (px !in BiomeCell.BORDER until width - BiomeCell.BORDER) break
                        cells[y * width + px] = BiomeCell.AIR
                        if (y + 1 < height - BiomeCell.BORDER) cells[(y + 1) * width + px] = BiomeCell.AIR
                        if (y - 1 >= BiomeCell.BORDER) cells[(y - 1) * width + px] = BiomeCell.AIR
                    }
                }
            }
        }
        if (shaftCount > 1) {
            var gy = rng.nextInt(4, 10)
            while (gy < height - BiomeCell.BORDER - 2) {
                for (x in BiomeCell.BORDER until width - BiomeCell.BORDER) {
                    cells[gy * width + x] = BiomeCell.AIR
                    cells[(gy + 1) * width + x] = BiomeCell.AIR
                }
                gy += rng.nextInt(12, 24)
            }
        }
        smooth(cells, width, height, rules)
    }

    private fun structureGallery(cells: IntArray, width: Int, height: Int, rules: BiomeRules, rng: Random) {
        cells.fill(BiomeCell.AIR)
        var floorH = rng.nextInt(2, 5)
        for (x in 0 until width) {
            if (rng.nextDouble() < 0.3) floorH = (floorH + rng.nextInt(-1, 2)).coerceIn(2, height / 3)
            for (y in height - floorH until height) cells[y * width + x] = BiomeCell.SOLID
            val ceilH = 1 + if (rng.nextDouble() < 0.2) 1 else 0
            for (y in 0 until ceilH) cells[y * width + x] = BiomeCell.SOLID
        }
        val pillars = (width / 44).coerceAtLeast(1)
        repeat(pillars) {
            if (width - BiomeCell.BORDER - 3 <= BiomeCell.BORDER + 2) return@repeat
            val px = rng.nextInt(BiomeCell.BORDER + 2, width - BiomeCell.BORDER - 3)
            val pw = rng.nextInt(3, 6)
            val gapTop = rng.nextInt(height / 4, (height * 3 / 4).coerceAtLeast(height / 4 + 1))
            val gapHeight = rng.nextInt(6, 11).coerceAtMost(height / 3)
            for (y in 0 until height) {
                if (y in gapTop until gapTop + gapHeight) continue
                for (x in px until (px + pw).coerceAtMost(width)) {
                    cells[y * width + x] = BiomeCell.SOLID
                }
            }
        }
        smooth(cells, width, height, rules)
    }

    private fun structureMaze(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        rng: Random,
        doorPockets: List<DoorPocket>,
    ) {
        cells.fill(BiomeCell.SOLID)
        val left = BiomeCell.BORDER + 1
        val top = BiomeCell.BORDER + 1
        val right = width - BiomeCell.BORDER - 2
        val bottom = height - BiomeCell.BORDER - 2
        if (right - left < 4 || bottom - top < 4) {
            carveSmallMazeFallback(cells, width, height, doorPockets)
            return
        }

        val startX = if ((left and 1) == 0) left + 1 else left
        val startY = if ((top and 1) == 0) top + 1 else top
        val nodeXs = generateSequence(startX) { it + 2 }.takeWhile { it <= right }.toList()
        val nodeYs = generateSequence(startY) { it + 2 }.takeWhile { it <= bottom }.toList()
        if (nodeXs.isEmpty() || nodeYs.isEmpty()) {
            carveSmallMazeFallback(cells, width, height, doorPockets)
            return
        }

        fun idx(x: Int, y: Int) = y * width + x
        fun carve(x: Int, y: Int) {
            if (x in 1 until width - 1 && y in 1 until height - 1) cells[idx(x, y)] = BiomeCell.AIR
        }
        fun carvePath(x1: Int, y1: Int, x2: Int, y2: Int) {
            var x = x1.coerceIn(1, width - 2)
            var y = y1.coerceIn(1, height - 2)
            val horizontalFirst = rng.nextBoolean()
            fun stepX() {
                while (x != x2) {
                    carve(x, y)
                    x += if (x2 > x) 1 else -1
                }
            }
            fun stepY() {
                while (y != y2) {
                    carve(x, y)
                    y += if (y2 > y) 1 else -1
                }
            }
            if (horizontalFirst) {
                stepX()
                stepY()
            } else {
                stepY()
                stepX()
            }
            carve(x, y)
        }
        fun nearestNode(x: Int, y: Int): Pair<Int, Int> {
            var bestX = nodeXs.first()
            var bestY = nodeYs.first()
            var bestDist = Int.MAX_VALUE
            for (ny in nodeYs) for (nx in nodeXs) {
                val dist = kotlin.math.abs(nx - x) + kotlin.math.abs(ny - y)
                if (dist < bestDist) {
                    bestDist = dist
                    bestX = nx
                    bestY = ny
                }
            }
            return bestX to bestY
        }

        val cols = nodeXs.size
        val rows = nodeYs.size
        val visited = BooleanArray(cols * rows)
        fun nodeIndex(cx: Int, cy: Int) = cy * cols + cx
        fun carveNode(cx: Int, cy: Int) = carve(nodeXs[cx], nodeYs[cy])

        val stack = ArrayList<Int>()
        val seedCx = (cols / 2 + rng.nextInt(-cols / 4, cols / 4 + 1)).coerceIn(0, cols - 1)
        val seedCy = (rows / 2 + rng.nextInt(-rows / 4, rows / 4 + 1)).coerceIn(0, rows - 1)
        visited[nodeIndex(seedCx, seedCy)] = true
        carveNode(seedCx, seedCy)
        stack.add(nodeIndex(seedCx, seedCy))

        while (stack.isNotEmpty()) {
            val stackPos = if (rng.nextDouble() < rules.mazeBranchDensity) rng.nextInt(stack.size) else stack.lastIndex
            val current = stack[stackPos]
            val cx = current % cols
            val cy = current / cols
            val neighbors = ArrayList<Pair<Int, Int>>()
            for ((dx, dy) in CARDINAL_DIRS) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until cols && ny in 0 until rows && !visited[nodeIndex(nx, ny)]) {
                    neighbors.add(nx to ny)
                }
            }
            if (neighbors.isEmpty()) {
                stack.removeAt(stackPos)
                continue
            }
            val (nx, ny) = neighbors[rng.nextInt(neighbors.size)]
            visited[nodeIndex(nx, ny)] = true
            carve((nodeXs[cx] + nodeXs[nx]) / 2, (nodeYs[cy] + nodeYs[ny]) / 2)
            carveNode(nx, ny)
            stack.add(nodeIndex(nx, ny))
        }

        for (cy in 0 until rows) for (cx in 0 until cols) {
            if (cx + 1 < cols && rng.nextDouble() < rules.mazeLoopDensity) {
                carve((nodeXs[cx] + nodeXs[cx + 1]) / 2, nodeYs[cy])
            }
            if (cy + 1 < rows && rng.nextDouble() < rules.mazeLoopDensity) {
                carve(nodeXs[cx], (nodeYs[cy] + nodeYs[cy + 1]) / 2)
            }
        }

        if (rules.mazeEmptyCenter && rules.mazeHubSize > 0.05 && width >= 64 && height >= 48) {
            carveCentralHub(cells, width, height, rules, nodeXs, nodeYs, ::carve, ::carvePath, ::nearestNode)
        }

        for (p in doorPockets) {
            val cx = ((p.x0 + p.x1) / 2).coerceIn(1, width - 2)
            val cy = ((p.y0 + p.y1) / 2).coerceIn(1, height - 2)
            val (tx, ty) = nearestNode(cx, cy)
            carvePath(cx, cy, tx, ty)
        }
    }

    private fun carveSmallMazeFallback(cells: IntArray, width: Int, height: Int, doorPockets: List<DoorPocket>) {
        val cx = width / 2
        val cy = height / 2
        fun carve(x: Int, y: Int) {
            if (x in 1 until width - 1 && y in 1 until height - 1) cells[y * width + x] = BiomeCell.AIR
        }
        for (x in 1 until width - 1) carve(x, cy)
        for (y in 1 until height - 1) carve(cx, y)
        for (p in doorPockets) {
            val px = ((p.x0 + p.x1) / 2).coerceIn(1, width - 2)
            val py = ((p.y0 + p.y1) / 2).coerceIn(1, height - 2)
            var x = px
            while (x != cx) {
                carve(x, py)
                x += if (cx > x) 1 else -1
            }
            var y = py
            while (y != cy) {
                carve(cx, y)
                y += if (cy > y) 1 else -1
            }
            carve(cx, cy)
        }
    }

    private fun carveCentralHub(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        nodeXs: List<Int>,
        nodeYs: List<Int>,
        carve: (Int, Int) -> Unit,
        carvePath: (Int, Int, Int, Int) -> Unit,
        nearestNode: (Int, Int) -> Pair<Int, Int>,
    ) {
        fun idx(x: Int, y: Int) = y * width + x
        val halfW = (4 + (rules.mazeHubSize * 6).toInt()).coerceIn(4, width / 5)
        val halfH = (3 + (rules.mazeHubSize * 4).toInt()).coerceIn(3, height / 5)
        val x0 = (width / 2 - halfW).coerceIn(BiomeCell.BORDER + 2, width - BiomeCell.BORDER - 6)
        val x1 = (width / 2 + halfW).coerceIn(x0 + 5, width - BiomeCell.BORDER - 3)
        val y0 = (height / 2 - halfH).coerceIn(BiomeCell.BORDER + 2, height - BiomeCell.BORDER - 6)
        val y1 = (height / 2 + halfH).coerceIn(y0 + 5, height - BiomeCell.BORDER - 3)

        for (y in y0..y1) for (x in x0..x1) {
            val wall = x == x0 || x == x1 || y == y0 || y == y1
            cells[idx(x, y)] = if (wall) BiomeCell.SOLID else BiomeCell.AIR
        }

        val midX = (x0 + x1) / 2
        val midY = (y0 + y1) / 2
        val offsets = if (rules.mazeHubSize > 0.55) intArrayOf(-2, 2) else intArrayOf(0)
        val openings = ArrayList<Pair<Int, Int>>()
        for (off in offsets) {
            openings += (midX + off).coerceIn(x0 + 1, x1 - 1) to y0
            openings += (midX + off).coerceIn(x0 + 1, x1 - 1) to y1
            openings += x0 to (midY + off).coerceIn(y0 + 1, y1 - 1)
            openings += x1 to (midY + off).coerceIn(y0 + 1, y1 - 1)
        }
        for ((ox, oy) in openings.distinct()) {
            carve(ox, oy)
            val outwardX = when (ox) {
                x0 -> ox - 2
                x1 -> ox + 2
                else -> ox
            }.coerceIn(nodeXs.first(), nodeXs.last())
            val outwardY = when (oy) {
                y0 -> oy - 2
                y1 -> oy + 2
                else -> oy
            }.coerceIn(nodeYs.first(), nodeYs.last())
            val (tx, ty) = nearestNode(outwardX, outwardY)
            carvePath(ox, oy, tx, ty)
        }
    }

    /**
     * Constructed facility: horizontal decks (flat-floored halls) stacked at a
     * regular pitch, joined by rectangular connector shafts that alternate
     * sides. Some hall segments widen upward into taller rooms; 1-wide bulkhead
     * pillars with floor-level doorways break long halls up. No cellular
     * smoothing, so every edge stays axis-aligned.
     */
    private fun structureRectilinear(cells: IntArray, width: Int, height: Int, rng: Random) {
        cells.fill(BiomeCell.SOLID)
        val left = BiomeCell.BORDER
        val right = width - BiomeCell.BORDER
        fun carve(x: Int, y: Int) {
            if (x in left until right && y in BiomeCell.BORDER until height - BiomeCell.BORDER) {
                cells[y * width + x] = BiomeCell.AIR
            }
        }
        val hallH = rng.nextInt(3, 5)
        val spacing = hallH + rng.nextInt(4, 8)
        // Deck tops, collected bottom-up (decks[0] is the lowest hall).
        val decks = ArrayList<Int>()
        var top = height - BiomeCell.BORDER - hallH
        while (top >= BiomeCell.BORDER + 1) {
            decks.add(top)
            top -= spacing
        }
        if (decks.isEmpty()) decks.add((height / 2 - hallH / 2).coerceAtLeast(BiomeCell.BORDER))

        for (deckTop in decks) {
            for (y in deckTop until deckTop + hallH) for (x in left until right) carve(x, y)
            // Widen some segments upward into taller rooms.
            var x = left
            while (x < right) {
                val segLen = rng.nextInt(8, 18)
                if (rng.nextDouble() < 0.35) {
                    val extra = rng.nextInt(2, 5)
                    for (yy in (deckTop - extra).coerceAtLeast(BiomeCell.BORDER) until deckTop) {
                        for (xx in x until minOf(x + segLen, right)) carve(xx, yy)
                    }
                }
                x += segLen + rng.nextInt(2, 6)
            }
            // Bulkhead pillars: leave a doorway at the floor, solid above.
            val doorway = (hallH - 1).coerceAtLeast(2)
            var px = left + rng.nextInt(8, 14)
            while (px < right - 4) {
                if (rng.nextDouble() < 0.5) {
                    for (y in (deckTop - 6).coerceAtLeast(BiomeCell.BORDER) until deckTop + hallH - doorway) {
                        cells[y * width + px] = BiomeCell.SOLID
                    }
                }
                px += rng.nextInt(9, 16)
            }
        }
        // Connector shafts between adjacent decks, alternating sides.
        val shaftW = rng.nextInt(3, 5)
        for (i in 0 until decks.size - 1) {
            val lowerTop = decks[i]
            val upperTop = decks[i + 1]
            val span = right - left - shaftW
            if (span <= 0) continue
            val base = if (i % 2 == 0) left + span / 5 else left + span * 4 / 5
            val sx = (base + rng.nextInt(-4, 5)).coerceIn(left, left + span)
            for (y in upperTop until lowerTop + hallH) for (x in sx until sx + shaftW) carve(x, y)
        }
    }

    /**
     * Inhabited settlement: a flat street along the bottom with hollow
     * buildings rising from it — 1-thick walls, interior floors with stair
     * gaps, a street-level doorway, and sometimes a roof hatch. Open sky above
     * is left to the platform mutator for rooftop walkways.
     */
    private fun structureSettlement(cells: IntArray, width: Int, height: Int, rng: Random) {
        cells.fill(BiomeCell.AIR)
        val groundTop = height - BiomeCell.BORDER - rng.nextInt(2, 4)
        for (y in groundTop until height) for (x in 0 until width) cells[y * width + x] = BiomeCell.SOLID
        val maxBh = groundTop - BiomeCell.BORDER - 5
        if (maxBh < 5) return
        var x = BiomeCell.BORDER + rng.nextInt(2, 6)
        while (x < width - BiomeCell.BORDER - 7) {
            val bw = rng.nextInt(7, 15).coerceAtMost(width - BiomeCell.BORDER - x)
            if (bw < 6) break
            val bh = rng.nextInt(5, maxBh + 1)
            buildBuilding(cells, width, x, groundTop, bw, bh, rng)
            x += bw + rng.nextInt(4, 10)
        }
    }

    private fun buildBuilding(
        cells: IntArray,
        width: Int,
        x0: Int,
        groundTop: Int,
        bw: Int,
        bh: Int,
        rng: Random,
    ) {
        val top = groundTop - bh
        val x1 = x0 + bw - 1
        for (y in top until groundTop) for (x in x0..x1) {
            val isWall = x == x0 || x == x1 || y == top
            cells[y * width + x] = if (isWall) BiomeCell.SOLID else BiomeCell.AIR
        }
        // Interior floors with alternating 2-wide stair gaps.
        var fy = groundTop - 5
        var gapLeft = rng.nextBoolean()
        while (fy > top + 2) {
            for (x in x0 + 1 until x1) cells[fy * width + x] = BiomeCell.SOLID
            val gapX = if (gapLeft) x0 + 1 else x1 - 2
            cells[fy * width + gapX] = BiomeCell.AIR
            cells[fy * width + gapX + 1] = BiomeCell.AIR
            gapLeft = !gapLeft
            fy -= rng.nextInt(4, 6)
        }
        // Street-level doorway on one side.
        val doorX = if (rng.nextBoolean()) x0 else x1
        for (dy in 1..3) cells[(groundTop - dy) * width + doorX] = BiomeCell.AIR
        // Roof hatch for rooftop access.
        if (rng.nextDouble() < 0.6 && x1 - 2 >= x0 + 2) {
            val hx = rng.nextInt(x0 + 2, x1 - 2)
            cells[top * width + hx] = BiomeCell.AIR
            cells[top * width + hx + 1] = BiomeCell.AIR
        }
    }

    /**
     * Remix: reproduce the original room's macro silhouette from a coarse
     * downsample, but re-roll every mixed-density region (platform clusters,
     * ledges, rubble) so the room keeps its shape while all the detail moves.
     */
    private fun structureRemix(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        rng: Random,
        originalSolid: BooleanArray,
    ) {
        val c = 3
        var cy0 = 0
        while (cy0 < height) {
            var cx0 = 0
            while (cx0 < width) {
                var solid = 0
                var count = 0
                for (y in cy0 until minOf(cy0 + c, height)) {
                    for (x in cx0 until minOf(cx0 + c, width)) {
                        count++
                        if (originalSolid[y * width + x]) solid++
                    }
                }
                val frac = solid.toDouble() / count
                val p = when {
                    frac >= 0.85 -> 0.98
                    frac <= 0.15 -> 0.02
                    else -> 0.25 + frac * 0.5
                }
                for (y in cy0 until minOf(cy0 + c, height)) {
                    for (x in cx0 until minOf(cx0 + c, width)) {
                        cells[y * width + x] = if (rng.nextDouble() < p) BiomeCell.SOLID else BiomeCell.AIR
                    }
                }
                cx0 += c
            }
            cy0 += c
        }
        smooth(cells, width, height, rules)
    }

    private val CARDINAL_DIRS = arrayOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
}
