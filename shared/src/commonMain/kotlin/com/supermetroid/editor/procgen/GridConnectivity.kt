package com.supermetroid.editor.procgen

import kotlin.math.abs
import kotlin.random.Random

internal object GridConnectivity {
    /** Flood-fill passable regions; fill tiny ones, tunnel the rest into one region. */
    fun ensureConnected(
        cells: IntArray,
        width: Int,
        height: Int,
        preserved: BooleanArray,
        rng: Random,
        passable: (Int) -> Boolean,
    ) {
        repeat(60) {
            val comp = IntArray(cells.size) { -1 }
            var compCount = 0
            val sizes = ArrayList<Int>()
            for (start in cells.indices) {
                if (!passable(cells[start]) || comp[start] != -1) continue
                var size = 0
                val queue = ArrayDeque<Int>()
                queue.add(start)
                comp[start] = compCount
                while (queue.isNotEmpty()) {
                    val c = queue.removeFirst()
                    size++
                    val cx = c % width
                    val cy = c / width
                    for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) {
                        val nx = cx + dx
                        val ny = cy + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val ni = ny * width + nx
                        if (passable(cells[ni]) && comp[ni] == -1) {
                            comp[ni] = compCount
                            queue.add(ni)
                        }
                    }
                }
                sizes.add(size)
                compCount++
            }
            if (compCount <= 1) return
            val mainComp = sizes.indices.maxBy { sizes[it] }
            var connected = true
            for (ci in 0 until compCount) {
                if (ci == mainComp) continue
                if (sizes[ci] < 8) {
                    for (i in cells.indices) if (comp[i] == ci && !preserved[i]) cells[i] = BiomeCell.SOLID
                    continue
                }
                var bestA = -1
                var bestB = -1
                val aCells = cells.indices.filter { comp[it] == ci }
                val bCells = cells.indices.filter { comp[it] == mainComp }
                val aSample = if (aCells.size > 200) aCells.shuffled(rng).take(200) else aCells
                val bSample = if (bCells.size > 400) bCells.shuffled(rng).take(400) else bCells
                var bestDist = Int.MAX_VALUE
                for (a in aSample) for (b in bSample) {
                    val d = abs(a % width - b % width) + abs(a / width - b / width)
                    if (d < bestDist) {
                        bestDist = d
                        bestA = a
                        bestB = b
                    }
                }
                if (bestA >= 0) {
                    carveTunnel(
                        cells, width, height,
                        bestA % width, bestA / width, bestB % width, bestB / width,
                        thickness = 2, preserved = preserved,
                    )
                    connected = false
                    break
                }
            }
            if (connected) return
        }
    }

    /** Carve an L-shaped air tunnel between two points. */
    fun carveTunnel(
        cells: IntArray,
        width: Int,
        height: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        thickness: Int,
        preserved: BooleanArray?,
    ) {
        fun carve(x: Int, y: Int) {
            for (ty in y until y + thickness) for (tx in x until x + thickness) {
                if (tx in 1 until width - 1 && ty in 1 until height - 1 &&
                    (preserved == null || !preserved[ty * width + tx])
                ) {
                    cells[ty * width + tx] = BiomeCell.AIR
                }
            }
        }
        var x = x1
        while (x != x2) {
            carve(x, y1)
            x += if (x2 > x) 1 else -1
        }
        var y = y1
        while (y != y2) {
            carve(x2, y)
            y += if (y2 > y) 1 else -1
        }
        carve(x2, y2)
    }
}
