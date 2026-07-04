package com.supermetroid.editor.procgen

import kotlin.math.ceil
import kotlin.random.Random

internal object StructureAlgorithms {
    fun build(cells: IntArray, width: Int, height: Int, rules: BiomeRules, rng: Random) {
        when (rules.algorithm) {
            StructureAlgorithm.CAVE -> structureCave(cells, width, height, rules, rng)
            StructureAlgorithm.CHAMBERS -> structureChambers(cells, width, height, rng)
            StructureAlgorithm.VERTICAL -> structureVertical(cells, width, height, rules, rng)
            StructureAlgorithm.OPEN_GALLERY -> structureGallery(cells, width, height, rules, rng)
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

    private val CARDINAL_DIRS = arrayOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
}
