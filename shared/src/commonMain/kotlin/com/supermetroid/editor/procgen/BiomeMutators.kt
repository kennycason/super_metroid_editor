package com.supermetroid.editor.procgen

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

internal object BiomeMutators {
    fun applyAll(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        addPlatforms(cells, width, height, rules, editable, rng)
        if (BiomeMutator.CEILING_FANGS in rules.mutators) {
            addCeilingFangs(cells, width, height, rules, editable, rng)
        }
        if (BiomeMutator.SPIKE_POCKETS in rules.mutators) {
            addSpikePockets(cells, width, height, rules, editable, rng)
        }
        if (BiomeMutator.RUBBLE_PILES in rules.mutators) {
            addRubblePiles(cells, width, height, rules, editable, rng)
        }
        if (BiomeMutator.BOMB_WALLS in rules.mutators) {
            addBombSeams(cells, width, height, rules, editable, rng)
        }
        if (BiomeMutator.HIDDEN_PASSAGES in rules.mutators) {
            addHiddenPassages(cells, width, height, rules, editable, rng)
        }
    }

    fun removeFloatingDebris(cells: IntArray, width: Int, height: Int, editable: BooleanArray) {
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if (isPlacedBlock(cells[i]) &&
                editable[i] &&
                isOpenForCleanup(cells[i - 1]) && isOpenForCleanup(cells[i + 1]) &&
                isOpenForCleanup(cells[i - width]) && isOpenForCleanup(cells[i + width])
            ) {
                cells[i] = BiomeCell.AIR
            }
        }
        removeShortFloatingRuns(cells, width, height, editable)
    }

    private fun addPlatforms(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        if (rules.platformDensity <= 0.01) return
        val crumbleBridges = BiomeMutator.CRUMBLE_BRIDGES in rules.mutators
        val candidates = platformCandidates(cells, width, height, rules)
        if (candidates.isEmpty()) return

        val screenArea = width * height / 256.0
        val styleFactor = when (rules.algorithm) {
            StructureAlgorithm.OPEN_GALLERY -> 0.72
            StructureAlgorithm.VERTICAL -> 0.58
            StructureAlgorithm.CHAMBERS -> 0.42
            StructureAlgorithm.CAVE -> if (rules.style == BiomeStyle.WARREN) 0.28 else 0.36
            StructureAlgorithm.MAZE -> 0.0
            StructureAlgorithm.RECTILINEAR -> 0.30
            StructureAlgorithm.SETTLEMENT -> 0.55
            StructureAlgorithm.REMIX -> 0.45
            StructureAlgorithm.WFC -> 0.0
        }
        val maxPlatforms = (screenArea * 0.44).roundToInt().coerceAtLeast(2)
        val minPlatforms = if (rules.platformDensity > 0.20) 1 else 0
        val target = (screenArea * rules.platformDensity * styleFactor)
            .roundToInt()
            .coerceIn(minPlatforms, maxPlatforms)
        if (target <= 0) return

        val placed = ArrayList<PlacedPlatform>()
        val ordered = candidates.shuffled(rng).sortedWith(
            compareByDescending<PlatformCandidate> { it.length }.thenBy { it.y }
        )
        for (candidate in ordered) {
            if (placed.size >= target) break
            val minLen = if (rules.algorithm == StructureAlgorithm.OPEN_GALLERY) 5 else 4
            val preferredMax = when (rules.algorithm) {
                StructureAlgorithm.OPEN_GALLERY -> 11
                StructureAlgorithm.VERTICAL -> 8
                // Longer straight walkways suit the rooftop skyline.
                StructureAlgorithm.SETTLEMENT -> 9
                else -> 7
            }
            val maxLen = minOf(
                preferredMax,
                (candidate.length * 0.58).roundToInt().coerceAtLeast(minLen)
            )
            if (candidate.length < minLen + 2 || maxLen < minLen) continue
            val len = rng.nextInt(minLen, maxLen + 1)
            val lo = candidate.x0 + 1
            val hi = candidate.x1 - len - 1
            if (hi < lo) continue
            val px = rng.nextInt(lo, hi + 1)
            val platform = PlacedPlatform(px, px + len, candidate.y)
            if (platformConflicts(platform, placed)) continue
            val asCrumble = crumbleBridges && rng.nextDouble() < rules.destructibleDensity * 0.45
            for (x in platform.x0 until platform.x1) {
                val ci = candidate.y * width + x
                if (editable[ci]) cells[ci] = if (asCrumble) BiomeCell.CRUMBLE else BiomeCell.SOLID
            }
            placed.add(platform)
        }
    }

    private fun platformCandidates(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
    ): List<PlatformCandidate> {
        val candidates = ArrayList<PlatformCandidate>()
        val rowStep = when (rules.algorithm) {
            StructureAlgorithm.OPEN_GALLERY -> 7
            StructureAlgorithm.VERTICAL -> 6
            else -> 8
        }
        val minRun = if (rules.algorithm == StructureAlgorithm.OPEN_GALLERY) 9 else 8
        var y = BiomeCell.BORDER + 5
        while (y < height - BiomeCell.BORDER - 4) {
            var x = BiomeCell.BORDER + 1
            while (x < width - BiomeCell.BORDER - 1) {
                val start = x
                while (x < width - BiomeCell.BORDER - 1 && isOpenColumn(cells, width, height, x, y)) x++
                val run = x - start
                if (run >= minRun) candidates.add(PlatformCandidate(start, x, y))
                x = maxOf(x + 1, start + 1)
            }
            y += rowStep
        }
        return candidates
    }

    private fun isOpenColumn(cells: IntArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        if (y < 2 || y >= height - 3) return false
        for (dy in -2..2) if (cells[(y + dy) * width + x] != BiomeCell.AIR) return false
        return true
    }

    private fun platformConflicts(candidate: PlacedPlatform, placed: List<PlacedPlatform>): Boolean {
        for (other in placed) {
            val vertical = abs(candidate.y - other.y)
            val horizontalGap = when {
                candidate.x1 < other.x0 -> other.x0 - candidate.x1
                other.x1 < candidate.x0 -> candidate.x0 - other.x1
                else -> 0
            }
            if (vertical < 5 && horizontalGap < 8) return true
            if (vertical < 10 && abs(candidate.centerX - other.centerX) < 12) return true
        }
        return false
    }

    private fun addSpikePockets(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        var y = 1
        while (y < height - 1) {
            var x = BiomeCell.BORDER
            while (x < width - BiomeCell.BORDER) {
                var run = 0
                while (x + run < width - BiomeCell.BORDER &&
                    cells[y * width + x + run] == BiomeCell.AIR &&
                    cells[(y + 1) * width + x + run] == BiomeCell.SOLID
                ) {
                    run++
                }
                if (run >= 4 && rng.nextDouble() < rules.hazardDensity * 0.35) {
                    val len = rng.nextInt(2, minOf(5, run))
                    val sx = x + rng.nextInt(0, run - len + 1)
                    for (i in 0 until len) {
                        val ci = (y + 1) * width + sx + i
                        if (editable[ci]) cells[ci] = BiomeCell.SPIKE
                    }
                }
                x += maxOf(run, 1)
            }
            y++
        }
    }

    private fun addCeilingFangs(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        val candidates = ArrayList<Pair<Int, Int>>()
        for (y in 1 until height - 3) for (x in BiomeCell.BORDER until width - BiomeCell.BORDER) {
            val i = y * width + x
            if (cells[i] == BiomeCell.SOLID &&
                cells[i + width] == BiomeCell.AIR &&
                cells[i + width * 2] == BiomeCell.AIR
            ) {
                candidates.add(x to y)
            }
        }
        val screenArea = width * height / 256.0
        val target = (screenArea * (0.10 + rules.platformDensity * 0.08))
            .roundToInt()
            .coerceIn(0, (screenArea * 0.25).roundToInt().coerceAtLeast(1))
        val placed = ArrayList<Pair<Int, Int>>()
        for ((x, y) in candidates.shuffled(rng)) {
            if (placed.size >= target) break
            if (placed.any { abs(it.first - x) < 7 && abs(it.second - y) < 5 }) continue
            val len = rng.nextInt(1, 3)
            var canPlace = true
            for (d in 1..len) {
                val ci = (y + d) * width + x
                if (y + d >= height - 2 || cells[ci] != BiomeCell.AIR || !editable[ci]) canPlace = false
            }
            if (!canPlace) continue
            for (d in 1..len) {
                cells[(y + d) * width + x] = BiomeCell.SOLID
            }
            placed.add(x to y)
        }
    }

    private fun addRubblePiles(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        for (y in 2 until height - 2) for (x in BiomeCell.BORDER until width - BiomeCell.BORDER) {
            val i = y * width + x
            if (cells[i] == BiomeCell.AIR && cells[i + width] == BiomeCell.SOLID && cells[i - width] == BiomeCell.AIR &&
                rng.nextDouble() < rules.destructibleDensity * 0.04
            ) {
                val h = rng.nextInt(1, 3)
                for (d in 0 until h) {
                    val ci = (y - d) * width + x
                    if (y - d > 1 && cells[ci] == BiomeCell.AIR && editable[ci]) cells[ci] = BiomeCell.CRUMBLE
                }
            }
        }
    }

    private fun addBombSeams(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        for (y in BiomeCell.BORDER until height - BiomeCell.BORDER) {
            for (x in BiomeCell.BORDER until width - BiomeCell.BORDER) {
                val i = y * width + x
                if (cells[i] != BiomeCell.SOLID) continue
                val thinH = cells[i - 1] == BiomeCell.AIR && cells[i + 1] == BiomeCell.AIR &&
                    cells[i - width] == BiomeCell.SOLID && cells[i + width] == BiomeCell.SOLID
                val thinV = cells[i - width] == BiomeCell.AIR && cells[i + width] == BiomeCell.AIR &&
                    cells[i - 1] == BiomeCell.SOLID && cells[i + 1] == BiomeCell.SOLID
                if ((thinH || thinV) && editable[i] && rng.nextDouble() < rules.destructibleDensity * 0.5) {
                    cells[i] = BiomeCell.BOMB
                }
            }
        }
    }

    private fun addHiddenPassages(
        cells: IntArray,
        width: Int,
        height: Int,
        rules: BiomeRules,
        editable: BooleanArray,
        rng: Random,
    ) {
        val target = ((width * height) / 1500.0 * (0.5 + rules.destructibleDensity)).toInt().coerceIn(1, 6)
        var placed = 0
        var attempts = 0
        while (placed < target && attempts < 300) {
            attempts++
            val x = rng.nextInt(BiomeCell.BORDER + 1, width - BiomeCell.BORDER - 3)
            val y = rng.nextInt(BiomeCell.BORDER + 1, height - BiomeCell.BORDER - 3)
            val i = y * width + x
            if (cells[i] != BiomeCell.SOLID || cells[i - 1] != BiomeCell.AIR) continue
            var ok = true
            for (dy in 0..1) for (dx in 0..2) {
                val ci = (y + dy) * width + x + dx
                if (!editable[ci] || cells[ci] != BiomeCell.SOLID) ok = false
            }
            if (!ok) continue
            for (dy in 0..1) {
                cells[(y + dy) * width + x] = BiomeCell.SHOT_HIDDEN
                cells[(y + dy) * width + x + 1] = BiomeCell.AIR
                cells[(y + dy) * width + x + 2] = BiomeCell.AIR
            }
            placed++
        }
    }

    private fun removeShortFloatingRuns(cells: IntArray, width: Int, height: Int, editable: BooleanArray) {
        for (y in 2 until height - 2) {
            var x = 1
            while (x < width - 1) {
                val start = x
                while (x < width - 1 &&
                    isPlacedBlock(cells[y * width + x]) &&
                    isOpenForCleanup(cells[(y - 1) * width + x]) &&
                    isOpenForCleanup(cells[(y + 1) * width + x])
                ) {
                    x++
                }
                val run = x - start
                if (run in 1..3 &&
                    isOpenForCleanup(cells[y * width + start - 1]) &&
                    isOpenForCleanup(cells[y * width + x])
                ) {
                    var canRemove = true
                    for (rx in start until x) {
                        if (!editable[y * width + rx]) canRemove = false
                    }
                    if (canRemove) {
                        for (rx in start until x) cells[y * width + rx] = BiomeCell.AIR
                    }
                }
                x = maxOf(x + 1, start + 1)
            }
        }
    }

    private fun isPlacedBlock(cell: Int): Boolean =
        cell == BiomeCell.SOLID ||
            cell == BiomeCell.CRUMBLE ||
            cell == BiomeCell.SHOT_HIDDEN ||
            cell == BiomeCell.BOMB ||
            cell == BiomeCell.SPIKE

    private fun isOpenForCleanup(cell: Int): Boolean =
        cell == BiomeCell.AIR ||
            cell == BiomeCell.CRUMBLE ||
            cell == BiomeCell.SHOT_HIDDEN ||
            cell == BiomeCell.BOMB

    private data class PlatformCandidate(val x0: Int, val x1: Int, val y: Int) {
        val length: Int get() = x1 - x0
    }

    private data class PlacedPlatform(val x0: Int, val x1: Int, val y: Int) {
        val centerX: Int get() = (x0 + x1) / 2
    }
}
